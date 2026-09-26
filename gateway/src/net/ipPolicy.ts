/**
 * IP / hostname policy for every server-side remote fetch (OPML URL import,
 * article extraction, image proxy, podcast audio download). Shared by every
 * fetcher — there is deliberately no second SSRF policy in this codebase.
 *
 * Two checks, one policy:
 *  - `checkRemoteHost` screens what the URL literally says (scheme-relative
 *    hostname, literal IPv4/IPv6, legacy decimal shorthand, blocked suffixes).
 *  - `checkRemoteAddress` screens the address a socket would actually be opened
 *    to. It is applied to every DNS answer of every request and every redirect
 *    hop, and the connection is then pinned to the screened address.
 *
 * The gateway runs as a long-lived Node 22 process (gateway/server/index.ts), so
 * DNS *is* resolvable before connecting. An earlier revision of this file
 * assumed a Cloudflare Worker runtime that could not resolve DNS before
 * fetch(); that premise no longer holds. See safeRemoteFetcher.ts for the
 * resolve → screen → pin connect path.
 */
export type HostVerdict = { allowed: true } | { allowed: false; reason: "blocked_hostname" | "blocked_ip" };

const BLOCKED_HOSTNAME_SUFFIXES = [
  "localhost",
  "localhost.localdomain",
  "local",
  "internal",
  "lan",
  "localdomain",
  "home.arpa",
  "intranet"
];

const BLOCKED_HOSTNAMES = new Set([
  "metadata.google.internal",
  "metadata.goog",
  "instance-data",
  "169.254.169.254"
]);

export function checkRemoteHost(rawHostname: string): HostVerdict {
  let host = rawHostname.trim().toLowerCase();
  // Strip IPv6 brackets.
  if (host.startsWith("[") && host.endsWith("]")) host = host.slice(1, -1);
  if (!host) return { allowed: false, reason: "blocked_hostname" };

  if (BLOCKED_HOSTNAMES.has(host)) return { allowed: false, reason: "blocked_hostname" };
  for (const suffix of BLOCKED_HOSTNAME_SUFFIXES) {
    if (host === suffix || host.endsWith(`.${suffix}`)) return { allowed: false, reason: "blocked_hostname" };
  }

  // Literal IPv4 (including decimal shorthand like 2130706433).
  if (/^\d{1,10}$/.test(host)) {
    const value = Number(host);
    if (isBlockedIpv4(value)) return { allowed: false, reason: "blocked_ip" };
    return { allowed: true };
  }
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    const octets = host.split(".").map(Number);
    if (octets.some((octet) => octet > 255)) return { allowed: false, reason: "blocked_ip" };
    const packed = ((octets[0] << 24) | (octets[1] << 16) | (octets[2] << 8) | octets[3]) >>> 0;
    if (isBlockedIpv4(packed)) return { allowed: false, reason: "blocked_ip" };
    return { allowed: true };
  }

  // Literal IPv6 (with or without embedded IPv4 form).
  if (host.includes(":")) {
    if (isBlockedIpv6(host)) return { allowed: false, reason: "blocked_ip" };
    return { allowed: true };
  }

  // Bare number-dot forms like "127.1" are legacy IPv4 shorthand.
  if (/^\d{1,3}(\.\d{1,3}){1,3}$/.test(host)) return { allowed: false, reason: "blocked_ip" };

  return { allowed: true };
}

/**
 * Screens a single resolved/given IP address literal — the address a socket
 * will be opened to. IPv4-mapped (`::ffff:a.b.c.d`) and NAT64 (`64:ff9b::/96`)
 * forms are unwrapped and screened as the IPv4 address they address. Anything
 * that is not a well-formed IP literal is refused (fail closed): a resolver
 * answer must be an address, never a hostname.
 */
export function checkRemoteAddress(rawAddress: string): HostVerdict {
  const address = rawAddress.trim().toLowerCase();
  if (!address) return { allowed: false, reason: "blocked_ip" };

  if (address.includes(":")) {
    // Strip IPv6 brackets, then apply the IPv6 policy (zone indices included).
    const bare = address.startsWith("[") && address.endsWith("]") ? address.slice(1, -1) : address;
    return isBlockedIpv6(bare) ? { allowed: false, reason: "blocked_ip" } : { allowed: true };
  }

  const octets = address.split(".");
  if (octets.length !== 4 || octets.some((octet) => !/^\d{1,3}$/.test(octet) || Number(octet) > 255)) {
    return { allowed: false, reason: "blocked_ip" };
  }
  const packed = ((Number(octets[0]) << 24) | (Number(octets[1]) << 16) | (Number(octets[2]) << 8) | Number(octets[3])) >>> 0;
  return isBlockedIpv4(packed) ? { allowed: false, reason: "blocked_ip" } : { allowed: true };
}

