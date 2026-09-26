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

Save the provider key at `/admin/provider-keys`, then enable a preset at `/admin/audio`. Each task chooses the lowest priority number among enabled presets that have a key. Presets can be disabled, reprioritized and edited independently. MiMo ASR supports Chinese and English; use a suitable provider and voice for other languages. Never use the AI text-model form to configure STT or TTS.

## STT pipeline

`POST /v1/transcripts/request` uses the existing authenticated API and reserves transcript credits. The SQLite job records the audio URL and survives restart. A single server worker claims the job and renews a lease. It fetches audio with the existing URL and redirect policy (up to 96 MiB), runs the locally installed FFmpeg/FFprobe, caps input duration at 3 hours, converts to 16 kHz mono MP3 at 32 kbps, and splits into 4-minute clips. Each clip is at most 7 MB before MiMo's Base64 encoding (under its documented 10 MB limit). The selected provider receives each clip; the result is saved as transcript text and timed clip-level segments in the object directory. On failure the job stores an error and releases the reserved credits. Segments represent clips, not word-level timestamps or speaker diarization.

MiMo API: `POST /v1/chat/completions` with `input_audio`. Deepgram API: `POST /v1/listen` with binary MP3. OpenAI API: `POST /v1/audio/transcriptions` with multipart MP3. Source audio and temporary clips are deleted after processing. Temporary files are created with private permissions. The image ships FFmpeg and FFprobe from Debian packages; local installs need these executables on `PATH`.

## TTS pipeline

`POST /v1/audio/speech` takes `{ "text": "..." }`, requires a mobile Bearer session and available managed AI quota, and returns MP3 (`audio/mpeg`). Input is limited to 2000 characters. The server calls MiMo's audio-enabled chat completions, Deepgram's Speak REST API, or OpenAI's speech endpoint, depending on the enabled TTS preset. A failed generation returns a 502 and releases the reserved AI request. OpenAI requires that the product disclose to users that the voice is AI generated. The generated audio is returned directly; no synthesis library has yet been added to the Android UI. Long-form narration needs chunking, storage, and a dedicated usage policy before it is shown as a product feature.

## Deployment and limits

Build the gateway image with `docker build -t wristbrief-gateway gateway`. Mount `/data` as a writable persistent volume and supply the variables described in `gateway/.env.server.example`. Use a TLS reverse proxy; do not publish the Node port directly. The image includes FFmpeg/FFprobe from Debian; account for the included FFmpeg packages and their licenses when distributing images. The `ffmpeg-static` npm package is not used. Neither upstream API is called without a configured key. The code has contract and conversion tests, but no real paid-provider credentials were available for a live call.

Official API references: [MiMo ASR](https://mimo.mi.com/docs/en-US/api/audio/Speech-Recognition), [MiMo TTS](https://mimo.mi.com/docs/en-US/api/audio/tts), [Deepgram prerecorded audio](https://developers.deepgram.com/docs/pre-recorded-audio), [Deepgram TTS](https://developers.deepgram.com/docs/text-to-speech), [OpenAI STT](https://developers.openai.com/api/docs/guides/speech-to-text), [OpenAI TTS](https://developers.openai.com/api/docs/guides/text-to-speech).
