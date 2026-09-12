# WristBrief Cloud Sync, Content Code, and Shared Transcript Architecture

> Status: **architecture and implementation specification**. This document defines the target design and rollout plan; it does **not** mean the features are already production-ready.
>
> Scope: account-level multi-device sync, Phone ↔ Wear synchronization, podcast playback progress, global episode identity (`Content Code`), reusable transcript artifacts, shared-cache quota rules, privacy boundaries, storage, APIs, background jobs, migration, observability, and release acceptance criteria.

---

## 1. Why this exists

WristBrief already has several important primitives:

- Google-account-backed identity and server sessions.
- A Cloudflare Worker gateway and D1-backed account/membership data.
- Local SQLite content and podcast playback progress on Android phone.
- Wear OS local playback progress.
- Phone ↔ Wear Data Layer synchronization for subscriptions/read/saved state.
- AI summary caching at the gateway.

Those primitives are not yet a complete account-scoped sync system. In particular, a user cannot currently expect all of the following to be true at once:

1. Start a podcast on Phone A, continue on Watch, then continue on Phone B at the same position.
2. Sign in on a replacement phone and recover subscriptions, read/saved state, playback progress, and generated artifacts.
3. Transcribe an episode once and keep that transcript as durable account content.
4. Reuse an existing public transcript generated for the same episode by another WristBrief user.
5. Charge a cache-hit transcript request at **20% of the normal transcript quota**, while not repeatedly charging the same user for reopening an artifact they already obtained.

This document makes those behaviors explicit and implementable.

---

## 2. Product outcomes

The completed system MUST provide these user-visible outcomes.

### 2.1 Account-level multi-device continuity

A signed-in WristBrief account should behave as one library across supported devices:

- Android phone(s)
- Wear OS watch(es)
- future Android tablet/TV clients

Account state must survive device replacement and reinstall where server-side retention permits.

### 2.2 Durable podcast continuity

For each podcast episode, WristBrief must preserve:

- last meaningful playback position
- duration
- playback speed
- completed state
- last-played timestamp
- last writer/device metadata needed for conflict resolution

The experience target is:

```text
Phone A @ 32:14
      ↓ sync
WristBrief Cloud
      ↓
Watch / Phone B / Tablet
      ↓
Resume @ approximately 32:14
```

### 2.3 Durable transcript artifacts

A transcript is a reusable content artifact, not a temporary AI response. Once a transcript is successfully created and retained, it can power:

- transcript reader
- timestamp seeking
- search
- Ask AI
- summaries
- chapters
- translation
- exports
- future TTS or derivative audio

### 2.4 Global transcript reuse

For eligible **public** podcast episodes, WristBrief should avoid retranscribing the same content for every user.

If User A creates a transcript for a public episode and WristBrief verifies/stores it as a reusable artifact, User B requesting the same episode should receive the cached artifact instead of causing another full transcription.

### 2.5 Quota rule

Default transcript quota policy:

| Situation | Transcript quota charged |
|---|---:|
| First successful generation of a public transcript artifact | `1.0x` normal rate |
| First access by a different user when a reusable artifact already exists | `0.2x` normal rate |
| Same user reopening an artifact they already obtained | `0x` |
| First successful generation of private/account-only content | `1.0x` |
| Same user reopening their private artifact | `0x` |
| Explicit user-requested regeneration/new quality version | normally `1.0x`, server policy controlled |

**Clients MUST NOT choose the multiplier.** The gateway calculates quota server-side from artifact/access state.

---

## 3. Non-goals for the first implementation

The first production version does not need to provide:

- real-time collaborative transcript editing
- second-by-second playback uploads
- public transcript search engines or public transcript pages by default
- automatic cross-user sharing of private RSS, paid feeds, signed URLs, local files, or user uploads
- perfect semantic duplicate detection between independently re-encoded audio files
- direct client writes into the global shared transcript pool without server verification

The architecture should leave room for these capabilities without requiring them in the initial rollout.

---

## 4. Core architectural rule: separate account state from global content

WristBrief needs two distinct data planes.

```text
                         WristBrief Cloud
                               │
              ┌────────────────┴────────────────┐
              │                                 │
      Account-scoped state               Global content registry
              │                                 │
      subscriptions                           episode identity
      read/saved                              Content Code
      playback progress                       media fingerprints
      user artifact access                    reusable transcripts
      sync cursors                            derivative artifacts
```

The separation is critical:

- **Account state** answers: “What does this user own/follow/read/save/play?”
- **Global content** answers: “What canonical podcast episode/artifact does this media represent?”

A transcript may be globally reusable while playback position remains private to a user.

---

## 5. Content Code: stable episode identity

### 5.1 Do not expose raw SHA-256 as the permanent product ID

A raw media hash is useful for deduplication, but it is a poor permanent product identity because:

