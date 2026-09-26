# 07 — Phone UX review and optimisation pass

Date: 2026-09-27. Scope: the Android phone client (`mobile/`). Wear (`app/`) is covered only
where it intersects the phone.

## Method and honest limits

- **Static review of source plus JVM unit tests. There is NO emulator or device in this
  environment, so no visual, layout, gesture, screen-reader or light/dark pass was
  performed.** Everything below is derived from reading Composables, resources, models and
  tests, and from `./gradlew :mobile:testDebugUnitTest`/`assembleDebug`. Any claim about
  how a screen *looks* would be invented.
- Reviewed: `ArticleDetailDestination.kt`, `media/PodcastPlayerUi.kt`,
  `artifacts/TranscriptViewerDestination.kt`, `artifacts/*`, `audio/*`, `MainActivity.kt`,
  `db/*`, `ui/**`, `values/strings.xml`, `values-zh-rCN/strings.xml`, and the Wear
  transcript surface.
- Review order mandated by `docs/PRODUCT_DESIGN_CONSTRAINTS.md`: truthful state first,
  then navigation/recovery/accessibility, then visual hierarchy, then performance.

## Findings

Severity: **P0** = the UI states something false about data or capability; **P1** = the
user cannot complete or recover the task; **P2** = accessibility / correctness of layout;
**P3** = polish.

