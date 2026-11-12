# Media Studio

**Last verified:** 2026-11-12

Media Studio is the in-app creative surface for generating and editing images and video with the currently active cloud media provider (PlusVibeAPI first). The main chat top bar switches to it: the media icon opens the studio, and while the studio is open the same slot shows a chat icon that returns to chat. The studio takes over the chat area. While it is open, the chat input is hidden: the studio has its own single prompt field in the dock. Speech output, if on, announces “генерация готова” in Russian when a job finishes.

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

### Canvas element

A placed image or video on the studio canvas. Each element is scaled, rotated and moved independently, with its own gestures. Elements can be stacked (bring forward / send back), duplicated, deleted, saved to the device gallery, or used as a reference for the next generation. A tap selects an element and shows its toolbar. The canvas pans and zooms with a two-finger gesture (or the matching trackpad gesture on desktop).

### Canvas element toolbar

The toolbar appears over the selected element in two icon rows. The first row holds the quick transform actions. The second row holds the former context-menu actions as vector icons: bring forward, send back, duplicate, use as reference, save to gallery and delete. The old text menu was removed, so the toolbar never overlaps another menu.

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
* Studio top capsule: recenter canvas, import a file, pick a model, and the generation journal. The sparkle icon recenters the canvas. It is not prompt enhancement.
* Studio second row: the image/video switch, the current model chip (opens the model sheet), and, in image mode, the aspect-ratio chip. The chip opens a sheet with a rectangle preview for each ratio and its name.
* Dock: one prompt field, the prompt-enhance button (small, icon only, left of the send button), a round create button with a progress indicator while a job runs, a reference strip, and the duration and resolution chips when the model declares them. Aspect ratio is not in the dock.
* Prompt enhance shows progress while it runs, blocks repeat presses until the first request finishes, and uses the active chat model.
* Generation lock: while a submit is starting or any journal entry is active, the create button is disabled.
* Model sheet: image and video models from the provider catalog, each with a price badge. The selected row uses the theme’s container colors, so its text stays readable. When no model is saved, the first catalog model of the mode is picked and persisted. The selected model is never changed by the app during generation.
* Errors appear as a snackbar with a Russian message. A missing key offers a “Настройки” action.
* Every catalog load, quote, submit, poll, download and reference mapping is logged under the `PotsMedia` tag (logcat on Android, stdout on desktop).
* Chat history and canvas stay independent: generating in the studio does not write into the conversation.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../media/MediaModels.kt` | Media kinds, catalog items, references, job requests and results |
| `composeApp/src/commonMain/.../media/MediaProvider.kt` | Provider interface (catalog, quote, submit, poll, download) |
| `composeApp/src/commonMain/.../media/PlusVibeMediaProvider.kt` | PlusVibeAPI adapter; body building, `mode` injection, reference mapping, `HttpTimeout` |
| `composeApp/src/commonMain/.../media/MediaGenerationRepository.kt` | Async queue, submit reconcile, polling with backoff, dedup, quote reuse, download-and-cache, resume |
| `composeApp/src/commonMain/.../media/MediaHistory.kt` | Journal model and `MediaHistoryStore` (`media/history.json`) |
| `composeApp/src/commonMain/.../media/MediaSettings.kt` | Per-instance image/video model selection and media instance resolution |
| `composeApp/src/commonMain/.../media/MediaLog.kt` | `PotsMedia` logger; platform actuals under `androidMain`, `desktopMain`, `iosMain`, `wasmJsMain` |
| `composeApp/src/commonMain/.../media/PromptEnhancer.kt` | Prompt cleanup used by the `enhance_prompt` tool and the enhance button |
| `composeApp/src/commonMain/.../tools/MediaTools.kt` | `generate_image` (with `reference_image`), `generate_video`, `list_media_models`, `enhance_prompt` agent tools |
| `composeApp/src/commonMain/.../ui/media/MediaStudioScreenFull.kt` | Studio composer: canvas, dock, snackbars, sheets, lightbox |
| `composeApp/src/commonMain/.../ui/media/MediaStudioTopBar.kt` | Studio top capsule (recenter, import, models, journal) and second row (mode, model, ratio) |
| `composeApp/src/commonMain/.../ui/media/MediaCanvas.kt` | Dotted canvas, independent element gestures, two-row element toolbar, skeletons, empty state |
| `composeApp/src/commonMain/.../ui/media/MediaDock.kt` | Prompt field, enhance and create buttons, reference strip, parameter chips |
| `composeApp/src/commonMain/.../ui/media/MediaHistorySheet.kt` | Generation journal sheet: open, save to gallery, regenerate, remove |
| `composeApp/src/commonMain/.../ui/media/MediaAspectRatioSheet.kt` | Aspect-ratio sheet with rectangle previews |
| `composeApp/src/commonMain/.../ui/media/MediaModelSheet.kt` | Model picker sheet with price badges |
| `composeApp/src/commonMain/.../ui/media/MediaStudioViewModel.kt` | Studio state: jobs, elements, references, model selection, generation lock, typed errors |
| `composeApp/src/commonMain/.../ui/settings/MediaModelsSettings.kt` | Media model picker in Settings → Services → Media |
| `composeApp/src/commonMain/.../ui/chat/composables/TopBar.kt` | Main top bar: media icon opens the studio, chat icon returns from it |
| `composeApp/src/commonMain/.../ui/chat/ChatScreen.kt` | `isMediaOpen` state and the top bar wiring |
| `docs/knowledge/media-providers/` | Provider contracts and refresh playbook |

See also: [multi-service.md](multi-service.md) (chat services, API keys), [tools.md](tools.md) (agent tool registry), [hands-free.md](hands-free.md) (voice loop), [chat.md](chat.md) (inline media in chat).