- CDN bytes can change without the logical episode changing.
- publishers can replace an MP3 with a corrected/re-encoded copy.
- multiple feeds can contain different encodes of the same logical episode.
- the user-visible code should remain stable across internal dedup changes.

Therefore WristBrief should use:

```text
Server-assigned canonical content row
              ↓
Stable Content Code
              ↓
WBEP-XXXXXXXX-XXXXXXXX
```

The exact display format can change, but the underlying identifier must be immutable.

Recommended internal primary key:

```text
content_id = UUIDv7 / ULID / equivalent sortable opaque ID
```

Recommended public code:

```text
content_code = "WBEP-" + Crockford/Base32 encoded random/server-derived value
```

Example:

```text
WBEP-7Q2M-4H9D-K8XR
```

### 5.2 Content identity inputs

The resolver may use these signals, from strongest to weakest:

1. verified exact audio SHA-256
2. known media fingerprint alias
3. previously resolved normalized enclosure URL
4. stable feed identity + RSS/Atom GUID
5. explicit provider/platform episode identifier where legally/technically available
6. normalized title + publication time + duration as a candidate heuristic only

Heuristics MUST NOT silently merge global content without sufficient confidence.

### 5.3 Two-stage resolution

A request should not download an entire multi-hour episode just to discover whether the server already knows it.

Use a two-stage process:

#### Stage A — cheap candidate resolution

Client sends episode metadata:

```json
{
  "feedId": "...",
  "feedUrl": "https://...",
  "guid": "...",
  "audioUrl": "https://...",
  "title": "Episode title",
  "publishedAt": "...",
  "durationMs": 6821000
}
```

Server checks existing aliases. If a trustworthy alias resolves to a canonical `content_id`, return its Content Code without media download.

#### Stage B — verification during managed media fetch

When WristBrief must fetch the audio for transcription anyway:

```text
stream audio
   ├── send chunks to transcription pipeline
   └── calculate SHA-256 incrementally
```

After the hash is known:

- attach it as a verified fingerprint to the canonical content row;
- if it matches a different canonical row, queue a safe dedup/merge operation;
- keep old Content Codes as aliases if records are merged.

### 5.4 Content merge invariant

A Content Code that has been issued MUST NOT become a broken link.

If duplicate content records are merged:

```text
old content_code → canonical content_id
```

must remain resolvable through an alias table.

---

## 6. Public, private, and restricted content

“Publicly reachable URL” must not automatically mean “safe to redistribute a full transcript.” Product policy and rights handling should be explicit.

Recommended content visibility/share policy:

```text
PUBLIC_REUSE
ACCOUNT_ONLY
PRIVATE_SOURCE
NO_PERSIST
BLOCKED
```

### 6.1 PUBLIC_REUSE

Eligible for global transcript reuse, subject to product/legal policy.

Typical candidate:

- normal public RSS podcast
- public unauthenticated enclosure
- content not marked restricted by policy/takedown state

### 6.2 ACCOUNT_ONLY / PRIVATE_SOURCE

Never placed into the global cross-user transcript pool.

Examples:

- private RSS feeds
- Patreon/member feeds
- authenticated URLs
- expiring/signed URLs
- local files
- user uploads
- enterprise/private sources

### 6.3 NO_PERSIST

Allow transient processing but do not retain full transcript content after the request if policy requires it.

### 6.4 BLOCKED

Do not transcribe/reuse due to policy, rights request, abuse, or administrative action.

### 6.5 Rights/takedown readiness

The global registry should store enough metadata to support:

- artifact disable/takedown
- source attribution
- rights/policy status
- deletion of reusable R2 objects
- preventing future cache hits for a blocked artifact

The first implementation can keep admin workflows simple, but the schema should not make takedowns impossible.

---

## 7. Storage architecture

Recommended Cloudflare-native layout:

```text
Cloudflare Worker / Gateway
        │
        ├── D1: identities, sync state, metadata, ledgers
        ├── R2: transcript bodies and large derivative artifacts
        ├── Queues: transcription / artifact generation jobs
        └── optional Durable Object or D1 lease: single-flight locking
```

### 7.1 D1 responsibilities

D1 stores compact structured state:

- account devices and cursors
- account subscriptions
- read/saved item state
- playback progress
- content registry
- content aliases/fingerprints
- transcript artifact metadata
- per-user artifact access grants
- background job state
- credit reservation/commit ledger
- tombstones/deletions where needed for sync

### 7.2 R2 responsibilities

R2 stores larger immutable/versioned objects:

```text
transcripts/{content_id}/{language}/{version}/transcript.json
transcripts/{content_id}/{language}/{version}/transcript.txt
transcripts/{content_id}/{language}/{version}/segments.json
translations/{content_id}/{language}/{version}/...
chapters/{content_id}/{version}/...
exports/{user_id}/...
```

Do not store full transcript text in D1 rows unless a deliberately small preview is needed.

### 7.3 Local client storage

Android phone remains local-first:

- SQLite remains the immediate UI source for subscriptions/items/progress.
- cloud sync writes into local state asynchronously.
- user actions must not block on network unless the operation inherently requires server processing.

Wear OS remains optimized for small local state and Data Layer continuity, with account cloud fallback when appropriate.

---

## 8. Proposed D1 data model

Names are illustrative; migrations may adapt to existing tables.

### 8.1 Devices

```sql
CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  platform TEXT NOT NULL,
  display_name TEXT,
  app_version TEXT,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  revoked_at INTEGER
);
```

### 8.2 Account subscriptions

```sql
CREATE TABLE user_subscriptions (
  user_id TEXT NOT NULL,
  subscription_id TEXT NOT NULL,
  feed_url TEXT NOT NULL,
  title TEXT,
  category TEXT,
  enabled INTEGER NOT NULL,
  send_to_watch INTEGER NOT NULL,
  watch_keywords_json TEXT,
  revision INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  PRIMARY KEY (user_id, subscription_id)
);
```

### 8.3 Read/saved state

```sql
CREATE TABLE user_item_states (
  user_id TEXT NOT NULL,
  item_id TEXT NOT NULL,
  is_read INTEGER NOT NULL,
  is_saved INTEGER NOT NULL,
  read_changed_at INTEGER,
  saved_changed_at INTEGER,
  revision INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, item_id)
);
```

### 8.4 Playback progress

```sql
CREATE TABLE user_playback_progress (
  user_id TEXT NOT NULL,
  content_id TEXT NOT NULL,
  episode_local_id TEXT,
  position_ms INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL,
  playback_speed REAL NOT NULL,
  completed INTEGER NOT NULL,
  source_device_id TEXT,
  revision INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, content_id)
);
```

Use `content_id` when resolved. Keep an episode-local fallback/alias during migration for episodes not yet resolved to a global content row.

### 8.5 Global contents

```sql
CREATE TABLE podcast_contents (
  id TEXT PRIMARY KEY,
  content_code TEXT NOT NULL UNIQUE,
  media_type TEXT NOT NULL,
  canonical_title TEXT,
  canonical_published_at INTEGER,
  canonical_duration_ms INTEGER,
  share_policy TEXT NOT NULL,
  status TEXT NOT NULL,
  preferred_transcript_artifact_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
```

### 8.6 Content aliases

```sql
CREATE TABLE content_aliases (
  alias_type TEXT NOT NULL,
  alias_value_hash TEXT NOT NULL,
  content_id TEXT NOT NULL,
  confidence TEXT NOT NULL,
  verified INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (alias_type, alias_value_hash)
);
```

Do not store sensitive signed/private URLs in plaintext solely for deduplication. Normalize and hash where possible.

### 8.7 Media fingerprints

```sql
CREATE TABLE content_fingerprints (
  content_id TEXT NOT NULL,
  fingerprint_type TEXT NOT NULL,
  fingerprint_value TEXT NOT NULL,
  verified_at INTEGER NOT NULL,
  PRIMARY KEY (fingerprint_type, fingerprint_value)
);
```

Initial fingerprint type:

```text
audio_sha256
```

Future optional type:

```text
acoustic_fingerprint_v1
```

### 8.8 Transcript artifacts

```sql
CREATE TABLE transcript_artifacts (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL,
  language TEXT NOT NULL,
  artifact_version INTEGER NOT NULL,
  provider TEXT,
  model TEXT,
  status TEXT NOT NULL,
  object_key_json TEXT,
  object_key_text TEXT,
  object_key_segments TEXT,
  transcript_hash TEXT,
  word_count INTEGER,
  segment_count INTEGER,
  quality_score REAL,
  share_policy TEXT NOT NULL,
  created_by_user_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE (content_id, language, artifact_version)
);
```

### 8.9 Per-user access grant

```sql
CREATE TABLE user_artifact_access (
  user_id TEXT NOT NULL,
  artifact_id TEXT NOT NULL,
  content_id TEXT NOT NULL,
  access_source TEXT NOT NULL,
  first_accessed_at INTEGER NOT NULL,
  last_accessed_at INTEGER NOT NULL,
  quota_units_charged REAL NOT NULL,
  PRIMARY KEY (user_id, artifact_id)
);
```

This table is what makes repeated opens cost `0x`.

### 8.10 Background jobs

```sql
CREATE TABLE artifact_jobs (
  id TEXT PRIMARY KEY,
  dedupe_key TEXT NOT NULL UNIQUE,
  user_id TEXT NOT NULL,
  content_id TEXT NOT NULL,
  artifact_type TEXT NOT NULL,
  language TEXT NOT NULL,
  requested_version INTEGER,
  status TEXT NOT NULL,
  lease_owner TEXT,
  lease_expires_at INTEGER,
  attempt_count INTEGER NOT NULL,
  error_code TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
```

