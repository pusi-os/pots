# Multi-Service

**Last verified:** 2026-10-28

The RF build supports the built-in trial presets plus five Russian-accessible gateways: Polza.ai, GPTunnel, AITUNNEL, ProxyAPI and VseGPT. All of them use the **OpenAI-compatible** chat-completions format; the remaining choices are the generic **OpenAI-Compatible API** adapter and **LiteRT on-device** inference. A handful of OpenAI models additionally require OpenAI's **Responses API**; Pots switches to it per model when an OpenAI-compatible base URL points at `api.openai.com`. Users can configure multiple service instances, reorder them, and Pots automatically falls back through the chain on failure.

## Concepts

### Service

A supported LLM provider. Each service is defined by:

- A unique name and ID
- Whether it requires an API key
- Which API format it uses (OpenAI-compatible or on-device LiteRT)
- Links to the provider's API-key management page

### Service Instance

A configured connection to a service. Users can add multiple instances of the same service (e.g. two OpenAI accounts with different keys). Each instance stores its own:

- API key
- Selected model
- Base URL (relevant for the OpenAI-Compatible API service)

### Free Tier

A built-in service that requires no API key. Free is never shown in the service picker — it is used as:

- The sole service when no other services are configured
- A last-resort fallback when "Use as fallback" is enabled (default)

There are two trial presets, both selectable from chat and from Pulse:

- **Trial Flash** talks to OpenCode's free endpoint and serves the `big-pickle` model. It sends the OpenCode session headers and the public client key.
- **Trial Smart** talks to the self-hosted Pots endpoint and serves the `pots4free` model.

The endpoints and model ids are compile-time constants; the API key it uses is **not** part of the repository. It is read from the `pots.free.apiKey` property of the git-ignored `local.properties` file and baked into the binary at build time, so:

- A build made from a checkout without that property ships without a bundled key. The free tier then only works once the user enters a key of their own, which is always allowed
- A key entered in the settings takes precedence over the bundled one
- Rotating the key is a matter of changing `local.properties` and rebuilding — no code change, no commit

When a trial preset is the only path and the user hits its rate or quota limit, chat shows a signup panel for the five RF gateways so they can open an API-key page and continue with a personal account. See [chat.md](chat.md).

## Fallback Chain

1. Configured instances are tried in the order the user arranged them
2. Only instances with valid API keys are considered
3. If no instances are configured, the Free tier is used as the only service
4. If instances exist and "Use as fallback" is enabled (default), the Free tier is appended as the last resort
5. Each individual API request retries up to 2 times with increasing delays before the service is considered failed. During a tool-use loop, each request inside the loop retries independently — a failure mid-loop never replays the loop (and its tool executions) from the start. On-device (Local Model) attempts are not retried, since their failures are deterministic rather than transient
6. On failure, the next instance in the chain is tried; if all fail, the last error is shown
7. If a fallback succeeds, the response indicates which service answered
8. While the chain is being walked, the thinking indicator shows per-attempt status — the name of the service currently being tried, or the reason the previous one failed before moving on — so silent fallbacks are visible to the user
9. Entries whose context window can't fit the current chat history are skipped during the walk
10. On-device (Local Model) failures are not silently absorbed — they short-circuit the fallback chain so the user sees the actual error rather than being quietly bumped to a cloud service
11. On-device entries are also never used as fallback targets: a local model is only tried when it is the primary (first) service in the chain. A cloud-service failure never silently starts a local model load
12. Errors that a retry can't fix skip further **per-service** retries and fail that service immediately: exhausted credits or quota, an invalid API key, an unknown model, a request the provider rejects as malformed, too large or disallowed by moderation, a history that exceeds the context window, and attachments rejected locally for size or type. Unknown or generic errors stay retryable. The fallback chain still continues to the next instance. Only on-device (Local Model) failures short-circuit the entire chain

## API Formats

