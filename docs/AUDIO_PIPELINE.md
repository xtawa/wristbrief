# Audio services: STT and TTS

The persistent gateway supports two independent audio tasks. The admin page at `/admin/audio` contains six disabled presets:

| Task | Provider | Model preset | Key slot |
| --- | --- | --- | --- |
| STT | Deepgram | `nova-3` | `AI_PROVIDER_SECRET_2` |
| STT | Xiaomi MiMo | `mimo-v2.5-asr` | `AI_PROVIDER_SECRET_1` |
| TTS | Xiaomi MiMo | `mimo-v2.5-tts`, `mimo_default` voice | `AI_PROVIDER_SECRET_1` |
| TTS | Deepgram | `aura-2-thalia-en` | `AI_PROVIDER_SECRET_2` |
| STT | OpenAI | `gpt-4o-transcribe` | `AI_PROVIDER_SECRET_3` |
| TTS | OpenAI | `gpt-4o-mini-tts`, `alloy` voice | `AI_PROVIDER_SECRET_3` |

Save the provider key at `/admin/provider-keys`, then enable a preset at `/admin/audio`. Each task chooses the lowest priority number among enabled presets that has a key. Presets can be disabled, reprioritized and edited independently. MiMo ASR supports Chinese and English; use a suitable provider and voice for other languages. Never use the AI text-model form to configure STT or TTS.

## Outbound fetch policy (all server-side remote fetches)

All server-side remote URL fetching goes through one module, `gateway/src/net/safeRemoteFetcher.ts`, with one shared IP policy in `gateway/src/net/ipPolicy.ts`. Downloads (`safeFetch`) and provider POSTs (`safePinnedPost`) share the same resolver, the same screening, the same pinned connect and the same concurrency bound.

**Scope.** The pinning policy covers every server-side remote URL fetch whose destination is not a compile-time constant: OPML import, article extraction, the image proxy, the podcast audio download, every redirect hop of each, **and** the AI text-provider POST in `gateway/src/provider.ts` (`safePinnedPost`; https-only, exact `AI_ALLOWED_HOSTS` allowlist, resolves, screens and pins, and refuses a 3xx rather than following it). The only fetches outside it are the six speech-provider calls (`recognize`/`synthesize`: POSTs to the fixed `api.xiaomimimo.com`, `api.deepgram.com` and `api.openai.com` hosts) — their destination is a compile-time literal, and they are sent with `redirect: "error"` so a provider 3xx can never move the request, with its credentials and the user's audio, to a host the gateway did not choose.

The gateway is a long-running Node 22 process, so DNS is resolved *before* connecting and the connection is pinned to what was validated:

1. **URL policy** — `http`/`https` only, no credentials in the URL, and `checkRemoteHost` screens the literal hostname (blocked suffixes such as `.local`/`.internal`, metadata names, literal IPv4/IPv6, legacy decimal shorthand).
2. **Resolve** — the hop's hostname is resolved with `node:dns/promises` `lookup` (`getaddrinfo`, the same resolver a default Node connection would use).
3. **Screen every answer** — `checkRemoteAddress` is applied to *each* returned address: loopback, RFC1918 private, link-local (including `169.254.169.254`), CGNAT `100.64/10`, IETF/benchmark ranges, multicast, reserved, unspecified, IPv6 unique-local and link-local, plus IPv4-mapped (`::ffff:a.b.c.d`), NAT64 (`64:ff9b::/96`), 6to4 and Teredo forms, which are unwrapped and screened as the IPv4 address they address. If **any** answer is blocked the whole hostname is refused — a public/private mix is the signature of a DNS-rebinding attempt, and picking the public one out of the set is exactly how that attack wins.
4. **Pin the connect** — the chosen address is passed to the transport, which connects with `http(s).request({ host, lookup })` where `lookup` ignores the hostname and always returns the screened address. The address that was validated and the address the socket is opened to cannot diverge, and no second resolution happens inside the connect path. One-off agents (`agent: false`) are used so a pooled socket validated against an older answer can never be reused.
5. **Every redirect hop repeats steps 1–4.** Redirects are never followed automatically (`redirect: manual`); each `Location` is resolved against the current URL, re-screened and re-pinned, bounded by `maxRedirects` (default 5, `too_many_redirects`).

`Host` and TLS SNI/certificate validation keep the original hostname (never the IP), so virtual hosting and certificate checks are unchanged for HTTPS. Response bodies are decompressed (`gzip`, `deflate`, `br`) and the size cap applies to the decoded bytes, so a compressed bomb cannot bypass it.