| # | Sev | Finding | Evidence | Status |
| --- | --- | --- | --- | --- |
| 1 | P0 | Transcript "word-level" promise: onboarding claimed "Word-level podcast transcripts with audio seek" / "支持逐词播客转写与音频定位", but segments are 4-minute clips with no word timestamps and no diarization | `values/strings.xml` `oobe_feature_transcripts` (pre-fix line 45); `docs/AUDIO_PIPELINE.md:18` | Fixed |
| 2 | P0 | The viewer never explained what a timestamp localizes; a tap implied precise word seeking | `TranscriptViewerDestination.kt` pre-fix: segment row seek with no explanation | Fixed |
| 3 | P0 | Transcript `Processing` collapsed `queued` and `running` into one indistinguishable spinner | `HttpTranscriptGatewayApi.kt` pre-fix `parseJobStatusResponse`; `TranscriptViewerDestination.kt` pre-fix single `transcript_loading` state | Fixed |
| 4 | P0 | A long transcript job lived only in composition state: leaving the page or restarting the app lost the job id, so the transcript was unreachable | `MainActivity.kt` pre-fix `activeTranscriptRequest`/`activeTranscriptState` were `remember`-only | Fixed |
| 5 | P0 | `podcast_lossless_badge` "LOSSLESS 24-BIT" / "24位无损" was rendered on artwork in two screens; the client cannot observe losslessness and the pipeline transcodes to 16 kHz mono MP3 | pre-fix `ArticleDetailDestination.kt:712`, `PodcastPlayerUi.kt:333`, `docs/AUDIO_PIPELINE.md:18` | Fixed |
| 6 | P0 | `podcast_audio_format` "128kbps AAC" was rendered as episode metadata the client cannot know | pre-fix `ArticleDetailDestination.kt:754` | Fixed |
| 7 | P0 | "Live Key Insights" dock presented two hard-coded sentences as that episode's insights, plus a "Syncing" pill claiming an unobservable background capability. Bullets: *"Edge Silicon Optimization: Speculative token offloading drops packet dependency and delivers microsecond response times."* and *"Local Vector Retrieval: Quantized embeddings enable fast similarity queries with minimal memory footprint."* | pre-fix `PodcastPlayerUi.kt:549-642`, keys `podcast_live_insights`, `podcast_live_syncing` | Fixed |
| 8 | P0 | `podcast_ai_brief_read_time` "2 min read" was a constant badge shown for every episode regardless of the brief's real length | pre-fix `ArticleDetailDestination.kt:895` | Fixed (computed) |
| 9 | P0 | `podcast_chapter_indicator` was rendered as `stringResource(..., 1, 4)` — "Chapter 1 of 4" for every episode. No chapter model exists | pre-fix `PodcastPlayerUi.kt:419` | Fixed |
| 10 | P0 | "Save highlight" toasted "Highlight saved" although nothing is persisted anywhere | pre-fix `PodcastPlayerUi.kt:593`, `podcast_highlight_saved` | Fixed |
| 11 | P0 | Four dead resources asserted capabilities with no implementation: `today_spoken_brief` ("Spoken Audio Brief"), `today_spoken_brief_sub` ("Natural neural voiceover · 4 min 12 sec" — an invented duration), `today_synced_footnote` ("Just now"), `today_engine_version` ("Editorial Engine v2.4") | pre-fix `values/strings.xml:93-98` | Fixed (deleted) |
| 12 | P1 | A failed transcript offered no way to recover, and the raw server string was shown | pre-fix `TranscriptViewerDestination.kt:88-101` | Fixed |
| 13 | P1 | No transcript state was announced to assistive tech; states differed by text only | pre-fix `TranscriptViewerDestination.kt` | Fixed |
| 14 | P1 | TTS: the existing `POST /v1/audio/speech` endpoint was unreachable from the UI, so a real capability was invisible | pre-fix: no client existed | Fixed (short text only) |
| 15 | P2 | A preview that failed mid-playback left the play control showing "Play" while audio was actually loaded and playing | `SpeechPreviewController` — playability was derived from player callbacks, and a `Released` event nulled the cached audio | Fixed |
| 15b | P2 | Transcript polling could resume for a job that had already settled terminally as not-found/non-retryable, because `refreshJob` had no terminal guard | `TranscriptJobService.refreshJob` / `startPolling` | Fixed |
| 15c | P2 | A 404 job probe classified as TERMINAL (so `retryable=false` was correct) but never reached the "job gone" state, because the raw body code (`job_not_found`) was returned while the state mapping compared a canonical constant — and a bare 401 surfaced as the mechanical `HTTP_401` instead of `UNAUTHORIZED` | `TranscriptGatewayErrors.normalize`, `TranscriptResponseParser.parseErrorResponse` | Fixed |
| 16 | P2 | TTS input text was held in `remember`, lost on rotation | `SpeechPreviewCard.kt` | Fixed (`rememberSaveable`) |
| 17 | P2 | The over-limit message was laid out beside the character counter, where it cannot wrap on a narrow screen | `SpeechPreviewCard.kt` | Fixed (own line) |
| 18 | P2 | Wear/phone transcript consistency | Wear has **no** transcript affordance: `PodcastTranscript` exists only as parsed feed metadata (`FeedParser.kt:107-118`, `FeedRepository.kt:8`, `FeedStoreCodec.kt:59-62,96-99`) and is never read by a Wear composable | **No change needed** |
| 19 | P3 | Dead `transcript_loading`, `podcast_live_insights`, `podcast_live_syncing`, `podcast_chapter_indicator`, `podcast_summarize_chapter`, `podcast_highlight_saved`, `transcript_quota_cached`, `transcript_quota_free` keys | both string files | Fixed (deleted) |
| 20 | P3 | Stale KDoc on the expanded player still advertised the removed lossless badge and insights dock | pre-fix `PodcastPlayerUi.kt:184` | Fixed |

### Note on findings 5–11 and 19