Every remote service in the RF catalog uses the **OpenAI-compatible** chat completions format, with GPTunnel using a raw `Authorization` key instead of a bearer token. **LiteRT** runs inference on-device using Google's LiteRT LM SDK -- no HTTP, no API key, fully offline. The former Gemini-native and Anthropic-native adapters were removed with their providers; a snapshot of the old catalog and free-tier lists is kept under `docs/backups/`.

The **OpenAI-Compatible API** service supports a custom base URL, defaulting to `localhost:11434/v1` for local Ollama setups. The base URL should include the version path segment (e.g., `http://localhost:11434/v1` or `https://my-provider.com/api/v1`), following the OpenAI SDK convention. Pots appends only `/chat/completions`, `/responses` or `/models` to this base URL. A pasted full chat endpoint (ending in `/chat/completions`) is trimmed back to the base first, so the path is never doubled and the models list still resolves.

### OpenAI Responses API

OpenAI rejects function tools on chat completions for GPT-5.6 (Sol, Terra, Luna) and every newer family (GPT-6, …), because those models reason by default and that combination is only supported on the Responses API. Any tool-enabled chat -- which is most of Pots -- therefore failed outright on those models.

Pots reads the GPT version from the model id and, from 5.6 upward, sends their requests to the Responses API instead. The switch is automatic and per model: no setting, no separate service entry, and every other OpenAI model stays on chat completions. It applies only when the request actually reaches OpenAI -- either the OpenAI service, or the OpenAI-Compatible service with a base URL pointing at `api.openai.com`. Aggregators that resell the same models translate to the Responses API on their own side and keep receiving chat completions.

Everything ahead of the wire call is shared with the chat-completions path: system prompt placement, attachment handling, tool-call pairing, and context trimming. Only the payload shape differs -- messages become input items, tool calls become `function_call` items, tool results become `function_call_output` items, and images move from a nested `image_url` object to a flat string.

Two deliberate limits:

- **Responses are not stored** (`store: false`), so OpenAI retains nothing server-side and Pots replays the full conversation on each request rather than chaining response ids.
- **Reasoning items are not replayed.** OpenAI recommends echoing back the reasoning that preceded a tool call, but replaying one whose following item was dropped by context trimming is a hard error. Pots trades a re-reasoned tool round-trip for a request that cannot fail that way. Reasoning summaries, when the account is eligible to receive them, are shown in the usual "Thinking" section.

Matching on the version rather than a fixed list means a new OpenAI family is routed correctly on release, with no app update.

### Session Header (OpenCode)

OpenCode Zen identifies the client behind a request by an `x-opencode-session` header and rejects requests that arrive without one. Pots sends the current conversation id as that session id, so one chat -- however many turns, tool round-trips or bailout retries it takes -- reads as a single session upstream, and separate chats read as separate sessions. Requests that belong to no conversation (fetching the model list, connection validation) carry a session id generated once per app process instead.

An OpenAI-Compatible service whose base URL points at `opencode.ai` -- the usual way to reach the OpenCode Go subscription gateway -- is sent the header too.

The id is Pots's own random conversation identifier; nothing about the user or the machine is derived from it. No other provider is sent the header.

## Supported Services

| Service | `id` | Requires API Key | API Type |
|---|---|---|---|
| Trial Flash (OpenCode free) | `opencode-free` | No | OpenAI-compatible |
| Trial Smart (Pots free) | `free` | No (bundled key when built with one) | OpenAI-compatible |
| Polza.ai | `polza` | Yes | OpenAI-compatible |
| GPTunnel | `gptunnel` | Yes | OpenAI-compatible (raw `Authorization` key) |
| AITUNNEL | `aitunnel` | Yes | OpenAI-compatible |
| Prosvetim | `prosvetim` | Yes | OpenAI-compatible (same endpoint family as Trial Smart, own key, model list from the account) |
| ProxyAPI | `proxyapi` | Yes | OpenAI-compatible |
| VseGPT | `vsegpt` | Yes | OpenAI-compatible |
| OpenAI-Compatible API | `openai-compatible` | No (optional) | OpenAI-compatible |
| Local Model | `litert` | No | On-device (LiteRT LM) |

## Connection Validation

