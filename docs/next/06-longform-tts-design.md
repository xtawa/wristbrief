# 06 — Long-form narration (TTS) design note

Status: **not implemented, and not presented as working in the app.** The phone ships a
short-text preview only (`POST /v1/audio/speech`, ≤ 2000 characters, one MP3). The
"Listen to a short sample" card (`mobile/.../audio/SpeechPreviewCard.kt`) states in both
languages that reading a whole article aloud is unavailable and why.

This note records what long-form narration would need so the next person does not have to
rediscover it, and so nobody ships it by wiring a control to the existing endpoint.

## Why the existing endpoint is not enough

`POST /v1/audio/speech` (see `gateway/server/index.ts`, `speechRequest`) is a single
request/response call:

- `{"text": "..."}`, trimmed, non-empty, **at most 2000 characters** (400 `invalid_text`
  otherwise); the raw body is rejected above 8192 bytes with 413 `request_too_large`.
- Requires a mobile Bearer session (401 `unauthorized`) and available managed AI quota
  (429 `managed_ai_quota_unavailable`).
- Reserves **one** managed AI request, releases it on failure, returns `audio/mpeg`
  with `Cache-Control: no-store`.
- 503 `tts_provider_unavailable` when no TTS preset is enabled, 502
  `tts_generation_failed` when synthesis fails, 405 for non-POST.

A 30-minute article is roughly 25k–35k characters, so it is 13–18 chunks. Doing that
synchronously in one request would blow the body limit, hold an HTTP connection for
minutes, and make a partial failure lose everything. It also cannot be resumed after the
app process dies.

## 1. Chunking strategy

Chunk at sentence boundaries, never mid-word, and never by a fixed character count alone:

- Target **1200 characters** per chunk, hard ceiling **1800** (below the server's 2000
  limit so the client never trips `invalid_text`). Clients must chunk on the **trimmed**
  length because the server trims first.
- Split candidates in priority order: paragraph break, sentence end (`。！？.!?` followed by
  whitespace or end of text), clause separator (`；;，,`), then whitespace. Never split
  inside a surrogate pair or a CJK character.
- Keep a small overlap (one trailing sentence, ≤ 200 characters) only if listening
  continuity tests show it is needed; overlap costs quota, so start without it.
- Each chunk is identified by `contentCode + language + voiceId + chunkIndex` so a retry
  regenerates one chunk, not the whole article.
- Generation is sequential per episode (provider rate limits and quota are per-request);
  at most one in-flight chunk per episode, and at most two episodes generating at once on
  a phone.
- A chunk failure is per-chunk: report `N of M ready`, keep finished chunks playable, and
  offer retry for the failed chunk only. Never present the episode as ready.
- Assemble playback with **Media3 concatenation** (`ConcatenatingMediaSource`-equivalent,
  or one `MediaItem` per chunk in a playlist) so seeking and "next chunk" work without a
  second player. Do not write one giant MP3 by concatenating bytes: MP3 frame headers make
  naive concatenation unreliable and break exact seeking.

## 2. Storage and caching of generated audio

- Store per-chunk MP3 files in app-private storage, keyed
  `narration/<contentCode>/<language>/<voiceId>/<chunkIndex>.mp3` with a metadata row for
  `bytes`, `durationMs`, `textHash`, `createdAt`, `lastAccessedAt`, `state`.
- **`textHash`** (SHA-256 of the exact chunk text) is the cache key. If the article text
  changes, the hash changes and the chunk is regenerated; audio is never reused for
  different text.
- Budget: cap the narration cache at **200 MB and 14 days since last access**, evicting
  least-recently-used first, and never evict a chunk belonging to an episode that is
  currently generating or playing. Show the user the real size and a "Clear narration
  audio" action in Settings before enabling anything that fills it.
- Store the manifest durably (SQLite, numbered migration) so playback survives process
  death: `contentCode`, `voiceId`, `chunkCount`, per-chunk state, and the last played
  `chunkIndex + positionMs`. This is the same durability shape as `transcript_jobs`.
- Generated audio is user content derived from paywalled or private feeds: keep it in
  app-private storage, exclude it from backup (`allowBackup` is already `false`), and never
  upload it.

## 3. Independent usage and quota rule

Long-form narration must **not** be metered as "one managed AI request" the way the text
and preview calls are. Chunking multiplies calls by 15–20 per article, which would burn a
managed AI quota in a handful of articles and make the existing 429
`managed_ai_quota_unavailable` path the normal experience.

Proposed rule, to be reviewed with billing before it is implemented:

- A separate `narration_characters` counter with its own monthly allowance
  (sketch: 60,000 characters/month free, 600,000 for the paid tier), metered on the
  **characters actually synthesized**, not characters requested.
- Charge per completed chunk. Release the reservation for chunks that failed or that the
  user cancelled before synthesis started. Never charge for a cache hit.
- Expose remaining narration characters in the UI before generation starts, and in the
  error path when the allowance runs out — a distinct, honest message, not the generic
  managed-AI quota error.
- Rate-limit per account (sketch: 20 chunks/minute) to protect the provider and to keep a
  single account from monopolising a worker.
- Provider cost guard: abort the whole job if the average per-chunk character count
  drifts above the ceiling, rather than silently generating 30 chunks.

## 4. Open questions and risks

1. **Cost ownership.** Who pays for a 20-call article — the narration allowance, the
   managed AI quota, or a separate bucket? Undecided. Shipping before deciding would make
   the existing quota meaningless.
2. **Voice and disclosure.** Every chunk must carry the same AI-voice disclosure the
   preview shows. OpenAI requires the disclosure; it is also an explicit product
   constraint. Which voice id, and does a voice change invalidate the cache? (It must —
   `voiceId` is in the cache key.)
3. **Language routing.** `docs/AUDIO_PIPELINE.md` notes MiMo ASR supports Chinese and
   English; other languages need a suitable provider and voice. There is no defined
   behaviour for an article in a language no enabled preset supports. The honest default
   is to refuse with a specific message rather than synthesize gibberish.
4. **Interruption and lifecycle.** Narration must pause on audio-focus loss, stop on
   navigation away, and resume from the persisted `chunkIndex + positionMs`. The playback
   service currently checkpoints podcast progress by media id; narration needs its own
   progress namespace so it can never appear as "Continue listening" for a podcast.
5. **Offline.** With chunks cached, playback can be offline; generation cannot. The UI must
   distinguish "cached chunks playable offline" from "generation needs a connection".
6. **Provider rate limits and latency.** Sequential generation of 15–20 chunks at, say,
   3–8 s each means 1–3 minutes before the first full episode is ready. Pre-generating the
   first two chunks lets playback start sooner, at the cost of partial-failure bookkeeping.
7. **Storage growth.** 200 MB is a guess based on ~1 MB per minute of MP3. It needs
   measurement against real provider output before the limit is shown to users.
8. **Legal.** Publisher terms may not permit synthesizing an entire paywalled article for
   offline listening. This needs a product/legal answer before implementation, and until
   then the feature must stay unavailable.

## 5. What is safe to ship today

Already shipped and truthful:

- Short-text preview with the 2000-character bound enforced client-side (no silent
  truncation), loading/error/retry states, play/pause/stop, and a mandatory AI-voice
  disclosure.
- Long-form narration presented as unavailable, in both languages, on the same card.

Do not wire a "read this article aloud" control to `POST /v1/audio/speech` alone; that is
exactly the failure mode this note exists to prevent.
