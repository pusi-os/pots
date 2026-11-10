# Media Studio

**Last verified:** 2026-11-10

Media Studio is the in-app creative surface for generating and editing images and video with the currently active cloud media provider (PlusVibeAPI first). It lives on the chat home: under the HandsFree voice button there is a smaller “Media” button opening the studio. The canvas occupies the place of the chat history; the agent remains available as a prompt bar with typed and voice input, and — when speech output is on — announces completion in Russian (“генерация готова”).

## Concepts

### Media provider

A cloud service exposing a model catalog with prices, an async generation queue, and signed result URLs. The provider adapter owns its HTTP contract (catalog, price quote, submit, poll, download). Pots ships PlusVibeAPI first; the same adapter interface accepts further providers without changing the studio, the settings UI, or the agent tools.

### Media job

One async generation task. The client submits `{ model, prompt, opts }`, receives a `jobId`, polls the provider until a terminal status (`success` / `fail`), and downloads the result bytes immediately — providers keep results only a few days (PlusVibe: 5 days, then `410 media_result_expired`). Paid generation is charged once on `success`; `fail` is free. A stuck (`submitting`) task is polled, never duplicated.

### Canvas element

A placed image or video on the studio canvas. Each element has position, size, and rotation, and can be moved (touch drag or mouse), resized, and rotated — on touch via pinch and on desktop via mouse drag and corner handles. Elements can be stacked (bring forward/send back), duplicated, deleted, and combined into a new generation as references (`input_urls` / `reference_image`). A video element plays inline in the canvas with play/pause and seek; pinch or scroll zooms the canvas viewport.

### Context menu

Tapping an element (touch) or hovering it (desktop) opens a small menu anchored directly under the tap/hover point, not a fixed bottom sheet or dialog. It offers editing actions for that element: move/resize/rotate mode, duplicate, delete, bring forward/send back, use as reference, and clear selection. The menu closes on outside tap or selection change.

### Reference combine

Selected canvas images can be merged into one reference set for the next generation (image-to-image / character-style / multi-frame I2V depending on the model). Local files are encoded as `*_base64` (`data:<mime>;base64,…`); public URLs are passed as-is.

### Prompt enhance

A “magic” button next to the prompt field asks the active chat model to rewrite the prompt into a detailed generation prompt (subject, lighting, style, composition). The original prompt is kept until the enhanced version is accepted.

### Gallery import/export

Generated or canvas assets can be saved to the device gallery (Pictures/Pots) and imported back by picking an image or video. Remote results download as bytes first, then save via the platform gallery writer.

## Behaviour

* Async generation with provider-recommended polling (PlusVibe: 3–5 s, exponential to 30 s), client-side queue per provider, cancel on user stop, no duplicate submit on `submitting`.
* Result URLs are downloaded to bytes immediately; the studio works with local copies and remote URLs as fallback until download completes.
* Prompt field, model picker (image vs video model from the provider catalog with prices), aspect/size, duration/resolution (model-dependent, from the catalog `params`/`axes`), and reference list are always visible in the studio dock.
* The agent prompt bar survives in the studio: typed input, voice input (HandsFree recognizer where available), and TTS “генерация готова” on completion when speech output is on.
* Chat history and canvas stay independent: generating in the studio does not write into the conversation, and chat-generated media does not auto-appear on the canvas (explicit “send to canvas” only).

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../media/MediaModels.kt` | Media kinds, catalog items, job results, canvas elements |
| `composeApp/src/commonMain/.../media/MediaProvider.kt` | Provider interface (catalog, quote, submit, poll, download) |
| `composeApp/src/commonMain/.../media/PlusVibeMediaProvider.kt` | PlusVibeAPI adapter (catalog/price/generate/poll/file) |
| `composeApp/src/commonMain/.../media/MediaGenerationRepository.kt` | Async queue, polling, dedup, download-and-cache |
| `composeApp/src/commonMain/.../media/MediaSettings.kt` | Per-instance image/video model selection storage |
| `composeApp/src/commonMain/.../media/PromptEnhancer.kt` | Chat-model prompt rewriting |
| `composeApp/src/commonMain/.../tools/MediaTools.kt` | `generate_image`, `generate_video`, `list_media_models`, `enhance_prompt` agent tools |
| `composeApp/src/commonMain/.../ui/media/MediaStudioScreen.kt` | Studio surface (canvas + dock + agent bar) |
| `composeApp/src/commonMain/.../ui/media/MediaCanvas.kt` | Canvas with draggable/resizable/rotatable elements |
| `composeApp/src/commonMain/.../ui/media/MediaContextMenu.kt` | Tap/hover floating menu under the element |
| `composeApp/src/commonMain/.../ui/media/MediaDock.kt` | Model picker, params, references, enhance, gallery actions |
| `composeApp/src/commonMain/.../ui/media/MediaStudioViewModel.kt` | Studio state (jobs, elements, selection, playback) |
| `composeApp/src/commonMain/.../ui/chat/composables/EmptyState.kt` | “Media” entry button under HandsFree |
| `docs/knowledge/media-providers/` | Provider contracts and refresh playbook |

See also: [multi-service.md](multi-service.md) (chat services, API keys), [tools.md](tools.md) (agent tool registry), [hands-free.md](hands-free.md) (voice loop), [chat.md](chat.md) (inline media in chat).