### 8.11 Credit ledger

Reuse the existing membership/quota service where possible, but add durable accounting semantics sufficient to prove a charge was applied exactly once.

Conceptual ledger:

```sql
CREATE TABLE credit_transactions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  operation_type TEXT NOT NULL,
  reference_id TEXT NOT NULL,
  units REAL NOT NULL,
  multiplier REAL NOT NULL,
  status TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  committed_at INTEGER,
  UNIQUE (user_id, operation_type, reference_id)
);
```

States:

```text
RESERVED
COMMITTED
RELEASED
```

---

## 9. Transcript artifact format

Canonical R2 JSON should be versioned and timestamp-aware.

Example:

```json
{
  "schemaVersion": 1,
  "contentCode": "WBEP-7Q2M-4H9D-K8XR",
  "language": "en",
  "durationMs": 6821000,
  "provider": "managed-provider",
  "model": "model-name",
  "segments": [
    {
      "startMs": 0,
      "endMs": 5430,
      "text": "..."
    }
  ]
}
```

Required characteristics:

- deterministic schema version
- millisecond timestamps where available
- normalized Unicode text
- no provider secrets
- no user account PII in global reusable artifacts
- content hash for integrity/debugging

---

## 10. Transcript request flow

### 10.1 API-level decision tree

```text
Client requests transcript
        ↓
authenticate user
        ↓
resolve episode → content_id / Content Code
        ↓
check content share policy
        ↓
check user's existing artifact access
        │
   ┌────┴────┐
   │ exists  │ no
   ▼         ▼
 return    find reusable READY artifact
 0x          │
        ┌────┴────┐
        │ HIT     │ MISS
        ▼         ▼
     charge      reserve full quota
      0.2x         ↓
        │       single-flight job
        │          ↓
        │       transcribe
        │          ↓
        │       validate/store
        │          ↓
        │       commit 1.0x
        └──────┬───┘
               ↓
        create user access grant
               ↓
             return
```

### 10.2 Existing access: `0x`

If `user_artifact_access(user_id, artifact_id)` already exists:

- return the artifact;
- update `last_accessed_at` asynchronously;
- charge no transcript quota.

### 10.3 Global reusable cache hit: `0.2x`

If no user grant exists, but a `READY`, allowed, reusable artifact exists:

1. compute normal request units (for example duration-based units);
2. apply server-owned `CACHED_TRANSCRIPT_MULTIPLIER = 0.2`;
3. reserve/commit the discounted amount exactly once;
4. create the access grant;
5. return artifact metadata/content.

### 10.4 Cache miss: `1.0x`

On a miss:

1. calculate normal units;
2. reserve `1.0x` units;
3. create or join a single-flight artifact job;
4. process audio;
5. only commit the initiator's full charge after a successful durable artifact is available;
6. release reservation on terminal failure where no billable provider result should count.

### 10.5 Users arriving while generation is in progress

Do not launch duplicate transcription jobs.

```text
User A → starts job → eventual 1.0x
User B → sees PROCESSING → joins/waits
User C → sees PROCESSING → joins/waits
```

After success:

```text
User A → artifact grant, 1.0x
User B → artifact grant, 0.2x
User C → artifact grant, 0.2x
```

User B/C should not be charged a full generation rate simply because they arrived before the first job completed.

### 10.6 Failure behavior

On job failure:

- mark job `FAILED` with stable public error code;
- release uncommitted quota reservations;
- keep retry/backoff metadata;
- do not create reusable `READY` artifact access;
- allow safe retry according to policy.

---

## 11. Single-flight and idempotency

The system must guarantee one logical generation job per artifact target.

Recommended dedupe key:

```text
sha256(content_id + artifact_type + language + target_version)
```

Enforce with D1 `UNIQUE(dedupe_key)`.

For long-running ownership, use either:

- Cloudflare Durable Object keyed by dedupe key, or
- a D1 lease (`lease_owner`, `lease_expires_at`) plus Cloudflare Queue retries.

The first implementation may use D1 leases if that keeps deployment simpler.

All worker steps MUST be idempotent:

- R2 writes use deterministic versioned keys.
- D1 finalize operation checks current job/artifact state.
- quota commit is unique by reference ID.
- retries must not double-charge or create duplicate grants.

---

## 12. Managed transcription and client uploads

### 12.1 Preferred path: managed server job

For the shared public pool, the safest path is:

```text
Gateway/Worker owns job
→ known provider
→ known source episode
→ validated artifact
→ eligible for PUBLIC_REUSE
```

### 12.2 Client-generated transcript upload

If WristBrief later allows on-device/BYOK/client-side transcription, the client MUST NOT be able to mark arbitrary text as globally reusable.

Uploaded artifacts should begin as:

```text
ACCOUNT_ONLY + UNVERIFIED
```

Promotion to global reuse requires server checks, such as:

- user is authorized for the source
- source is eligible for public reuse
- content identity matches
- transcript passes schema/integrity checks
- optional similarity/quality checks
- abuse limits

This prevents poisoning the global transcript pool.

---

## 13. Transcript versions and quality upgrades

Never overwrite the only transcript in place.

```text
content_id
  ├── en / v1
  ├── en / v2
  └── zh / v1 (if direct transcript/translation artifact is supported)
```

`podcast_contents.preferred_transcript_artifact_id` points to the recommended version.

Reasons for a new version:

- improved transcription model
- corrected timestamps
- better diarization
- quality remediation
- publisher source replacement

Existing grants can continue to resolve to a compatible preferred version according to product policy without losing the original audit trail.

Automatic platform-driven upgrades should normally not charge a user again. Explicit user-requested regeneration can use normal quota policy.

---

## 14. Derivative artifact cache

The same artifact registry should be reusable beyond transcripts.

```text
Audio
  ↓
Transcript
  ├── Summary
  ├── Chapters
  ├── Translation
  ├── Embeddings
  └── future TTS/export artifacts
```

Each derivative should have its own:

- artifact type
- input artifact/version
- language/options
- cache key
- share policy
- quota policy

Do not hard-code the transcript cache into a structure that cannot later cache summaries or chapters.

---

## 15. Account sync protocol

### 15.1 Local-first rule

The client writes locally first for ordinary state changes:

```text
user action
   ↓
local SQLite
   ↓
local UI updates immediately
   ↓
outbox/delta push
   ↓
cloud
```

Incoming remote changes are then merged into local storage.

### 15.2 Sync entities

Initial account sync scope:

1. subscriptions
2. read state
3. saved state
4. playback progress
5. account-level transcript/artifact grants/metadata

Do not sync the entire RSS article body through the account API. Clients can refetch public feeds and keep their own bounded caches.

### 15.3 Delta sync instead of full snapshots

Recommended endpoints:

```text
POST /v1/sync/push
GET  /v1/sync/pull?cursor=...
```

The server returns a monotonic/opaque cursor.

Example push:

```json
{
  "deviceId": "device_...",
  "baseCursor": "cursor_...",
  "mutations": [
    {
      "idempotencyKey": "...",
      "entity": "playback_progress",
      "entityId": "content_...",
      "updatedAt": 1789000000000,
      "payload": {
        "positionMs": 1934000,
        "durationMs": 6821000,
        "playbackSpeed": 1.25,
        "completed": false
      }
    }
  ]
}
```

### 15.4 Outbox

Phone should maintain a persistent sync outbox so offline changes survive process death.

Conceptual local table:

```text
sync_outbox
- id
- entity_type
- entity_id
- payload
- created_at
- retry_count
- next_attempt_at
```

Delete an outbox record only after server acknowledgement.

### 15.5 Pull cursor

Each device stores its last acknowledged cursor.

On reinstall/new device:

```text
cursor = none
→ initial account snapshot/delta bootstrap
→ write local SQLite
→ save returned cursor
```

---

## 16. Conflict resolution

Conflict rules must be entity-specific.

### 16.1 Read/saved flags

The existing Phone ↔ Wear timestamped field merge is a useful base model.

Server account sync should preserve independent clocks for read and saved flags so that one field does not clobber the other.

Tie-breaking order should be deterministic:

1. higher logical/server revision
2. newer validated changed timestamp
3. stable device/origin tie-breaker

### 16.2 Playback progress

Blind “largest position wins forever” is wrong because users may intentionally restart an episode.

Recommended payload includes:

```text
position_ms
duration_ms
completed
playback_speed
updated_at
source_device_id
playback_session_id
progress_generation
```

Rules:

- newer playback generation/session normally wins;
- explicit restart creates a new generation;
- within the same generation, newer trusted update wins;
- `completed=true` should not be undone by stale pre-completion updates;
- a deliberate replay after completion starts a new generation and may clear completed.

### 16.3 Subscription deletion

Use tombstones (`deleted_at`) rather than immediate hard delete so another offline device cannot resurrect a removed subscription from stale state.

---

## 17. Playback sync cadence

Do not upload progress every second.

Recommended behavior:

### Local checkpoint

Persist locally approximately every `5–15s` while playing, plus important lifecycle events.

### Cloud checkpoint

Push approximately every `15–30s` while actively playing, plus immediate/near-immediate push on:

- pause
- seek
- episode change
- playback completed
- app/background lifecycle transition
- service stop

The exact interval should be configurable and battery/network aware.

### Wear considerations

Phone ↔ Wear Data Layer can remain the fastest nearby bridge, but cloud state should become the account-level canonical recovery path.

A paired-device update should eventually converge with cloud state rather than creating a separate unsynchronized universe.

---

## 18. Recommended API surface

Names may change, but capabilities should exist.

### Content identity

```text
POST /v1/content/resolve
GET  /v1/content/{contentCode}
```