### Deadlines and limits

- **One deadline covers the whole operation**: DNS resolution, connect, response headers, every redirect hop, and the entire streamed body read. A server that sends headers immediately and then dribbles the body can no longer hold the request open — the previous revision cleared its timer as soon as the response promise resolved and only then read the body, so a stalled body never timed out.
- **Idle/stall timeout** (`idleTimeoutMs`, default `min(30 s, timeoutMs)`) is re-armed on every chunk, so a slow-but-moving transfer is allowed to finish while a stalled one is cut.
- **Size cap is enforced while streaming** (`maxBytes`): the transfer is aborted the moment the cap is crossed, never buffered first and checked afterwards. A declared `Content-Length` above the cap is refused before the first byte.
- **Concurrency cap**: one bounded semaphore limits simultaneous outbound requests across BOTH paths (downloads and provider POSTs) — default 8, `WRISTBRIEF_FETCH_CONCURRENCY`. A burst of requests cannot open unbounded sockets, and the slot is released on success, failure, timeout and redirect abort.
- **Provider POSTs** (`safePinnedPost`) use the same `maxBytes` (default 8 MiB, enforced while streaming), the same idle/stall timeout and the same single deadline as the download path, and they never follow a redirect: a 3xx is returned as a failure with its status.

### Honest residual limits

- The policy screens *addresses*. A publicly routable address that the operator also routes internally (a VPC that announces public space) cannot be distinguished from the internet by the address alone; network-level egress restrictions remain the second layer of defence.
- Resolution itself is unauthenticated (system resolver, no DNSSEC enforcement). The pin guarantees the gateway connects to the address it screened, but it cannot prove the resolver told the truth. A spoofed answer still has to be a public address to pass the policy.
- Proxy environment variables (`HTTP_PROXY`/`HTTPS_PROXY`) are not honoured: the gateway dials the pinned address directly. A deployment that requires an egress proxy must provide it at the network layer.
- When a host has both A and AAAA answers, the IPv4 answer is preferred and pinned. If it has only AAAA and the host has no IPv6 route, the hop fails (`network_error`) instead of trying a second candidate address — two candidate addresses cannot both be pinned.
- 6to4/Teredo IPv6 ranges are refused outright, which is stricter than strictly necessary for public addresses in those ranges.
- A request that cannot get a concurrency slot waits for one (standard semaphore semantics); the wait is not charged against its own deadline. With the audio worker processing one job at a time, at most one long-lived download can hold a slot, so this bound has not needed a queue timeout.

## STT pipeline

`POST /v1/transcripts/request` uses the existing authenticated API and reserves transcript credits. The SQLite job records the audio URL and survives restart. A single server worker claims the job and renews a lease. It fetches audio through the policy above (up to 96 MiB, 5-minute deadline, 30-second stall timeout), runs the locally installed FFmpeg/FFprobe, measures the true duration with FFprobe, caps input duration at 3 hours, converts to 16 kHz mono MP3 at 32 kbps, and splits into 4-minute clips. Each clip is at most 7 MB before MiMo's Base64 encoding (under its documented 10 MB limit). The selected provider receives each clip; the result is saved as transcript text and timed clip-level segments in the object directory. Segments represent clips, not word-level timestamps or speaker diarization.

MiMo API: `POST /v1/chat/completions` with `input_audio`. Deepgram API: `POST /v1/listen` with binary MP3. OpenAI API: `POST /v1/audio/transcriptions` with multipart MP3. Source audio and temporary clips are deleted after processing. Temporary files are created with private permissions. The image ships FFmpeg and FFprobe from Debian packages; local installs need these executables on `PATH`.

### Settlement, quota ceiling and release

- Transcription is charged by the **measured duration actually processed** (`ceil(FFprobe seconds / 60)`, minimum 1), not by the duration the client declared. The declared value only sizes the initial reservation.
- The charge is written to `credit_transactions` **only while the row is still `RESERVED`**, and is committed once at the end. A committed transaction is never restated, so a reclaimed job cannot double-charge.
- **Executable per-job ceiling** `TRANSCRIPT_MAX_BILLABLE_MINUTES` (default 180). A job whose measured duration exceeds it is refused before any provider work, with a whole-reservation release and therefore no partial charge. `TRANSCRIPT_MAX_DURATION_MS` (default 3 h) rejects longer input before transcoding.
- **Failure release is guaranteed**: on every error path the job is marked `failed` and the reservation ends `RELEASED`. The runner releases directly in SQL after `TranscriptService.failJob`, so a failure after the units update cannot leave an orphaned hold.

