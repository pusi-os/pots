# Media Studio

**Last verified:** 2026-11-14

Media Studio is the in-app creative surface for generating and editing images, video and audio with the currently active cloud media provider (PlusVibeAPI first). The main chat top bar switches to it: the media icon opens the studio, and while the studio is open the same slot shows a chat icon that returns to chat. The studio takes over the chat area. While it is open, the chat input is hidden: the studio has its own single prompt field in the dock. Speech output, if on, announces “генерация готова” in Russian when a job finishes.

## Concepts

### Media provider

A cloud service exposing a model catalog with prices, an async generation queue, and signed result URLs. The provider adapter owns its HTTP contract (catalog, price quote, submit, poll, download). Pots ships PlusVibeAPI first; the adapter interface accepts further providers without changing the studio, the settings UI, or the agent tools.

### Media instance

The service instance that bills generation. It is the first configured instance that has an API key; if none has a key, the first configured instance is used and the studio reports the missing key with a shortcut to settings.

### Media job

One async generation task. The client submits the model id, prompt, options and references, receives a `jobId`, polls the provider until a terminal status (`success` / `fail`), and downloads the result bytes immediately. Providers keep results only a few days (PlusVibe: 5 days). Paid generation is charged once on `success`; `fail` is free.

Each job has a local `localId` (UUID) that is also the `Idempotency-Key` of its first submit. The journal persists every job to `media/history.json` in app storage. On restart:

* an entry still in `Sending` without a `jobId` becomes `Failed`;
* an entry with a `jobId` is resumed by polling the same job. If the provider no longer knows the job, the entry becomes `Failed` with “Провайдер не знает эту задачу”.

A submit that timed out is never resent with a new key. The client reconciles it by polling the same `jobId`. A failed submit with no `jobId` is retried with the same key, the saved `quoteId` and `maxPriceRub`, so the body matches.

### Generation journal

A top-bar button (with a badge for active jobs) opens a sheet listing every generation, newest first, with the local time. From it the user can:

* open an entry in the lightbox or place it on the canvas;
* save a finished result to the phone gallery;
* regenerate an entry (a finished or charged job gets a new key);
* remove an entry from the list. Removing an entry does not cancel the provider job, and the user is warned.

Queued and running entries show a progress bar, the model, the provider and the price. Errors are shown in Russian.

### Audio mode

The third generation mode, next to image and video. The mode switch is a three-button segment; audio uses a music-note icon with no label. Audio mode has two flows: **Озвучить текст** (TTS) and **Создать музыку** (music and sound effects). The first tap on the audio segment turns the mode on with the last remembered flow; a repeat tap while audio is active opens a two-option dropdown. The flow choice is stored globally under `media_audio_flow`, while the per-flow model selection is per instance (`instance_<id>_media_audio_tts_model_id` / `instance_<id>_media_audio_music_model_id`).

The model sheet is filtered by the active flow: the TTS flow lists models of class `tts`, the music flow lists `music` and `sfx`; `suno-replace-section` appears only when the journal already contains a `suno` job (it needs one). Each row shows the price together with its billing unit (“за 1000 символов”, “за трек”, “за секунду”, “за файл”), and price-range models show a tier badge built from the parsed `tiers` catalog field. STT/analytical models (input `audio_url`) and transformation models never appear in the sheet. When no audio model is saved, the research defaults apply: `gpt-4o-mini-tts` for the TTS flow and `suno:white` for the music flow.

TTS parameters ride the existing axis-chip mechanism: voice and speed axes map to chips (“Голос”, “Скорость”, plus technical aliases like `gemini_voice`), every other visible axis to a generic chip, at most four in the top bar; each chip opens the value sheet, and the chosen values land in the job `opts` under the axis name (booleans become `"true"`/`"false"`, numbers stay strings). Models without axes get no chips.