function isBlockedIpv4(packed: number): boolean {
  const first = (packed >>> 24) & 0xff;
  const second = (packed >>> 16) & 0xff;
  // 0.0.0.0/8, 10/8, 127/8 (loopback), 169.254/16 (link-local + metadata),
  // 172.16/12 (private), 192.168/16 (private), 224/4 (multicast), 240/4 (reserved).
  if (first === 0 || first === 10 || first === 127 || first >= 224) return true;
  if (first === 169 && second === 254) return true;
  if (first === 172 && second >= 16 && second <= 31) return true;
  if (first === 192 && second === 168) return true;
  // 100.64/10 (CGNAT) — not publicly routable.
  if (first === 100 && second >= 64 && second <= 127) return true;
  // 192.0.0/24 (IETF protocol assignments) and 198.18/15 (benchmarking) are not
  // usable public destinations either.
  if (first === 192 && second === 0) return true;
  if (first === 198 && (second === 18 || second === 19)) return true;
  return false;
}

function isBlockedIpv6(host: string): boolean {
  const groups = expandIpv6(host);
  if (!groups) return true; // unparseable literal -> refuse
  const first = groups[0];
  const second = groups[1];
  // :: (unspecified), ::1 (loopback), fc00::/7 (unique local), fe80::/10 (link-local),
  // ff00::/8 (multicast).
  if (groups.every((group) => group === 0)) return true;
  if (groups.slice(0, 7).every((group) => group === 0) && groups[7] === 1) return true;
  if ((first & 0xfe00) === 0xfc00) return true;
  if ((first & 0xffc0) === 0xfe80) return true;
  if ((first & 0xff00) === 0xff00) return true;
  // ::ffff:a.b.c.d — IPv4-mapped; apply the IPv4 policy to the embedded address.
  if (groups.slice(0, 5).every((group) => group === 0) && groups[5] === 0xffff) {
    const packed = ((groups[6] << 16) | groups[7]) >>> 0;
    return isBlockedIpv4(packed);
  }
  // 64:ff9b::/96 (NAT64) maps to IPv4 as well.
  if (first === 0x64 && second === 0xff9b && groups.slice(2, 5).every((group) => group === 0)) {
    const packed = ((groups[6] << 16) | groups[7]) >>> 0;
    return isBlockedIpv4(packed);
  }
  // 2002::/16 (6to4) and 2001::/32 (Teredo) embed an IPv4 address in a way that
  // resolvers and middleboxes disagree about; refuse them rather than guess.
  if (first === 0x2002) return true;
  if (first === 0x2001 && second === 0x0000) return true;
  return false;
}

function expandIpv6(host: string): number[] | null {
  let value = host;
  // Strip zone index.
  const zoneIndex = value.indexOf("%");
  if (zoneIndex >= 0) value = value.slice(0, zoneIndex);

  // Embedded IPv4 tail.
  let v4Tail: number[] | null = null;
  const lastColon = value.lastIndexOf(":");
  if (lastColon >= 0 && value.slice(lastColon + 1).includes(".")) {
    const tail = value.slice(lastColon + 1);
    if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(tail)) return null;
    const octets = tail.split(".").map(Number);
    if (octets.some((octet) => octet > 255)) return null;
    v4Tail = [(octets[0] << 8) | octets[1], (octets[2] << 8) | octets[3]];
    value = value.slice(0, lastColon + 1) + "0:0";
  }

  const halves = value.split("::");
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const missing = 8 - head.length - tail.length;
  if (halves.length === 2 && missing < 0) return null;
  if (halves.length === 1 && head.length !== 8) return null;
  const groups = [...head, ...Array(halves.length === 2 ? missing : 0).fill(0), ...tail]
    .map((group) => (/^[0-9a-f]{1,4}$/.test(group) ? parseInt(group, 16) : NaN));
  if (groups.some((group) => Number.isNaN(group))) return null;
  const result = v4Tail ? [...groups.slice(0, 6), v4Tail[0], v4Tail[1]] : groups;
  if (result.length !== 8) return null;
  return result;
}
