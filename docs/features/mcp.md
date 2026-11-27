# MCP Servers

**Last verified:** 2026-11-26

Pots supports external tool servers via the [Model Context Protocol](https://modelcontextprotocol.io/) (MCP). Users can connect to remote MCP servers using Streamable HTTP transport and use their tools alongside native tools.

## Concepts

### MCP Server

A remote service that exposes tools via the MCP JSON-RPC protocol. Each server has a name, URL, optional authentication headers, and an enabled state. Server configurations are persisted as JSON in app settings. If the saved list ever becomes unreadable, it is backed up to a separate settings entry before the next save replaces it, so a corrupt blob is never silently lost.

### MCP Tool

A tool discovered from a connected MCP server. Each one is wrapped so it behaves like a built-in tool and integrates seamlessly with the existing tool executor and AI request pipeline. Each MCP tool has an ID of `mcp_{serverId}_{toolName}` and can be individually toggled.

### Server State

A server's configuration, its last known state, and its live connection are three separate things, and the UI and the agent read them in that order:

- **Configuration** (`McpServerConfig` in settings) — name, URL, headers, enabled flag, who added it. Lives until the owner removes the server.
- **State** (`McpServerStatus` in settings) — `disabled` / `connecting` / `needs_auth` / `connected` / `failed`, plus the last failure (kind, HTTP status, text, timestamp) and the last known tool list.
- **Live session** (memory only) — the JSON-RPC client with its session id. Dropped on app exit, on disable, and whenever the server answers 401.

Merging those three in 3.21.0 was the visibility bug: a failed connect threw the discovered tools away, so a server that merely needed a fresh sign-in disappeared from the agent's prompt and showed as a bare red dot.

### Popular Servers

A curated list of verified free MCP endpoints. Displayed as quick-add shortcuts in the add server bottom sheet. Selection criteria: free, Streamable HTTP transport, practically useful, reasonably stable. Most require no API key and one-tap add. Jina AI prefills the form with an optional API key field (stored as `Authorization: Bearer …` when provided); the server can still be added without a key, but search tools need a free key from jina.ai. Existing user-defined headers are never overwritten. The current names live in the runtime popular-server list in code; selection policy, last probe results, and a mirrored snapshot live in the knowledge bundle under `docs/knowledge/popular-mcp/`. Both are refreshed together with the `update-popular-mcp-servers` skill. Dead hosts (`remote.mcpservers.org` Fetch and Sequential Thinking) were removed earlier.

## Adding a Server

In the Tools tab of settings, the "MCP Servers" section appears above native tools. Users can:

- Tap "Add MCP Server" to open a bottom sheet
- Enter a name, URL, and any number of custom headers manually (e.g., `Authorization`, plus additional vendor-specific headers); rows can be added or removed individually
- Or pick from the popular servers list: no-auth servers one-tap add; auth-optional servers (Jina AI) prefill name/URL and show an optional API key field (Add works without a key)

MCP server configurations are included in the settings export/import feature, so the full set of servers (and their headers) can be moved between devices.

## Connection Flow

When a server is added or enabled:

1. Pots creates an MCP client for the server URL and headers
2. Sends an `initialize` JSON-RPC request with client capabilities
3. Sends a `notifications/initialized` notification
4. Calls `tools/list` to discover available tools
5. Registers discovered tools with their metadata (name, description, input schema)
6. The server appears as connected (green dot) in settings

If any step fails, the server keeps its place in the list, its last known tool list and a human-readable reason:

| Result | State | What the owner sees |
|---|---|---|
| `initialize`/`tools/list` succeeded | `connected` | green dot, tool count |
| 401/403 with or without `WWW-Authenticate` | `needs_auth` | orange dot, "Authorization required", an Authorize button |
| 403 with `error="insufficient_scope"` | `needs_auth` | same, with the missing scope in the text |
| other HTTP status | `failed` | red dot, the status code and the server's own message |
| timeout, DNS, TLS, refused connection | `failed` | red dot, "no connection"; the cached tools stay listed |

A failed connect never deletes the cached tool list and never removes the server from the agent's view; a tool call against a `needs_auth` server returns an instruction to press Authorize and retry, not a bare HTTP error.

When the chat screen first opens, all enabled MCP servers are reconnected in the background in parallel, with a short backoff (three attempts) and a per-server timeout. The first time the settings screen becomes visible in a session, the same connect sweep runs once (alongside connection validation for services); later returns to settings do not automatically re-connect failed servers. Servers also reconnect when the user enables a server, taps refresh, finishes a sign-in, or imports MCP settings. All configuration changes (add, remove, enable, headers) are serialized by a mutex, so a background reconnect cannot interleave with a UI edit and drop one of the two writes.

## Server Management

Each server card in settings shows:

- A status dot (green=connected, orange=connecting or needs sign-in, red=failed, grey=disabled/unknown), an enable/disable toggle, and a dropdown chevron
- Clicking anywhere on the card expands/collapses it
- The last failure in words under the header: "Authorization required", "Server error (HTTP 500)", "No connection", "Check the URL — this looks like the SSE endpoint", each with the time of the attempt
- When expanded: the tool list with individual toggles — from the live session when connected, from the persisted cache otherwise, labelled as cached — a refresh button, and remove (removal is deferred with a snackbar "Undo" option before permanent deletion)
- Disabling a server disconnects it immediately, closes its client and hides its tools from the agent; the cached list stays for when it is re-enabled
- While a sign-in is in flight the card shows "Waiting for the browser" with the authorize URL, an "Open link" button and "Copy link" for cases where the browser has to be driven by hand

The UI uses the same card style, status dot colors, and spacing as the Services tab for visual consistency.

## Transport

Only Streamable HTTP transport is supported:

- POST requests with `Content-Type: application/json` and `Accept: application/json, text/event-stream`
- The client tracks `Mcp-Session-Id` headers for session management
- Both direct JSON responses and SSE (Server-Sent Events) responses are handled
- No stdio transport support

## Authentication

Three ways to satisfy a server, in the order the app tries them:

1. **Stored token** — an OAuth access token from a previous sign-in, or a token the owner pasted by hand. Sent as `Authorization: Bearer …`.
2. **Configured headers** — the static headers from the server card (e.g. `Authorization: Bearer <api key>`, `X-Api-Key`). The popular-server sheet and the manual add form collect these; `Bearer` is prefixed automatically when a bare key is entered.
3. **OAuth sign-in** — started by the Authorize button on the card, or by the agent's `mcp_authorize` tool after the owner confirms the dialog.

The OAuth flow follows the MCP authorization spec:

1. `initialize` answers 401 (or 403) with `WWW-Authenticate`; Pots parses the challenge (`resource_metadata`, `scope`, `error`).
2. Protected Resource Metadata (RFC 9728) is fetched from the `resource_metadata` URL, or from `/.well-known/oauth-protected-resource<path>` on the server itself.
3. Authorization server metadata (RFC 8414 `/.well-known/oauth-authorization-server`, then OIDC `/.well-known/openid-configuration`) gives the authorize, token and registration endpoints.
4. Dynamic Client Registration registers Pots as a public client with `redirect_uri` on loopback and PKCE `S256`.
5. A one-shot HTTP listener binds `127.0.0.1` on an ephemeral port; the system browser opens the authorize URL, the listener catches `?code=…&state=…`, checks `state`, and closes.
6. The code is exchanged for tokens with the PKCE verifier; the `resource` parameter (RFC 8707) pins the token to this server's URL.
7. The token is stored in the platform secure storage (Android Keystore-backed settings, iOS Keychain, encrypted file on desktop) — never in `McpServerConfig`, so it is not part of the settings export — and the server reconnects.

Expired access tokens are refreshed with the stored refresh token; a refresh that is refused, a 401 without a usable token, and a `insufficient_scope` 403 all land in `needs_auth` with the reason, never in "no connection".

Platforms without a loopback listener (iOS, web) cannot complete the redirect automatically: the card shows the authorize URL with "Open link" and "Copy link", and the owner pastes the resulting token into the manual-token field. The same field is the fallback when discovery fails — a server that only documents a static key still works.

Signing out clears the stored token for that server and reconnects, so a server that then answers 401 shows as `needs_auth` rather than silently failing calls.

## Agent-Managed Servers

The agent can manage servers on the owner's behalf through six tools: `mcp_list_servers`, `mcp_add_server`, `mcp_remove_server`, `mcp_set_enabled`, `mcp_refresh`, `mcp_authorize`. They are grouped under the `mcp_admin_tools` master switch and are not individually toggleable.

- `mcp_list_servers` returns every configured server with its state, failure reason and tool count — including servers that are failing or awaiting sign-in.
- `mcp_add_server` takes a name, URL and transport (Streamable HTTP only). It refuses private, loopback and link-local addresses: an agent that can be talked into a URL by a web page must not be able to point Pots at the router or at a cloud metadata endpoint. The owner adding the same URL by hand is still allowed.
- Every mutating call (`mcp_add_server`, `mcp_remove_server`, `mcp_set_enabled`, `mcp_authorize`) asks the owner first through the same confirmation dialog the vault uses, and is refused outright in headless runs (background/heartbeat chats have nobody to ask).
- The agent never sees or types a secret: headers, API keys and tokens are entered by the human in Settings → Tools. `mcp_authorize` only opens the browser and returns "waiting for the owner".
- Servers added this way appear in Settings → Tools like hand-added ones, with an "added by agent" badge.

## Integration with Tools

MCP tools are automatically available to the AI — no changes needed to the tool executor or request serialization. The platform's list of available tools includes every enabled tool of every configured server: from the live session when connected, from the persisted cache when the last connect failed. A server whose connect failed therefore keeps contributing its tools, and a call against it returns the reason (press Authorize / server unreachable) instead of a bare HTTP error. A server tool gets a 90-second timeout (vs 30s default for native tools) for its network round-trip; it never shows a confirmation dialog, so the window is all it needs. The gated `mcp_*` admin tools get 240 seconds, which covers the 120-second confirmation window plus the connect or authorization work that follows it. MCP tools are only shown within their server's expanded card in settings, not in the native tools list; the six `mcp_*` admin tools are listed with the other native tools.

Tool calls to MCP servers go through the same execution pipeline as native tools: the tool executor finds the tool by name, the MCP tool wrapper sends a `tools/call` JSON-RPC request to the server, and the result is returned to the AI.

Every tool must have a unique name in a request. An MCP tool whose name is already taken — by a built-in tool or by another server's tool — is offered to the AI as `<server id>_<tool name>` (then `_2`, `_3`, … if needed, limited to 64 characters of `A–Z a–z 0–9 _ -`). Calls to the renamed tool still invoke the server's original tool name; the per-tool enable switches in settings are unaffected. Stopping a run while an MCP tool call is in flight cancels it rather than returning a failed result.

## Limitations

- HTTP/SSE transport only (no stdio)
- CORS may block MCP server requests on the web platform
- The OAuth redirect needs a loopback listener: iOS and web fall back to a manual token
- MCP tool parameters preserve full JSON Schema (including nested `items`, `properties`, `enum`) for accurate API serialization

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../mcp/McpClient.kt` | MCP JSON-RPC client over HTTP/SSE |
| `composeApp/src/commonMain/.../mcp/McpServerManager.kt` | Server configuration, persisted state, live sessions, reconnect |
| `composeApp/src/commonMain/.../mcp/McpStatus.kt` | `McpServerStatus`, `McpServerStateKind`, `McpFailureKind`, HTTP-failure classification |
| `composeApp/src/commonMain/.../mcp/McpAuth.kt` | WWW-Authenticate parsing, discovery, DCR, PKCE, token exchange/refresh, token store, SSRF guard |
| `composeApp/src/commonMain/.../mcp/McpAuthCallback.kt` | Loopback redirect listener (`expect`/`actual` per platform) |
| `composeApp/src/commonMain/.../mcp/McpGate.kt` | Owner confirmation for agent-initiated changes; refuses in headless runs |
| `composeApp/src/commonMain/.../mcp/McpTools.kt` | The six `mcp_*` admin tools the agent can call |
| `composeApp/src/commonMain/.../data/SettingsJson.kt` | Shared settings-backed JSON persistence: decode-or-default, encode-and-write, locked read-modify-write |
| `composeApp/src/commonMain/.../mcp/McpTool.kt` | Wraps MCP tools as native Tool implementations |
| `composeApp/src/commonMain/.../mcp/McpServerConfig.kt` | Server configuration data model (never holds a token) |
| `composeApp/src/commonMain/.../mcp/McpModels.kt` | JSON-RPC DTOs and MCP-specific models |
| `composeApp/src/commonMain/.../mcp/PopularMcpServers.kt` | Curated list of verified MCP endpoints |
| `docs/knowledge/popular-mcp/` | OKF bundle: selection policy, last probe snapshot, refresh playbook |
| `composeApp/src/commonMain/.../ui/settings/McpSection.kt` | MCP server card UI, add-server bottom sheet with multi-header editor |
| `composeApp/src/commonMain/.../ui/settings/SettingsScreen.kt` | Hosts the MCP section inside the Tools tab content |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | MCP connection management and UI state |
| `composeApp/src/commonMain/.../ui/settings/SettingsUiState.kt` | McpServerUiState, McpConnectionStatus |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | MCP server config, status and token persistence, backup of undecodable JSON |
| `composeApp/src/desktopTest/.../mcp/McpAuthTest.kt` | Challenge parsing, PKCE vector, authorize URL, discovery candidates, SSRF guard |
| `composeApp/src/desktopTest/.../mcp/McpServerStateTest.kt` | State transitions on 401/500/network failure, cache survival, tool-name stability, gate, agent tools |
