# Media Studio

**Last verified:** 2026-11-11

Media Studio is the in-app creative surface for generating and editing images and video with the currently active cloud media provider (PlusVibeAPI first). It opens from the chat top bar (media icon) and takes over the chat area. While it is open, the chat input is hidden: the studio has its own single prompt field in the dock. Speech output, if on, announces “генерация готова” in Russian when a job finishes.

## Concepts

### Media provider

A cloud service exposing a model catalog with prices, an async generation queue, and signed result URLs. The provider adapter owns its HTTP contract (catalog, price quote, submit, poll, download). Pots ships PlusVibeAPI first; the adapter interface accepts further providers without changing the studio, the settings UI, or the agent tools.

### Media instance

The service instance that bills generation. It is the first configured instance that has an API key; if none has a key, the first configured instance is used and the studio reports the missing key with a shortcut to settings.

### Media job

One async generation task. The client submits the model id, prompt, options and references, receives a `jobId`, polls the provider until a terminal status (`success` / `fail`), and downloads the result bytes immediately. Providers keep results only a few days (PlusVibe: 5 days). Paid generation is charged once on `success`; `fail` is free. A stuck (`submitting`) job is polled, never duplicated. Each submit carries an idempotency key.

### Canvas element

A placed image or video on the studio canvas. Elements can be dragged, rotated and scaled, stacked (bring forward / send back), duplicated, deleted, saved to the device gallery, or used as a reference for the next generation. A tap selects an element and shows quick chips (rotate, scale, fullscreen). The canvas pans and zooms with a two-finger gesture (or the matching trackpad gesture on desktop).

### Context menu

Tapping an element opens a small glass menu anchored under that element. It follows the canvas when the canvas is panned, so it stays attached to its element. The menu closes on outside tap or on an action.

### References

The dock shows a strip of reference thumbnails for the next generation. References come from three places: the file picker in the strip, the “Как референс” action on a canvas element, or the agent. A reference is either a public `https://` URL, which the provider downloads itself, or app-storage bytes, which are sent inline as `data:<mime>;base64,…`. Inline bytes are capped at 8 MB; larger files are refused with a Russian message asking the user to compress them. Each model receives references on the opts keys it declares (for example `image_url`, `image_base64`, or their plural array forms). Models that do not accept an image reference refuse the request before submit.

### Agent photo edits

When the user attaches a photo in chat and asks the agent to improve it or place the person somewhere, the agent calls `generate_image` with `reference_image="attachment"`. The tool takes the last image attached to a user message in the active chat. If the chosen model cannot take a reference, the tool switches to the first catalog model that can, and logs the switch.

## Behaviour

* Async generation with provider-recommended polling (PlusVibe: 3 s, doubling to 30 s), no duplicate submit on `submitting`, and transient poll errors retried with backoff.
* Result URLs are downloaded to bytes immediately and cached under the app storage; the canvas keeps the remote URL as a fallback until the download completes.
* Dock: one prompt field, a round create button, a reference strip, a speech toggle, an “ask agent” icon (sends the typed prompt to the chat agent), an image/video mode switch, the current model chip (opens the model sheet), and parameter chips for aspect ratio, duration and resolution when the model declares them.
* Model sheet: image and video models from the provider catalog, each with a price badge. The selected row uses the theme’s container colors, so its text stays readable. When no model is saved, the first catalog model of the mode is picked and persisted.
* Errors appear as a snackbar with a Russian message. A missing key offers a “Настройки” action.
* Every catalog load, quote, submit, poll, download and reference mapping is logged under the `PotsMedia` tag (logcat on Android, stdout on desktop).
* Chat history and canvas stay independent: generating in the studio does not write into the conversation.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../media/MediaModels.kt` | Media kinds, catalog items, references, job requests and results |
| `composeApp/src/commonMain/.../media/MediaProvider.kt` | Provider interface (catalog, quote, submit, poll, download) |
| `composeApp/src/commonMain/.../media/PlusVibeMediaProvider.kt` | PlusVibeAPI adapter; maps references onto model opts keys |
| `composeApp/src/commonMain/.../media/MediaGenerationRepository.kt` | Async queue, polling with backoff, dedup, download-and-cache |
| `composeApp/src/commonMain/.../media/MediaSettings.kt` | Per-instance image/video model selection and media instance resolution |
| `composeApp/src/commonMain/.../media/MediaLog.kt` | `PotsMedia` logger; platform actuals under `androidMain`, `desktopMain`, `iosMain`, `wasmJsMain` |
| `composeApp/src/commonMain/.../media/PromptEnhancer.kt` | Prompt cleanup used by the `enhance_prompt` tool |
| `composeApp/src/commonMain/.../tools/MediaTools.kt` | `generate_image` (with `reference_image`), `generate_video`, `list_media_models`, `enhance_prompt` agent tools |
| `composeApp/src/commonMain/.../ui/media/MediaStudioScreenFull.kt` | Studio composer: canvas, dock, snackbars, sheets, lightbox |
| `composeApp/src/commonMain/.../ui/media/MediaStudioTopBar.kt` | Top capsule of icon buttons (chat, recenter, import, models) |
| `composeApp/src/commonMain/.../ui/media/MediaCanvas.kt` | Dotted canvas, draggable elements, selection chips, skeletons, empty state |
| `composeApp/src/commonMain/.../ui/media/MediaContextMenu.kt` | Floating menu under the selected element |
| `composeApp/src/commonMain/.../ui/media/MediaDock.kt` | Prompt field, reference strip, mode and model controls, parameter chips |
| `composeApp/src/commonMain/.../ui/media/MediaModelSheet.kt` | Model picker sheet with price badges |
| `composeApp/src/commonMain/.../ui/media/MediaStudioViewModel.kt` | Studio state: jobs, elements, references, model selection, typed errors |
| `composeApp/src/commonMain/.../ui/settings/MediaModelsSettings.kt` | Media model picker in Settings → Services → Media |
| `composeApp/src/commonMain/.../ui/chat/composables/TopBar.kt` | Media icon that opens the studio |
| `docs/knowledge/media-providers/` | Provider contracts and refresh playbook |

See also: [multi-service.md](multi-service.md) (chat services, API keys), [tools.md](tools.md) (agent tool registry), [hands-free.md](hands-free.md) (voice loop), [chat.md](chat.md) (inline media in chat).