### Crash and abnormal-interruption recovery

- Leases expire (`TRANSCRIPT_LEASE_MS`, default 240 s) and are renewed by a heartbeat while a job runs. A job reclaimed after `SIGKILL` **reuses the same job row, the same credit transaction and the same object keys**; no new job row is created.
- On reclaim the worker first checks whether a previous attempt already published a ready artifact for that user, content and language. If it did, the job is settled: access is granted, the single reservation is committed and the job is marked completed — without transcribing again. This is the `SIGKILL`-between-artifact-and-status-update case, and it neither double-charges nor publishes a duplicate transcript.
- Stale in-flight state is cleared on claim (`error_code` reset, lease rewritten), so a previous failure cannot poison the next attempt.
- Attempts are bounded by `TRANSCRIPT_MAX_ATTEMPTS` (default 3) for **crash reclaims**: a job repeatedly reclaimed after `SIGKILL` fails cleanly as `audio_attempts_exhausted` and releases its reservation instead of looping forever. An explicitly re-queued job — a user retry through `TranscriptService.resetJobForRetry`, or a graceful-shutdown hand-back — is always processed, so fault tolerance never swallows a user's retry.
- Graceful shutdown (`server.close()` → `audio.stop()`) stops polling, hands an in-flight job back to the queue with an expired lease, and leaves its reservation `RESERVED` — it is a hold, not a charge. The next process resumes the job immediately instead of waiting out the old lease.

## TTS pipeline

`POST /v1/audio/speech` takes `{ "text": "..." }`, requires a mobile Bearer session and available managed AI quota, and returns MP3 (`audio/mpeg`). Input is limited to 2000 characters. The server calls MiMo's audio-enabled chat completions, Deepgram's Speak REST API, or OpenAI's speech endpoint, depending on the enabled TTS preset. Managed quota is reserved before the upstream call; exhausted quota is refused with 429, and a failed generation returns 502 after releasing the reservation, so no partial charge is committed. OpenAI requires that the product disclose to users that the voice is AI generated. The generated audio is returned directly; no synthesis library has yet been added to the Android UI. Long-form narration needs chunking, storage, and a dedicated usage policy before it is shown as a product feature.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `WRISTBRIEF_FETCH_CONCURRENCY` | `8` | Simultaneous outbound requests across every server-side fetch, downloads and provider POSTs alike (one process-global bound). |
| `TRANSCRIPT_MAX_DURATION_MS` | `10800000` (3 h) | Longest input audio the worker will transcode. |
| `TRANSCRIPT_MAX_BILLABLE_MINUTES` | `180` | Hard per-job billing ceiling; beyond it the job is refused and its reservation released. |
| `TRANSCRIPT_MAX_ATTEMPTS` | `3` | Crash reclaims allowed before a job fails as `audio_attempts_exhausted`; explicit retries are always processed. |
| `TRANSCRIPT_LEASE_MS` | `240000` | Job lease length; an expired lease makes a job reclaimable. |

Per-fetch limits stay at the call site: OPML preview 8 MB / 15 s, image proxy 10 MB / 15 s, podcast audio download 96 MiB / 5 min with a 30 s stall timeout.

## Deployment and limits

Build the gateway image with `docker build -t wristbrief-gateway gateway`. Mount `/data` as a writable persistent volume and supply the variables described in `gateway/.env.server.example`. Use a TLS reverse proxy; do not publish the Node port directly. The image includes FFmpeg/FFprobe from Debian; account for the included FFmpeg packages and their licenses when distributing images. The `ffmpeg-static` npm package is not used. Neither upstream API is called without a configured key. The code has contract and conversion tests, but no real paid-provider credentials were available for a live call.

Official API references: [MiMo ASR](https://mimo.mi.com/docs/en-US/api/audio/Speech-Recognition), [MiMo TTS](https://mimo.mi.com/docs/en-US/api/audio/tts), [Deepgram prerecorded audio](https://developers.deepgram.com/docs/pre-recorded-audio), [Deepgram TTS](https://developers.deepgram.com/docs/text-to-speech), [OpenAI STT](https://developers.openai.com/api/docs/guides/speech-to-text), [OpenAI TTS](https://developers.openai.com/api/docs/guides/text-to-speech).