### Transcript

```text
POST /v1/transcripts/request
GET  /v1/transcripts/{contentCode}
GET  /v1/transcripts/{contentCode}/status
```

### Account sync

```text
POST /v1/sync/push
GET  /v1/sync/pull?cursor=...
```

### Artifact access

```text
GET /v1/artifacts/{artifactId}
```

R2 objects should normally be served through authenticated gateway responses or short-lived signed access, not permanent public bucket URLs.

---

## 19. `POST /v1/transcripts/request` contract

Example request:

```json
{
  "episode": {
    "feedUrl": "https://example.com/feed.xml",
    "guid": "episode-123",
    "audioUrl": "https://cdn.example.com/episode.mp3",
    "title": "Episode 123",
    "publishedAt": "2026-09-12T00:00:00Z",
    "durationMs": 6821000
  },
  "language": "auto"
}
```

Example cache hit response:

```json
{
  "status": "ready",
  "contentCode": "WBEP-7Q2M-4H9D-K8XR",
  "artifactId": "art_...",
  "source": "shared_cache",
  "quota": {
    "normalUnits": 60,
    "multiplier": 0.2,
    "chargedUnits": 12
  }
}
```

Example existing user grant:

```json
{
  "status": "ready",
  "contentCode": "WBEP-7Q2M-4H9D-K8XR",
  "artifactId": "art_...",
  "source": "existing_access",
  "quota": {
    "normalUnits": 60,
    "multiplier": 0,
    "chargedUnits": 0
  }
}
```

Example processing response:

```json
{
  "status": "processing",
  "contentCode": "WBEP-7Q2M-4H9D-K8XR",
  "jobId": "job_..."
}
```

Never expose provider credentials, internal R2 bucket credentials, or other users' identities.

---

## 20. Quota calculation

Use an abstract unit model rather than “one request = one credit.”

Example duration-based policy:

```text
normal_units = ceil(audio_duration_minutes)
```

Then:

```text
full generation = normal_units × 1.0
shared cache hit = normal_units × 0.2
existing grant   = normal_units × 0.0
```

Store the actual multiplier and committed units in the ledger for auditability.

Configuration belongs server-side:

```text
TRANSCRIPT_GENERATION_MULTIPLIER = 1.0
TRANSCRIPT_SHARED_CACHE_MULTIPLIER = 0.2
TRANSCRIPT_EXISTING_ACCESS_MULTIPLIER = 0.0
```

The public product language can say “cached shared transcript costs 1/5 of normal transcript quota,” while the service keeps the value configurable.

---

## 21. Security requirements

### 21.1 Authentication

All account sync and user artifact access requires a valid WristBrief session.

### 21.2 Authorization

Every account-scoped query MUST constrain by authenticated `user_id` server-side.

Never trust user IDs from request JSON.

### 21.3 Device identity

Devices get revocable IDs tied to a user session/account. Device IDs are not authentication credentials by themselves.

### 21.4 R2 access

Do not expose writable R2 credentials to clients.

### 21.5 SSRF/media fetch protection

Transcription workers that fetch enclosure URLs must defend against SSRF:

- HTTPS by default
- block loopback/link-local/private-network destinations
- cap redirects
- cap maximum bytes/duration according to product limits
- enforce timeouts
- validate media content type/signature where practical

### 21.6 Logging

Do not log:

- bearer tokens
- private feed credentials
- full transcript bodies
- signed source URLs with secrets

Use IDs/hashes/request IDs in structured logs.

### 21.7 Abuse resistance

Rate-limit:

- content resolve requests
- transcript requests
- client uploads
- polling/status endpoints

Global cache promotion must be server controlled.

---

## 22. Privacy and deletion semantics

Account deletion should remove or anonymize account-scoped state according to product policy:

- device records
- subscriptions
- playback progress
- private artifacts
- user artifact access grants

A globally reusable public artifact should not necessarily be deleted just because one contributing user deletes their account, **provided the artifact is legally/policy eligible for independent retention**. Its `created_by_user_id` should be nullable/anonymizable.

Private/account-only artifacts must remain tied to the owning account and deletion policy.

---

## 23. Offline behavior

### Sync state

When offline:

- reading/saving/playback continues locally;
- mutations enter local outbox;
- UI clearly distinguishes server-required operations from local actions;
- sync retries with exponential backoff + jitter.

### Transcript access

If an already-downloaded transcript is cached locally, the user can read it offline without quota effects.

If the artifact is known to the account but not present locally, fetching it requires network.

---

## 24. Local transcript cache

Phone should add local tables/indexes for artifact metadata and optionally cached transcript files.

Conceptual local state:

```text
artifact_metadata
- artifact_id
- content_id/content_code
- type
- language
- version
- status
- local_path
- last_accessed_at
- size_bytes
```

Large transcript bodies can live as app-private files rather than bloating the SQLite row.

