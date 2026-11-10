# Plan: Media Studio — generation, canvas, agent tools (phased)

Status: Phase 0 + provider skeleton implemented; Phases 1–5 tracked below.
Last verified: 2026-11-10. Source: PlusVibeAPI docs snapshot in `docs/knowledge/media-providers/plusvibe.md`.

## 1. Phase 0 — Knowledge and design (done)

* `docs/knowledge/media-providers/{index,plusvibe,refresh-playbook,log}.md` — full async contract (catalog/price/generate/poll/file), sync `/v1/images` shortcut, `*_base64` + Files API, STT/TTS pipeline reference, webhook-is-out-of-scope rule.
* `docs/features/media-studio.md` — behaviour spec, key files, UX decisions (tap-point context menu, merge-to-reference, enhance-before-generate, gallery import/export).

## 2. Phase 1 — Provider abstraction + PlusVibe adapter (backend, no UI)

1. Define `MediaKind` (`image/video/audio/music/other3d`), `MediaModel`, `MediaParam`, `MediaJobRequest` (`model`, `prompt`, `opts`, `quoteId`, `maxPriceRub`, `idempotencyKey`), `MediaJobState` (`submitting|processing|success|fail` + `resultUrls`, `priceRub`, `failMsg`, `errorCode`, `failure{code,advice,definitive}`).
2. `MediaProvider` interface: `listModels()`, `quotePrice(req)`, `submitJob(req)`, `pollJob(id)`, `downloadResult(url)`.
3. `PlusVibeMediaProvider` (Ktor, `ignoreUnknownKeys`):
   * `GET /api/media-catalog` → parse `kind`, `unit`, `fromRub`, `variants[]`, `params[]`, `axes[]`, `priceUnavailable`, `generationAvailable`/`displayOnly` filtering.
   * `POST /api/media/price` → `{ priceRub, quoteId }`.
   * `POST /api/media/generate` HTTP 202 → `{ jobId }`; map HTTP errors (400/401/402/422/502/503) to typed `MediaException` without charge.
   * `GET /api/media/jobs/{id}` → normalize `submitting` + `processing` as non-terminal; `success` → `resultUrls`; `fail` → `failMsg`/`errorCode`.
   * `GET /api/media/file/{id}/{idx}` + `/media/r/…` → bytes (Range supported by server; client just GETs).
4. Unit-test parsers against canned JSON (catalog entry, quote, 202, success, fail, submitting).

## 3. Phase 2 — Repository: queue, polling, persistence

1. `MediaGenerationRepository` — per-provider queue; `Idempotency-Key` (UUID, 24 h) per user action; exponential polling 3 s → 30 s; client timeout per provider default (video up to ~10 min, image ~5 min); cancel propagates to scope, job continues server-side and is re-polled on reopen.
2. Download-on-success to bytes; cache in app storage (`media/jobs/<jobId>/…`); expose `StateFlow<Map<jobId, MediaJobState>>`.
3. Error normalization: `retry_after` from 429, `insufficient_balance` (402) → settings hint, `model_not_found` (422/404) → catalog refresh, `submission_unknown` → poll-same-id rule.
4. Persist job history (jobId, model, prompt, cost, status, local path) in `DataRepository`/SQLite alongside conversations (no SQLDelight schema change needed if stored as JSON blob like `Conversation.Message`).

## 4. Phase 3 — Settings: models, keys, tools

1. Per-instance storage (existing `Settings` keys, no migration): `instance_<id>_media_image_model`, `instance_<id>_media_video_model`. Default: first available model per `kind` from catalog (prefer `generationAvailable && !priceUnavailable`, lowest `fromRub`).
2. Services settings card gains “Media models” section: image dropdown + video dropdown populated from `listModels()` with price caption (`fromRub` / `unit` / per-variant `priceRub` when known), refresh button, “same key as chat” hint. Catalog is public so the list shows even before a key is set; generate validates the key server-side.
3. `MediaTools`: `generate_image` (model?, prompt, aspect_ratio?, resolution?, image_base64/url?), `generate_video` (model?, prompt, first_frame?, duration?, resolution?), `list_media_models` (kind?), `enhance_prompt` (prompt). Each returns `{ jobId|resultUrls, cost_rub, model }` and works both from chat and studio. Tools tab shows them as toggleable entries (default on).
4. Update `docs/features/multi-service.md`, `docs/features/tools.md`, `docs/features/hands-free.md` (voice/TSS in studio).

## 5. Phase 4 — Chat inline media + lightbox (reuse of `docs/plans/chat-media-inline-rendering.md`)

1. Parse generated media pointers from tool results + markdown image/video links into `ChatMediaAttachment`.
2. Images: full-screen viewer with pinch-zoom, double-tap zoom, pan, “save to gallery”. Videos: inline player (platform `expect/actual` player — ExoPlayer on Android, `java.awt`/HTML on desktop, HTML5 interop on wasm) with seek, play/pause, fullscreen. Audio: seekable player + playlist (later).
3. “Send to Media Studio” action on any chat media → adds a canvas element and opens the studio.

## 6. Phase 5 — Studio UI: entry, canvas, dock, agent bar, voice/TTS

1. `EmptyState` — smaller “Media” button under HandsFree (navigates to `MediaStudioScreen` via `App.kt` navigation state, not a new nav graph).
2. `MediaStudioScreen` — replaces chat body with canvas; dock at bottom: model chip (image/video), aspect/size/duration/resolution (from catalog `params`), reference chips (combine/merge), “✨ enhance” button, generate button with price quote (`POST …/price` before submit; show `x-pv-cost-rub` result afterwards).
3. `MediaCanvas` — elements with `Modifier.pointerInput` (drag), `transformable` (pinch scale/rotate), corner handles (mouse resize/rotate), tap-to-select, tap-point context menu (`MediaContextMenu`), video element with inline controls, canvas zoom (pinch/scroll), import from gallery (`FileKit` picker), export composite to gallery.
4. Agent bar — same composer as chat (typed + voice via `HandsFreeController` where STT exists); generation completion → Russian TTS “генерация готова” when speech output is on; agent `generate_*` calls route through the same repository and appear on the canvas.
5. Accessibility: content descriptions, min 48dp targets, focus order, reduced-motion disables infinite animations.
6. Screenshots (`screenshotTests`) + `./gradlew spotlessApply` → `desktopTest` → `updateScreenshots`.

## 7. Phase 6 — Hardening and providers backlog

* Billing balance check (`GET /v1/balance`, `GET /api/key/usage`) before submit; low-balance hint.
* Expired-result handling (`410`) — “re-generate” CTA with stored prompt/params.
* Second provider adapter behind the same interface (catalog normalizer + capability matrix: supports `*_base64`, `strict`, `quote_id`, `Idempotency-Key`).
* Tests: parser goldens, repository poll/state-machine tests, tool schema tests.

## Non-goals (v1)

* Webhook receiver in mobile/desktop (polling only; provider webhook is cabinet-session only).
* Mask/inpaint editing, 3D view, music generation UI (catalog kinds exposed, studio focuses image/video).
* Public gallery sync / cloud backup of media (local storage + device gallery only).