These are **pre-existing defects found during this pass, not regressions introduced here.**
They share one root cause: a literal string was written to express a design intent and then
rendered as if it were observed data. `docs/PRODUCT_DESIGN_CONSTRAINTS.md` already names
this class ("article reading time was hard-coded", "do not substitute a ... fake version,
read-time, episode duration, or progress"); the review found seven more instances.

## What changed, and why it is truthful

### Transcript lifecycle (findings 2, 3, 4, 12, 13)

- New durable table `transcript_jobs` (mobile SQLite migration, `DATABASE_VERSION` 4 → 5) via
  `SqliteTranscriptJobStore`. Stored: audio URL, job id, content code, server status, the
  request fields the server needs, error code, retryability, timestamps. **The Bearer token
  is never persisted.** Rows are bounded (25 retained, oldest pruned) and jobs older than 7
  days are dropped on resume.
- `TranscriptJobService` owns the job. Re-arm runs at **app start**
  (`MainActivity.kt` `LaunchedEffect(transcriptJobService) { transcriptJobService.resumePending() }`
  inside `WristBriefMobileApp`), not when the transcript screen opens. Closing the viewer
  does not cancel anything.
- Five genuinely distinct, localized states: `Checking` (a persisted job whose server status
  has not been observed yet — never a guessed queued/processing), `Queued`, `Processing`,
  `Failed`, `Completed`. `queued` and `running` are separate server words and separate
  labels in both languages. Progress is indeterminate only; no percentage is invented.
- Terminal handling: `completed` fetches content via
  `GET /v1/transcripts/{contentCode}`; `failed` is terminal with the server's `errorCode`;
  `HTTP_404`/`410`/`not_found`/`not_yours` on a job probe is a terminal "gone / not yours",
  polled exactly once and never retried. Retry is offered **only** when
  `TranscriptGatewayErrors.isRetryable` says the failure was transient (transport, 5xx, 408,
  429), and retry re-POSTs the request so the server can reuse the job it knows.
- Accessibility: each state panel declares `stateDescription` + `liveRegion = Polite`;
  status text is always present, so no state depends on colour; the retry and segment
  controls are ≥48 dp `defaultMinSize`; each segment row announces
  `Seek to <start> – <end>`.
- Positioning granularity has one source of truth, `TranscriptGranularity`
  (`SUPPORTS_WORD_LEVEL = false`, `SUPPORTS_SPEAKER_DIARIZATION = false`,
  `CLIP_DURATION_MS`), so a future affordance cannot be added without confronting what the
  pipeline actually produces. Tests assert both flags stay false and that a clip-length
  segment is recognised as clip level.
- The viewer explains its own granularity: *"Segments are timed to the audio clips the
  server creates (4 minutes each), so a timestamp marks where a passage starts — not each
  spoken word. Nothing here highlights or seeks a single word."* /
  *"分段按服务器切出的音频片段计时（每段 4 分钟），因此时间戳只标记一段话的起点，而不是每个词。此处不会高亮或跳转到单个词。"*
  A test now pins that sentence as required content, so a future edit cannot quietly drop
  the honest explanation.

### Short-text speech preview (findings 14, 15, 16, 17)

#### Finding 15 in detail: transient player lifecycle must not mean "content unavailable"

This one is instructive because the symptom looked like a test problem and was a real
user-visible bug.

Before:

```kotlin
private var audio: ByteArray? = null

private fun derive() {
    val playable = audio != null && playback.isPlayable && playback.issue == null
    _state.value = SpeechPreviewUiState(isPlaying = playable && ..., canPlay = playable, ...)
}

fun onPlayerEvent(event: SpeechPlayerEvent) {
    playback = SpeechPlaybackReducer.reduce(playback, event, ::playbackIssue)
    if (playback.issue != null) audio = null      // <-- also cleared on a clean Released
    derive()
}
```

`Media3SpeechPreviewPlayer.stop()` emits `Released` (as does `clearMediaItems()`), and
`onPlayerEvent` treated that transient lifecycle event as "the sample is gone", clearing
`audio`. `derive()` then computed `canPlay = (audio != null && ...) = false` while
`isGenerating = false` and `issue = null`. The user-visible result: generation succeeds, and
the control shows "Play" and does nothing, because `togglePlay()` also gated on
`!playback.isPlayable`.

After:

```kotlin
/**
 * True once a preview sample has been generated and handed to the player. This is
 * deliberately NOT learned from a player callback ...
 */
private var sampleReady = false

private fun derive() {
    val playable = sampleReady && audio != null && playback.issue == null
    _state.value = SpeechPreviewUiState(isGenerating = generating, canPlay = playable, ...)
}

fun onPlayerEvent(event: SpeechPlayerEvent) {
    playback = SpeechPlaybackReducer.reduce(playback, event, ::playbackIssue)
    if (playback.issue != null) { audio = null; sampleReady = false }   // only a real error
    derive()
}
```

The rule this records: **content availability is owned by the generation path, and playback
intent is owned by the control path.** A transient player lifecycle event (`Released`,
buffering, a cleared playlist, or a `Started`/`Paused` that arrives before the listener is
attached) must never be read as "the content became unavailable" *or* as "playback did not
happen".

The same bug class appeared a second time, in the opposite direction. With content
availability fixed, the UI correctly reported `canPlay = true, canStop = true` — and
`isPlaying = false`, because that field was derived from the player's reported state
(`playback.playback == PLAYING`) rather than from the control path. The observable
consequence was identical in kind: the user pressed play, audio was playing, and the button
said "Play" (so a following tap was read as play, not pause). The fix is a `playIntent`
latch set by the control path (`startPlayback`, the play branch of `togglePlay`, cleared by
pause/stop/end/error) that the player's `Started`/`Paused`/`Ended` events may correct but
are never the sole source of:

```kotlin
private var playIntent = false    // owned by the control path

fun onPlayerEvent(event: SpeechPlayerEvent) {
    playback = SpeechPlaybackReducer.reduce(playback, event, ::playbackIssue)
    when (event) {
        SpeechPlayerEvent.Started -> playIntent = true
        SpeechPlayerEvent.Paused, SpeechPlayerEvent.Ended -> playIntent = false
        is SpeechPlayerEvent.Failed -> { playIntent = false; audio = null; sampleReady = false }
        else -> Unit               // Released et al. must not flip the control state
    }
    derive()
}
```

The four tests that caught this assert `canPlay`, `isPlaying`, `canStop` and the always-on
AI-voice disclosure after a successful generation, and none of them were weakened. The
general lesson for this codebase is recorded above rather than in a test name: a Compose
control that mirrors an asynchronous external device needs its own intent state, because the
device's event stream is neither complete nor ordered.

#### Terminal jobs must not be polled again (finding 15b)
`refreshJob()` is reachable from the viewer's retry callback for a non-retryable failure, and
it started a poll loop unconditionally, so a job the server had already 404'd could be probed
forever. `startPolling()` now returns early for a `COMPLETED` job and for a job whose recorded
error is non-retryable, and `refreshJob()` checks the same before publishing. Combined with
the 25-row retention cap and the 7-day prune, a never-settling job cannot grow the table or
the request rate.

#### Finding 15c: a correct discriminator with a wrong state mapping

Worth recording because the symptom was actively misleading. A 404 job probe was classified
`TERMINAL`, so the UI correctly refused to offer RETRY — but it rendered as a generic
*failure* rather than as "this job is gone", and the code it carried was the raw body string
`job_not_found` while the state mapping compared a canonical `NOT_FOUND`. Two layers each
looked right in isolation and the join between them was wrong:

```kotlin
// before: the raw body code was returned verbatim for every status except 404
val code = if (responseCode == 404) bodyCode ?: NOT_FOUND else "HTTP_$responseCode"
// so: "job_not_found" was never canonical, and a bare 401 surfaced as "HTTP_401"
```

The fix is to make canonicalisation total for the known vocabulary
(`job_not_found`/`job_gone`/`not_found`/`not_yours`/`HTTP_404`/`HTTP_410` -> `NOT_FOUND`;
`unauthorized`/`unauthenticated`/`forbidden`/`HTTP_401`/`HTTP_403` -> `UNAUTHORIZED`) and to
assert the canonical code explicitly in tests, so a regression is named by the layer that
broke rather than surfacing as a state mismatch three layers away. The general rule for this
codebase: **when a classification and a state mapping are separate functions, canonicalise
at the boundary and assert on the canonical value.**

- `HttpSpeechPreviewApi` calls the existing `POST /v1/audio/speech`, bounded at 2000
  characters **computed on the trimmed text** because the server trims first; over-limit
  input is refused with a message and is never truncated and never sent. Downloads are
  capped at 8 MiB.
- `SpeechPreviewController` + `Media3SpeechPreviewPlayer` reuse the app's Media3 stack
  (ExoPlayer with `USAGE_MEDIA`/`CONTENT_TYPE_SPEECH` and audio-focus handling), pausing
  podcast playback first so only one player holds focus; leaving the article stops the
  preview. Generated MP3 lives in `cacheDir` and stale files are cleared on launch.
- States: loading, playing, paused, ended, and eight distinct error reasons (network,
  unauthorised, quota, too long, invalid text, provider unavailable, generation failed,
  endpoint absent). Retry re-requests generation instead of replaying the failure.
- The AI-voice disclosure is rendered above the input, before anything can be played, in
  both languages. Long-form narration is stated as unavailable on the same card
  (`docs/next/06-longform-tts-design.md` is the design work, not the feature).

### Fabricated metrics replaced or removed (findings 5–11)

- Lossless/AAC badges and the chapter indicator: **deleted**, with no substitute value
  invented. Where the episode metadata row previously read `date · 128kbps AAC` it now shows
  only the real feed date.
- `podcast_ai_brief_read_time`: computed from the lines actually shown via
  `ArticleContentSanitizer.readingTimeMinutesFor` (documented 200 wpm Latin / 350 cpm CJK),
  recomputed with `remember(takeaways)`.
- "Live Key Insights" dock: **deleted**, both fabricated bullets gone. The two real
  user-initiated actions that lived there remain (Ask AI about this clip, Save highlight).
- "Save highlight": still no store exists, so it now reports
  `podcast_highlight_store_unavailable` — *"Highlights aren't available yet: this app has
  nowhere to store them."* — instead of a false success toast.

## Optimisation notes

- The transcript viewer uses one `LazyColumn` keyed by segment id; no nested scrolling and
  no per-row recomposition state.
- Polling cadence is 3 s for the first probe then 10 s, and honours a server-provided
  poll-after hint when present (`pollAfterMs`), with no low attempt ceiling — a long job
  keeps polling without hammering the gateway.
- The transcript state is computed once per composition from the job flow plus the cache
  lookup, rather than re-deriving per row.

## Verification performed, and what is still unverified

Performed:

- `./gradlew :mobile:testDebugUnitTest` — JVM tests covering the five states, retryable vs
  terminal classification, persistence + restore of a pending job across a simulated
  restart (including "re-arm polls the persisted job id with zero new requests"), the
  2000-character bound, the documented error-code mapping, the play/pause state machine, and
  en/zh copy parity plus the absence of word-level promises.
- `./gradlew :mobile:assembleDebug`, `:app:testDebugUnitTest`, `:app:assembleDebug`.
- `python scripts/release_guard.py` for string parity in both modules.

**Not performed, and not claimed:** any emulator or device run; screenshots in light/dark;
narrow-screen/large-font layout; TalkBack traversal; gesture and reduced-motion behaviour;
real provider audio. The accessibility work above is source-level (semantics, state
descriptions, live regions, documented touch targets) and is not a substitute for a device
pass. Findings 15–17 in particular were fixed from code reading and unit tests, not from
observing a running app.

## Recommended next steps

1. Run the device pass listed above once an emulator is available; start with the transcript
   states and the speech preview card, which are the newest surfaces.
2. Decide whether "Save highlight" should be implemented (needs a real store and sync
   path) or removed; today it is honest but useless.
3. Audit the remaining `uidocs/`-derived literals in `ArticleDetailDestination.kt` and
   `PodcastPlayerUi.kt` with the same test the copy suite now uses: if a string states a
   fact the client cannot observe, it must be derived or deleted.