When the user enters or changes an API key (or base URL), the app validates the connection after an 800 ms debounce and shows a status indicator: **checking**, **connected**, **invalid key**, **quota exhausted**, **rate limited**, **connection failed**, or **local network access denied**. Validation also runs for all services when the settings screen opens. Remote services validate by fetching their `/models` endpoint with the configured key. The trial presets are always considered valid. On a successful connection, the available model list is refreshed.

### Local Network Servers (Android)

Android 17+ blocks all traffic to local network hosts unless the user grants the local network permission. When a service's base URL points at a LAN host (private IP range, `.local` name, or a bare hostname — loopback is exempt), the app requests the permission before validating the connection and before sending a chat message. If the user denies it, the connection status shows "local network access denied" with an "Open settings" button that jumps straight to the app's system settings page, and chat shows an actionable error instead of a silent failure. When the user returns to the app after granting the permission there, the denied connection re-validates automatically (without ever re-prompting). Other platforms don't gate local network access, so the check is a no-op there.

## Model Selection

When a connection is validated and models are fetched, the app auto-selects a model if none is chosen — first checking for a per-service default model (e.g. LongCat defaults to "LongCat-Flash-Lite", Perplexity defaults to "sonar-pro"), then preferring "kimi-k2.5" if available, otherwise the first model in the list. Services with a strict `/models` shape can filter inactive models or by `type == "chat"`; the current RF gateways expose ordinary chat-model lists.

If a stored list-selection model id is not in the fetched list, it is kept and shown as selected rather than overwritten by auto-select.

### Custom model (OpenAI-Compatible API)

The **OpenAI-Compatible API** config card keeps the normal model dropdown (when a list is available) and adds a **Custom model** checkbox below it. When checked, a free-text field appears for a model id the server accepts even if `/models` never returns it (e.g. a free tier omitted from the list). List selection and custom id are stored separately; chat uses the custom id only while the checkbox is on. Toggling the checkbox off restores the list selection without losing the typed id.

### Model Cards

The model picker modal shows each candidate as a card with consistent metadata regardless of provider:

- **Title** (top left) — a human-readable display name from the curated catalog or the provider's API; falls back to the raw model id only when no display name is available
- **Free badge** — reserved for models explicitly marked free by a curated provider catalog; no RF gateway currently ships such a catalog.
- **Arena score** (top right) — LMArena Elo rating as colored text, gradient from green (>= 1400) through lime/yellow to orange (< 1250)
- **Detail line** (below the title) — release date, parameter count, and context window joined into a single muted line separated by ` · ` (e.g. `Mar 2025 · 70B · 200K ctx`); any missing field is simply omitted from the line

The card representing the currently selected model is highlighted with a filled accent background so users can identify their current choice at a glance when reopening the picker.

The modal includes sort chips (Date, Score, Ctx) below the search field. Tapping a chip switches which field is active; all sorts are descending (highest or most recent first), with no ascending option. Default sort is by score. When the current service list includes any free-tier model, a **Free** filter chip also appears: selected, it shows only free-tier models while the active sort still applies; if the filtered list is empty, a short empty message is shown. The Free chip is hidden for services with no free-tier catalog entries.

Context window and release date come from two sources, merged by the mapping layer: a bundled curated catalog of well-known models, and whatever the provider's own models endpoint returns (e.g. OpenAI-compat `context_window` and `created`, Anthropic `created_at`). The curated catalog wins; provider-supplied values are used only as a fallback when the catalog has no entry for that model. The catalog is hand-maintained to correct inconsistencies in what providers report. Models not present in the catalog still render — they just use whatever the API provided, and any unknown fields are hidden.

The old free-tier provider catalog was removed with its providers; a copy is kept in `docs/backups/FreeTierModels.kt.pre-rf-backup.txt`. The two trial presets do not depend on it.

## Chat Screen Service Toggle