Cache eviction must never revoke server-side access. If local files are evicted, they can be downloaded again at `0x` because `user_artifact_access` remains.

---

## 25. Migration from current WristBrief state

Implement incrementally; do not replace working local behavior in one rewrite.

### Phase 0 — documentation and invariants

- land this architecture
- mark current README claims accurately where implementation lags
- define stable API/versioning conventions

### Phase 1 — account sync foundation

Gateway:

- D1 migrations for devices, subscriptions, item state, playback progress, sync cursor/change tracking
- `/v1/sync/push`
- `/v1/sync/pull`
- idempotency and tombstones

Phone:

- persistent outbox
- device registration
- initial bootstrap/pull
- local merge

Acceptance target: Phone A ↔ Phone B subscriptions/read/saved converge.

### Phase 2 — playback cloud sync

- map local episode IDs to `content_id` where available
- sync playback progress
- playback generation/session conflict semantics
- tie Wear Data Layer state into canonical progress model

Acceptance target: Phone → Watch → replacement phone resume works without manually seeking.

### Phase 3 — Content Code registry

- `podcast_contents`
- `content_aliases`
- `content_fingerprints`
- `/v1/content/resolve`
- stable public Content Code generation
- merge/alias support

Acceptance target: the same known episode resolves to the same Content Code across supported sources when identity evidence is sufficient.

### Phase 4 — transcript persistence

- transcript artifact schema
- R2 storage
- managed transcription worker
- Queues job pipeline
- status endpoint
- durable account access grants

Acceptance target: one user can generate a transcript, reinstall, sign in, and retrieve it without retranscribing.

### Phase 5 — shared transcript cache and 1/5 quota

- PUBLIC_REUSE policy checks
- cache-hit path
- `0.2x` quota ledger
- `0x` existing grant path
- single-flight handling for concurrent users

Acceptance target: two users requesting the same eligible episode cause one full generation; later users receive the artifact at 20% normal quota.

### Phase 6 — derivative artifacts

- summary/chapter/translation artifact caching
- Ask AI consumes transcript artifact instead of retranscribing audio
- optional embedding/index pipeline

### Phase 7 — production hardening

- admin/takedown path
- abuse limits
- metrics/alerts
- retention policy
- staged rollout flags
- load/concurrency tests

---

## 26. Suggested code/module boundaries

### Gateway

Potential modules:

```text
gateway/src/sync/
  syncRoutes.ts
  syncService.ts
  syncStore.ts

gateway/src/content/
  contentResolver.ts
  contentStore.ts
  contentCode.ts

gateway/src/artifacts/
  artifactStore.ts
  transcriptRoutes.ts
  transcriptService.ts
  artifactJobs.ts
  quotaLedger.ts

gateway/src/workers/
  transcriptionWorker.ts
```

Keep route parsing, business logic, and persistence adapters separable for testing.

### Mobile

Potential modules:

```text
mobile/.../sync/
  CloudSyncApi.kt
  SyncCoordinator.kt
  SyncOutboxStore.kt
  SyncMerge.kt

mobile/.../artifacts/
  TranscriptApi.kt
  TranscriptRepository.kt
  TranscriptCache.kt
  TranscriptUiState.kt
```

Do not wire network calls directly into Compose destinations.

### Wear

Keep Data Layer transport separate from merge rules so the same canonical state can be applied from nearby phone or cloud/account recovery paths.

---

## 27. Tests required before claiming completion

### 27.1 Unit tests

Content identity:

- same verified SHA resolves to same canonical content
- different low-confidence metadata does not silently merge
- merged Content Code alias remains valid

Quota:

- miss commits `1.0x`
- shared hit commits `0.2x`
- existing grant commits `0x`
- retry cannot double-charge
- failed job releases reservation

Artifact jobs:

- concurrent identical requests create one logical job
- expired lease can be safely recovered
- successful retry is idempotent

Sync merge:

- read/saved independent conflict handling
- subscription tombstone prevents stale resurrection
- playback restart creates a newer generation
- completed episode ignores stale progress

### 27.2 Gateway integration tests

- auth required for sync/artifact endpoints
- user cannot read another user's private state
- R2 artifact metadata/content mismatch is rejected
- private source cannot enter PUBLIC_REUSE path
- request size and SSRF limits work

### 27.3 Android integration tests

- offline mutation persists in outbox through process death
- reconnect flushes outbox and advances cursor
- new-device bootstrap restores account state
- local transcript cache eviction does not lose account grant

### 27.4 Multi-device end-to-end tests

Minimum scenarios:

1. Phone A saves article → Phone B shows saved.
2. Phone A plays episode to N minutes → Watch resumes near N.
3. Watch advances playback → Phone A converges.
4. Replacement Phone C signs in → retrieves subscriptions/progress.
5. User A generates public transcript → artifact reaches READY.
6. User B requests same eligible content → no second transcription job, charge is 20%.
7. User B opens same transcript again → charge is 0.
8. Private feed transcript created by User A → User B cannot discover/reuse it.