A flow keeps its own prompt draft: drafts are keyed by mode plus flow in `MediaSettings` and survive mode/flow switches and generation errors; entering a flow restores its draft. The prompt field expands into a near-full-height editor (line breaks kept, live `{len}/{limit}` counter from the model's documented character limit, the text is never truncated). When the selected model's limit is exceeded, the counter turns to the error color and create is blocked; switching to a model with a smaller limit under an over-long text only offers trimming via snackbar action. The prompt-enhance button works for both flows through the active chat model.

Metered models (“за 1000 символов”, “за секунду”) and `mirelo-sfx-2` are quoted via `POST /api/media/price` before submit; fixed prices submit directly without the extra request.

Audio jobs go through the same submit/poll/download contract and the same queue and journal (`media/history.json`) as image and video; the journal entry carries an `isAudio` flag and the result is downloaded to bytes immediately (signed URLs live 5 days). Retry reuses the same `Idempotency-Key`, so a repeated submit never charges twice.

A finished audio result lands on the canvas as an audio element with a full in-app player: a real waveform (peaks decoded from the audio bytes; a decorative pattern stands in where decoding is unavailable), play/pause, ±10 s, tap-to-seek, position/duration, and — in the lightbox and the editor — playback-speed presets (0.5× / 0.75× / 1× / 1.25× / 1.5× / 2×), a volume slider with mute and looping. Desktop plays MP3 (JLayer) and WAV with all extended controls; Android uses the system media player (speed/volume/looping included); iOS and web show a static card (playback not wired yet). Editing is the audio editor below.

### Audio editor

Entry: the «Редактировать» chip in the selected audio element's toolbar. The editor opens full-screen with the real waveform, tap-to-seek and a drag-to-select range with two edge handles. Three operations, each non-destructive — the source element is never modified, the result is always a NEW audio element on the canvas plus a WAV file under `media/edits/` in app storage:

* **Обрезка** — «Обрезать до выделения» renders the selected range.
* **Скорость** — a 0.25–4× slider with 0.5 / 1 / 1.5 / 2 presets; speed and pitch change together (tape behavior — honest about what linear resampling does; pitch-preserving time-stretch is a later version).
* **Склейка** — «Добавить трек» picks other canvas audio elements; the ordered list (move up/down, remove) joins top-to-bottom into one file. Mixed formats are converted to the first track's channel count and sample rate.

Rendering runs in the background with a progress bar and a cancel button («Отмена» stops the operation; the source stays intact). Failures show the §8 error card (title, human detail, rescue action) inside the sheet. Decoding is the only platform-dependent step: desktop understands MP3 and WAV, every other target decodes WAV in pure common code and refuses MP3 with «Формат не поддерживается» (playback of MP3 still works where the platform player exists).

Audio processing is PCM sample math in pure Kotlin `commonMain` (trim = slice, speed = linear-interpolation resample, concat = copy), not a local `ffmpeg`. Reasons: (1) no new dependency — ffmpeg would mean shipping a ~100 MB binary per OS/arch just for three operations that fit in deterministic sample loops; (2) the pure logic is unit-tested in CI without any binary; (3) the output is always lossless WAV, so no MP3 encoder is needed (the spec's MP3 export chip waits for a later version together with time-stretch).

### Drawing (pencil)

Entry: the brush icon in the studio top capsule draws on an empty canvas — a fixed transparent 1024×1024 sketch sheet shown over a checkerboard so the transparency is visible; the «Карандаш» chip in a selected image element's toolbar draws on top of that image (annotation). Both chips appear only on image elements — video elements have no pixel data to draw on or edit. The drawing sheet is full-screen: a palette of colors, a brush width slider, undo of the last stroke, «Очистить» to wipe all strokes, and «Применить» to bake. Strokes are a separate layer — the source image is never touched until the user applies. Applying flattens strokes onto the base pixels and exports one PNG: on an image element the result keeps the element's size; an empty-canvas sketch is baked transparent and trimmed to the drawn bounding box (nothing drawn — apply is blocked). The result lands as a NEW canvas element via `media/edits/<uuid>.png`, so it can immediately be used as a reference for the next generation (dock strip, `*_base64`, `mode=image-to-image`) — the intended use is a visual hint for a generation prompt. Esc closes the sheet (asking first when strokes are not applied yet), Ctrl+Z undoes.

### Image editor

Entry: the «Редактировать» chip in a selected image element's toolbar. The editor opens full-screen with four operations, each non-destructive — the source element is never modified, the result is always a NEW canvas element plus a PNG file under `media/edits/` in app storage:

* **Обрезка** — an interactive frame with draggable corners, drag-to-move and a rule-of-thirds grid over the fitted preview; aspect chips (Свободно, 1:1, 4:3, 3:4, 16:9, 9:16) snap the frame, «Обрезать» renders the selected region.
* **Размер** — a width × height form (pixels, both required), a lock that keeps the aspect ratio, and 512 / 1024 / 2K quick presets (each sets the longer side; the other side follows the aspect ratio).
* **Наложение** — overlay another canvas image element or a file from disk on top: drag to position, sliders for scale (relative to the base) and opacity (0–100 %), straight-alpha over blending.
* **Корректировки** — brightness (−100…+100 %) and contrast (0…400 %, 100 % = unchanged) sliders, 90° turns in both directions and horizontal/vertical flips, composed into one render.

Rendering runs in the background with a progress bar and a cancel button («Отмена» stops the operation; the source stays intact). Failures show the §8 error card (title, human detail, rescue action) with a retry that repeats the last operation. The pipeline is pure pixel math in `commonMain` (`PixelGrid`, straight-alpha ARGB `0xAARRGGBB`, row-major): crop = slice, resize = bilinear resample, rotate/flip = index remap, adjustments = per-channel clamp math, overlay = premultiplied-free over blending with clipping. Decoding and PNG encoding are the only platform-dependent steps (`ImageCodec` expect/actuals): desktop uses ImageIO (direct `getRGB`, no premultiplying intermediate), Android uses `BitmapFactory`/`Bitmap.compress`, iOS and web use Skia (`Image.makeFromEncoded` + `readPixels(RGBA_8888, UNPREMUL)`). The output is always lossless PNG, so no encoder dependency was added.

### Video editor

Entry: the «Редактировать» chip in a selected video element's toolbar. Video editing is local desktop processing and ships desktop-only — other targets compile but never show the chip (the same honesty as local TTS). The editor opens full-screen with three operations, each non-destructive — the source element is never modified, the result is always a NEW video element on the canvas plus an MP4 file under `media/edits/` in app storage:

* **Обрезка** — interactive start/end handles over a filmstrip of real frames grabbed from the clip (the desktop canvas has no embedded player, so the filmstrip is the preview); excluded tails are dimmed, the handles keep a minimum gap, and «Обрезать» re-encodes the selected range frame-accurately.
* **Скорость** — a 0.25–4× slider with 0.5 / 1 / 1.5 / 2 presets and the computed new duration; video is retimed with `setpts`, audio with cascaded `atempo`, and pitch follows speed (tape behavior — the same honesty as the audio editor). Speed 1× is blocked as pointless.
* **Аудиодорожка** — «Убрать звук» mutes the clip, «Заменить дорожку» swaps the soundtrack for a canvas audio element or a picked file (mp3/wav/m4a/ogg/opus/flac/aac). Both copy the video stream bit-for-bit, so they finish in seconds; a short replacement track is padded with silence and a long one is cut at the video end — the picture never changes length.

Rendering runs with a progress bar and a cancel button («Отмена» destroys the ffmpeg process; the source stays intact). Failures show the §8 error card (title, human detail, rescue action) with a retry that repeats the last operation.

Video tooling decision: the **system `ffmpeg`/`ffprobe` binaries** driven through `ProcessBuilder`, not a library wrapper. Reasons: (1) no new dependency — wrapper libraries bundle 100–300 MB of native codecs per OS/arch, while the binary is already present on the Linux desktop this app targets; (2) runtime detection — the sheet probes the binaries on open and, when missing, shows the §8 card with per-OS install commands and a «Проверить снова» re-probe; (3) the command building, trim/speed timecode math and `-progress` parsing are pure `commonMain` code unit-tested without any binary, plus desktop integration tests run against the real ffmpeg on a synthetic lavfi clip (skipped cleanly when the binary is absent). Future timeline export and audio-clip assembly tasks reuse this same processor contract.

### Local TTS (offline)

The TTS flow ends with a «Локально · 0 ₽» group that never touches a provider. Local synthesis is honest about platforms: only Android can synthesize into a FILE, so the group exists there and only there — the system engine (`TextToSpeech.synthesizeToFile`) and the bundled Piper voice «Ирина» (sherpa-onnx, the same engine Settings → Озвучивание uses; no new dependency). Desktop, iOS and web native engines only play speech and expose no audio data, so those targets show no group at all — no disabled rows, no “unavailable” notes, no fake local models.

Synthesis writes a WAV into app storage; the result then rides the regular flow: an audio element on the canvas and one journal entry with the friendly engine name and the «локально» mark instead of a provider, price 0 ₽. No API key, no billing, no polling — the entry is persisted once, in its terminal state. Speed rides the standard chip (both engines accept a rate; pitch is not supported by either). Local engines are never auto-picked as the default model and never hide cloud models; repeating a local journal entry synthesizes offline again.

### Voice cloning «Мой голос»

Cloud-only voice cloning through the provider voices API; the local TTS path never sees cloned voices. Models that accept a cloned voice (`voice-clone-tts`, `elevenlabs-v4-tts`, `tts`) render their free-text `voice_id` axis as a «Мой голос» chip: the sheet lists the model's built-in voices, the account's cloned voices with a mic mark and their status (ready / processing / error), and a «+ Мой голос» entry that opens the creation sheet. Only ready voices can be selected; the chosen id lands in the generation `opts` under the axis key.

Creation validates before upload: wav or mp3, at most 10 МБ, duration 3–60 s (Standard) or 10–60 s (ElevenLabs, Premium) — WAV duration is parsed exactly, MP3 estimated from the first frame. The sheet offers a listen-before-send player, a voice name, the level with its price note (only levels whose creation engine is documented by the provider are creatable — currently ElevenLabs), and a mandatory consent checkbox without which the create button stays disabled. `POST /api/media/voices` sends `name`, `consent: true`, `engine` and `audio_base64` as a `data:<mime>;base64,…` string with an `Idempotency-Key`, so a retry never creates a duplicate voice; the list (`GET`) refreshes on every sheet open, and deletion (`DELETE`) asks for confirmation with the voice name. Errors map to Russian cards: rejected sample, sample-count limit (25 recordings / 300 s), daily addition limit (20), engine refusal, missing voice, key and balance problems.

### Image quality and video parameters

The image/video toolbar is driven by the model's catalog axes (the generalized «axis → chip» mechanism, in chip order resolution → quality → duration → sound, at most four chips).

Image quality gets one selector: a chip indicator right after the resolution chip opens a dropdown of the model's quality levels with Russian labels. It renders only for models whose `quality` axis has at least two real levels — degenerate one-option axes and models whose `quality` axis holds resolution values (`pixverse`, `runway`) have no selector; the latter render that axis as the resolution chip instead. The default is the lowest level: the cheapest option by catalog price, ties or missing prices fall back to the first option (so `gpt-image-2-6` defaults to Low although the catalog default is `high`, and `flux-3` video quality defaults to draft). The choice is stored per model in `MediaSettings`, applies to generation and editing alike, and is always sent explicitly in `opts.quality`; a stale stored value that no longer exists in the axis silently rolls back to the default.

Dropdown prices come from the public `GET /api/media/price-table?model=<id>`, cached per model: exact lines only for rows matching the current (price-affecting axes) combination — resolution, quality and generation/editing mode. A combination without a table row shows no price and no note; when the table is unavailable, flat equal catalog prices render with a «Качество не влияет на цену» note and anything else shows the conservative «Среднее ≈ ×2, высокое ≈ ×3» note without numbers. Tables whose rows carry `chargeBasis=quote` are quoted via `POST /api/media/price` before submit, exactly like metered audio.

Video mode adds a «Со звуком / Без звука» toggle chip (VolumeUp/VolumeOff icons) for models with an `audio` or `generate_audio` axis; the toggle rides the catalog default until flipped. Models without the corresponding axes show no controls. Switching model or mode drops parameter selections the new model does not accept.

### Canvas element

A placed image, video or audio item on the studio canvas. Image and video elements are scaled, rotated and moved independently, with their own gestures. Elements can be stacked (bring forward / send back), duplicated, deleted, saved to the device gallery, or used as a reference for the next generation (image/video only). A tap selects an element and shows its toolbar. Audio elements skip transform and reference actions; their toolbar keeps layers, duplicate, edit (opens the audio editor), open player, save and delete. Image elements additionally get «Карандаш» (opens the drawing sheet on top of the element) and «Редактировать» (opens the image editor). Video elements get «Редактировать» (opens the video editor: trim, speed, audio track) on desktop only. The canvas pans and zooms with a two-finger gesture (or the matching trackpad gesture on desktop).

### Canvas element toolbar

The toolbar appears over the selected element in icon rows. The first row holds the quick transform actions. For image elements the second row holds «Карандаш», «Редактировать», layers and duplicate; for video elements it holds «Редактировать» (desktop only, where ffmpeg runs), layers and duplicate. The last row holds use as reference, save to gallery and delete. Audio elements render rows without the transform/drawing/edit-image actions. The old text menu was removed, so the toolbar never overlaps another menu.

### References

The dock shows a strip of reference thumbnails for the next generation. References come from three places: the file picker in the strip, the “Как референс” action on a canvas element, or the agent. A reference is either a public `https://` URL, which the provider downloads itself, or app-storage bytes, which are sent inline as `data:<mime>;base64,…` in the matching `*_base64` field. Inline bytes are capped at 8 MB; larger files are refused with a Russian message asking the user to compress them, and the journal entry is removed so that a compressed retry does not create a duplicate. Models that do not accept an image reference refuse the request before submit.

When references are attached and the model declares a `mode` parameter, the request sends `mode = "image-to-image"`. Without references the mode is left at the model's default.

### Agent photo edits

When the user attaches a photo in chat and asks the agent to improve it or place the person somewhere, the agent calls `generate_image` with `reference_image="attachment"`. The tool takes the last image attached to a user message in the active chat. The tool does not switch models. If the selected model cannot take a reference, the tool returns a clear error that names the model, so the user can pick a suitable one.

## Behaviour

* Async generation with provider-recommended polling (PlusVibe: 3 s, doubling to 30 s), no duplicate submit on `submitting`, and transient poll errors retried with backoff.
* HTTP timeouts for the provider client: connect 30 s, request and socket 180 s. The default OkHttp timeout is too short for generation submits.
* Result URLs are downloaded to bytes immediately and cached under the app storage; the canvas keeps the remote URL as a fallback until the download completes.
* Top bar (main chat bar, shown in both modes): the media icon opens the studio; in the studio the same slot shows a chat icon that returns to chat.
* Studio top capsule: recenter canvas, import a file, draw (brush icon, opens the drawing sheet on an empty canvas), pick a model, and the generation journal. The sparkle icon recenters the canvas. It is not prompt enhancement.
* Studio second row: the image/video/audio switch (three icon buttons; audio is a music note), the current model chip (opens the model sheet), and, in image mode only, the aspect-ratio chip. The chip opens a sheet with a rectangle preview for each ratio and its name. Image/video models then show axis-driven chips: resolution, the quality dropdown (default Low), duration and the sound toggle — each only when the model declares the axis.
* Audio mode has a remembered flow (TTS / music): the first tap on the audio segment restores it, a repeat tap on the active segment opens the flow dropdown; the flow, its model and its draft persist separately from the other flow.
* Audio mode swaps the resolution/aspect chips for the selected audio model's parameter chips from the catalog axes; switching the audio model or flow resets the chosen values.
* Dock: one prompt field (draft kept separately per mode, and per audio flow inside audio mode), the prompt-enhance button (small, icon only, left of the send button), a round create button with a progress indicator while a job runs, a reference strip, and the duration and resolution chips when the model declares them. Aspect ratio is not in the dock. The audio dock adds an expand button that opens the expanded prompt editor with line breaks, a `{len}/{limit}` counter and the same enhance/create actions.
* Prompt enhance shows progress while it runs, blocks repeat presses until the first request finishes, and uses the active chat model.
* Generation lock: while a submit is starting or any journal entry is active, the create button is disabled.
* Model sheet: image, video and audio models from the provider catalog, each with a price badge; audio rows are filtered by the active flow (TTS / music+SFX) and also show the billing unit and the tier badge for price-range models. The selected row uses the theme’s container colors, so its text stays readable. When no model is saved, the first catalog model of the mode is picked and persisted (audio uses the research defaults per flow). The selected model is never changed by the app during generation.
* Canvas import accepts image, video and audio files; an imported audio file lands on the canvas as a playable audio element.
* Errors appear as a snackbar with a Russian message. A missing key offers a “Настройки” action.
* Every catalog load, quote, submit, poll, download and reference mapping is logged under the `PotsMedia` tag (logcat on Android, stdout on desktop).
* Chat history and canvas stay independent: generating in the studio does not write into the conversation.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../media/MediaModels.kt` | Media kinds, catalog items, references, job requests and results |
| `composeApp/src/commonMain/.../media/MediaProvider.kt` | Provider interface (catalog, price table, quote, submit, poll, download) |
| `composeApp/src/commonMain/.../media/PlusVibeMediaProvider.kt` | PlusVibeAPI adapter; body building, `mode` injection, reference mapping, `HttpTimeout` |
| `composeApp/src/commonMain/.../media/MediaGenerationRepository.kt` | Async queue, submit reconcile, polling with backoff, dedup, quote reuse, download-and-cache, resume |
| `composeApp/src/commonMain/.../media/MediaHistory.kt` | Journal model and `MediaHistoryStore` (`media/history.json`) |
| `composeApp/src/commonMain/.../media/AudioModels.kt` | Generative audio filter, flows (TTS / music), flow filtering, price label with unit, tier parsing, voice/speed axis aliases, quote policy, toolbar chips, clone-voice axis |
| `composeApp/src/commonMain/.../media/LocalTts.kt` | Local TTS honesty contract, synthetic catalog entries; actuals: `androidMain` (system `synthesizeToFile` + Piper), empty catalogs on `desktopMain`/`iosMain`/`wasmJsMain` |
| `composeApp/src/commonMain/.../media/WavAudio.kt` | Pure common WAV codec, duration parsing and 16-bit PCM decoding (local synthesis output, voice-sample pre-check, MP3 CBR estimate, editor/waveform input) |
| `composeApp/src/commonMain/.../media/AudioEdit.kt` | Audio editing core: `AudioPcm`, trim/speed/concat as pure cancellable sample math, §8 editor errors; `decodeAudioToPcm` actuals — `desktopMain` (WAV + MP3 via JLayer), `androidMain`/`iosMain`/`wasmJsMain` (WAV only) |
| `composeApp/src/commonMain/.../media/ImageEdit.kt` | Image editing core: `PixelGrid` (straight-alpha ARGB), crop/resize/rotate/flip/adjust/overlay/bake as pure cancellable pixel math, drawing strokes layer, §8 editor errors |
| `composeApp/src/commonMain/.../media/ImageCodec.kt` | `decodeImageToGrid` / `encodeGridToPng` expect/actuals — `desktopMain` (ImageIO), `androidMain` (BitmapFactory/Bitmap), `iosMain`/`wasmJsMain` (Skia decode + PNG encode) |
| `composeApp/src/commonMain/.../media/VideoEdit.kt` | Video editing core: ffmpeg command building, trim/speed timecode math, `-progress` parsing, §8 editor errors; `VideoProcessor` contract with actuals — `desktopMain` (system ffmpeg/ffprobe via `ProcessBuilder`), unsupported stubs on `androidMain`/`iosMain`/`wasmJsMain` |
| `composeApp/src/commonMain/.../ui/media/MediaVideoEditorSheet.kt` | Full-screen video editor: filmstrip trim handles, speed slider with presets, audio track remove/replace (canvas element or file), progress, cancel and §8 error cards |
| `composeApp/src/commonMain/.../media/MediaVoices.kt` | Voice cloning domain: levels, pre-upload sample validation, voices payload parsing, create body, §8 error mapping |
| `composeApp/src/commonMain/.../media/MediaQualityParams.kt` | Axis→chip mechanism: quality selector visibility, the Low default, audio toggle axes, explicit opt defaults |
| `composeApp/src/commonMain/.../media/MediaPriceTables.kt` | Price-table parsing and quality price rules (exact lines, flat/conservative notes, quote basis) |
| `composeApp/src/commonMain/.../media/MediaSettings.kt` | Per-instance image/video/audio model selection, audio flow, per-flow models, prompt drafts and the per-model quality selection, media instance resolution |
| `composeApp/src/commonMain/.../media/MediaLog.kt` | `PotsMedia` logger; platform actuals under `androidMain`, `desktopMain`, `iosMain`, `wasmJsMain` |
| `composeApp/src/commonMain/.../media/PromptEnhancer.kt` | Prompt cleanup used by the `enhance_prompt` tool and the enhance button |
| `composeApp/src/commonMain/.../tools/MediaTools.kt` | `generate_image` (with `reference_image`), `generate_video`, `list_media_models`, `enhance_prompt` agent tools |
| `composeApp/src/commonMain/.../ui/media/MediaStudioScreenFull.kt` | Studio composer: canvas, dock, snackbars, sheets, lightbox |
| `composeApp/src/commonMain/.../ui/media/MediaStudioTopBar.kt` | Studio top capsule (recenter, import, models, journal) and second row (three-mode switch, model, ratio/param chips) |
| `composeApp/src/commonMain/.../ui/media/MediaCanvas.kt` | Dotted canvas, independent element gestures, two-row element toolbar, skeletons, empty state |
| `composeApp/src/commonMain/.../ui/media/MediaDock.kt` | Prompt field, enhance/expand and create buttons, reference strip, parameter chips, flow dropdown on the active audio segment |
| `composeApp/src/commonMain/.../ui/media/MediaPromptSheet.kt` | Expanded near-full-height prompt editor: line breaks, `{len}/{limit}` counter, enhance, create |
| `composeApp/src/commonMain/.../ui/media/MediaAudioPlayer.kt` | Audio element player: real waveform with decorative fallback, play/pause, ±10 s, seek, speed presets, volume with mute, loop |
| `composeApp/src/commonMain/.../ui/media/MediaAudioEngine.kt` | Audio playback contract incl. extended transport (speed/volume/loop); actuals in `desktopMain` (MP3 via JLayer, WAV), `androidMain` (MediaPlayer), `iosMain`/`wasmJsMain` (unsupported) |
| `composeApp/src/commonMain/.../ui/media/MediaAudioEditorSheet.kt` | Full-screen audio editor: waveform selection with handles, trim/speed/concat with progress and cancel, §8 error cards, non-destructive WAV results |
| `composeApp/src/commonMain/.../ui/media/MediaDrawSheet.kt` | Full-screen drawing sheet: palette, width, undo/clear, strokes as a separate layer, bake to PNG element (empty-canvas sketch or annotation over an image) |
| `composeApp/src/commonMain/.../ui/media/MediaImageEditorSheet.kt` | Full-screen image editor: crop frame, resize form, overlay (element or file, drag/scale/opacity), brightness/contrast/rotate/flip with progress, cancel and §8 error cards |
| `composeApp/src/commonMain/.../ui/media/MediaHistorySheet.kt` | Generation journal sheet: open, save to gallery, regenerate, remove |
| `composeApp/src/commonMain/.../ui/media/MediaAspectRatioSheet.kt` | Aspect-ratio sheet with rectangle previews |
| `composeApp/src/commonMain/.../ui/media/MediaModelSheet.kt` | Model picker sheet with price badges; local group with friendly engine names |
| `composeApp/src/commonMain/.../ui/media/MediaVoiceSheet.kt` | «Мой голос» picker: built-in voices, cloned voices with status and mic mark, delete confirmation |
| `composeApp/src/commonMain/.../ui/media/MediaVoiceCloneSheet.kt` | Voice creation sheet: file picker, pre-upload validation, preview player, levels, consent |
| `composeApp/src/commonMain/.../ui/media/MediaStudioViewModel.kt` | Studio state: jobs, elements, references, model selection, generation lock, typed errors |
| `composeApp/src/commonMain/.../ui/settings/MediaModelsSettings.kt` | Media model picker in Settings → Services → Media |
| `composeApp/src/commonMain/.../ui/chat/composables/TopBar.kt` | Main top bar: media icon opens the studio, chat icon returns from it |
| `composeApp/src/commonMain/.../ui/chat/ChatScreen.kt` | `isMediaOpen` state and the top bar wiring |
| `docs/knowledge/media-providers/` | Provider contracts and refresh playbook |

See also: [multi-service.md](multi-service.md) (chat services, API keys), [tools.md](tools.md) (agent tool registry), [hands-free.md](hands-free.md) (voice loop), [chat.md](chat.md) (inline media in chat).