The chat screen service toggle lists two distinct trial entries — **Trial Flash** and **Trial Smart** — followed by every configured non-Free service (including on-device and models that do not support tools). When this list contains more than one entry, a circular service icon button appears to the right of the chat input, next to the send/stop button. Because the two trial modes alone already count as two entries, the toggle can appear even when no non-Free services are configured. The icon represents the current primary service (each service has its own simplified vector icon). Tapping it opens a dropdown listing those services with their icons, names, and model IDs; the current primary is highlighted with a primary container background. The dropdown is height-capped to the screen and becomes scrollable when the list is long enough to overflow, so every entry stays reachable.

In **Interactive UI mode**, the same control is filtered to services whose selected model supports tool use (agentic flows), and on-device services are excluded so the user cannot switch to a model that cannot produce pots-ui.

Selecting a configured entry reorders the configured list so the chosen service becomes first (primary). Selecting a trial entry (Flash or Smart) instead flips an "is Free primary" flag and records the chosen Free mode — Free can be promoted to primary independently of the configured fallback order, without rearranging the non-Free chain. The fallback chain picks up the new state automatically. The fallback walker also skips any entry whose context window can't fit the current chat history, so very long conversations may transparently move past services that would otherwise be eligible.

## Attachments

Image attachments are gated per service and per model. The trial presets are treated as text-only, while RF gateways accept images when the selected model is vision-capable. When the active service or model cannot take images, images are dropped from the request and the file picker stops offering image types, so a text turn still goes through. Unknown models are assumed image-capable. PDFs are advertised only where a service declares native document support (ProxyAPI currently does); the OpenAI-compatible wire path otherwise drops PDF binaries. The on-device Local Model hides file attachment affordances entirely.

## Settings UI

Users manage services through the settings screen:
- **Add** — pick from the list of available services (can add the same service multiple times); the OpenAI-Compatible API and the on-device Local Model are pinned to the top of the picker, with the five RF gateways sorted alphabetically below them
- **Remove** — delete an instance and its stored credentials; deletion is deferred with a snackbar "Undo" option (~4 seconds) before the service is permanently removed
- **Reorder** — drag to change priority (first = primary, rest = fallbacks)
- **Configure** — per-instance API key, model selection, base URL (OpenAI-Compatible only; optional custom model id via checkbox)
- **Trial fallback toggle** — controls whether the active trial preset is appended as last resort

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/Service.kt` | Service definitions, all provider metadata |
| `composeApp/src/commonMain/.../data/ModelCatalog.kt` | Curated context window / release date / Arena Elo for well-known models |
| `docs/knowledge/model-catalog/` | OKF bundle: Arena Elo matching policy, attested snapshot, refresh playbook |
| `composeApp/src/commonMain/.../data/FreeProviderSuggestions.kt` | Providers recommended in chat when Free is rate-limited with no services configured |
| `composeApp/src/commonMain/.../data/ModelTransformations.kt` | Maps provider model DTOs to `SettingsModel` and merges catalog metadata |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Service instance storage, credential persistence, migration |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Fallback chain, request orchestration, model execution origin |
| `composeApp/src/commonMain/.../data/ServiceEntry.kt` | Shared chat/Pulse model picker entries, including trial presets |
| `composeApp/src/commonMain/.../network/Requests.kt` | HTTP clients for all API formats |
| `composeApp/src/commonMain/.../data/ModelCapabilities.kt` | Per-model gates: tool support, image support, Responses API routing |
| `composeApp/src/commonMain/.../data/providers/OpenAIResponsesInput.kt` | Translates chat-completions messages into Responses API input items |
| `composeApp/src/commonMain/.../network/dtos/openairesponses/` | OpenAI Responses API DTOs |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Connection validation, service management UI logic |
| `composeApp/src/commonMain/.../tools/PermissionController.kt` | Runtime permission requests, including the local network gate for LAN server URLs (Android 17+) |
| `composeApp/src/commonMain/.../tools/LocalNetworkUrl.kt` | Detects LAN base URLs that need the local network permission |
| `composeApp/src/commonMain/.../ui/chat/ChatScreen.kt` | Chat screen, renders ServiceSelector |
| `composeApp/src/commonMain/.../ui/chat/composables/ServiceSelector.kt` | Compact service toggle dropdown |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Wires service selection and reordering |