---

## 28. Observability

Structured metrics should include at least:

```text
sync_push_success / failure
sync_pull_success / failure
sync_conflict_count
playback_progress_updates
content_resolve_hit_alias
content_resolve_new
content_dedup_merge
transcript_cache_hit
transcript_cache_miss
transcript_job_started
transcript_job_success
transcript_job_failure
transcript_singleflight_join
quota_reserved
quota_committed
quota_released
quota_multiplier_1_0
quota_multiplier_0_2
quota_multiplier_0_0
```

Important ratios:

```text
shared transcript cache hit rate
provider transcription minutes avoided
average artifact generation latency
sync convergence latency
failed/retried job rate
quota reconciliation mismatch count
```

No metric should contain transcript text or private source credentials.

---

## 29. Feature flags and rollout

Recommended flags:

```text
ACCOUNT_CLOUD_SYNC_ENABLED
PLAYBACK_CLOUD_SYNC_ENABLED
CONTENT_CODE_ENABLED
TRANSCRIPT_PERSISTENCE_ENABLED
SHARED_TRANSCRIPT_CACHE_ENABLED
SHARED_TRANSCRIPT_CACHE_MULTIPLIER
```

Rollout order:

1. internal accounts
2. test track
3. small percentage production
4. monitor sync/quota mismatch metrics
5. expand gradually

The shared-cache multiplier should be remotely configurable without requiring an APK release.

---

## 30. Release acceptance criteria

Do not call the feature “multi-device sync” complete until all of these are true:

- [ ] signed-in second phone can restore subscriptions
- [ ] read and saved states converge across account devices
- [ ] playback progress converges across phone/watch/account devices
- [ ] offline mutations survive process death and later sync
- [ ] conflict behavior is deterministic and tested
- [ ] private content never enters shared global artifacts
- [ ] public eligible transcript persists durably in R2
- [ ] Content Code remains stable through alias/merge operations
- [ ] concurrent identical transcript requests do not duplicate provider jobs
- [ ] first generation charges normal quota exactly once
- [ ] shared cache first access charges exactly `0.2x`
- [ ] repeat access by the same user charges `0x`
- [ ] failed jobs cannot leave committed quota without the defined billable outcome
- [ ] new-device/reinstall artifact access is recoverable
- [ ] admin can disable/takedown a reusable artifact
- [ ] CI contains unit/integration coverage for sync, dedup, quota, and privacy boundaries
- [ ] production D1/R2/Queue configuration is verified

---

## 31. Recommended implementation priority

The most useful order for WristBrief is:

```text
1. Account cloud sync foundation
2. Playback progress cloud sync
3. Phone ↔ Wear canonical progress convergence
4. Content Code/global content registry
5. Durable transcript artifact storage
6. Shared transcript reuse + 1/5 quota
7. Ask AI / Summary / Chapters reuse transcript artifacts
8. Translation/TTS/export derivative artifacts
9. Production hardening and rights/takedown tooling
```

This order reuses the existing account, D1, gateway, local SQLite, and Data Layer work instead of replacing them.

---

## 32. Final target architecture

```text
                         ┌──────────────────────────────┐
                         │       WristBrief Cloud       │
                         │                              │
                         │  Worker / API Gateway        │
                         │      │                       │
                         │      ├── Account Sync        │
                         │      ├── Content Resolver    │
                         │      ├── Artifact Service    │
                         │      └── Quota Service       │
                         │                              │
                         │  D1              R2          │
                         │  ├ users         ├ transcript│
                         │  ├ devices       ├ segments  │
                         │  ├ sync state    ├ chapters  │
                         │  ├ progress      └ derivatives│
                         │  ├ contents                  │
                         │  ├ aliases                   │
                         │  ├ artifacts                 │
                         │  └ quota ledger              │
                         │                              │
                         │  Queue / single-flight jobs  │
                         └──────────────┬───────────────┘
                                        │
                ┌───────────────────────┼───────────────────────┐
                │                       │                       │
                ▼                       ▼                       ▼
         Android Phone A         Wear OS Watch           Android Phone B
         local SQLite            local state             local SQLite
         local outbox            Data Layer              local outbox
                │                       │                       │
                └────────────── eventual convergence ──────────┘

Public podcast episode
        ↓
Content Resolver
        ↓
Stable Content Code
        ↓
Reusable Transcript Artifact
        ↓
 ┌───────────────┬──────────────────────┐
 │ User A        │ User B               │ same user reopen
 │ generates     │ shared cache hit     │ existing grant
 │ 1.0x quota    │ 0.2x quota           │ 0x quota
 └───────────────┴──────────────────────┘
```

The important invariant is simple: **user state is account-scoped, reusable public content is content-scoped, and quota is charged from durable server-side facts rather than client claims.**
