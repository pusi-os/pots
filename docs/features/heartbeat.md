# Heartbeat

**Last verified:** 2026-11-19

> Heartbeat is user-controlled (on/off toggle, interval, active hours live in the settings UI). The AI cannot enable, disable, or reschedule it. To customise *what happens on each heartbeat*, the AI creates heartbeat-triggered scheduled tasks via `schedule_task` with `on_heartbeat: true` — these are `HEARTBEAT`-trigger tasks (see [tasks.md](tasks.md)) and their prompts are appended to every heartbeat run under `## Heartbeat Additions`. Each addition is a first-class task the user can see, edit, and cancel.

Pots's heartbeat feature enables periodic automatic self-checks. The AI reviews pending tasks, email status, newly arrived emails, and learned memories on a configurable interval, surfacing anything that needs attention without requiring user interaction.

## Concepts

### Heartbeat

A silent, scheduled prompt sent to the AI during active hours. If nothing needs attention, the AI responds with "HEARTBEAT_OK" and the user sees nothing. If something requires follow-up, the response appears as an assistant message in the chat.

### Active Hours

A configurable time window (default 8:00–22:00) during which heartbeats are allowed to fire. Outside this window, heartbeats are skipped regardless of interval.

### Promotion

A mechanism for graduating well-established memories into the permanent soul/system prompt. Memories that have been reinforced 5 or more times become promotion candidates and are surfaced during heartbeat checks for the AI to evaluate.

## Configuration

Heartbeat configuration is stored as a serialized JSON object in app settings. Values are only editable from the settings UI — there is no AI tool that can flip them:

- **Enabled**: true
- **Interval**: 30 minutes between heartbeats (UI slider offers 5m, 10m, 15m, 30m, 45m, 1h, 2h, 4h)
- **Active hours start**: 8 (hour, 24h format; UI range slider covers 0–23)
- **Active hours end**: 22 (hour, 24h format; UI range slider covers 0–24, where 24 renders as 0:00 and means end-of-day)
- **Model**: optional override for which service+model to use for heartbeats. When set, the run goes to *that instance and its own selected model* — not to the chat's global model. When not set, the default path prefers the first configured **remote** service, then falls back to the first on-device (LiteRT) instance if no remote is configured. Useful for selecting a cheaper or faster model for background checks
- **Fallback model**: optional second service+model. Tried once after the primary gives up (see the retry policy below). When unset, or equal to the primary, only the primary is tried

**If the selected instance disappears** (provider removed, instance re-created with a new id, settings imported from another device) the run does **not** silently substitute another provider. With no reserve configured it fails and the journal says `configured instance not found: <id>`; with a reserve configured the run goes through that reserve and records a success whose note says `fell back to <reserve> after configured instance not found: <id>`. Either way the journal names the missing pin, so the owner never reads the behaviour of a model they did not choose without being told. The slot is cleared the next time the settings screen loads.

**Provider classes** — the same path serves all of them: an OpenAI-compatible remote, the on-device LiteRT model, and a keyless endpoint (a local Ollama/LM Studio instance, `requiresApiKey = false`). A keyless instance sends **no** `Authorization` header at all — an empty header reads as a malformed credential to several gateways and would turn a working local setup into a 401.

UI validation rules:

- Interval must be at least 5 minutes
- Active hours start must be 0–23; end must be 0–24 (24 = midnight next day = full-day coverage)

## Execution Flow

1. The task scheduler polls every 60 seconds. Heartbeat and due scheduled tasks share one retry policy: up to three attempts against the primary instance, then one attempt against the fallback. Which failures are repeated is decided by class, not by count — see [Retry and fallback policy](#retry-and-fallback-policy).
2. On each poll, it checks: is the overall scheduling-enabled toggle on (the tasks setting that gates all scheduled work)? Is heartbeat enabled? Is the current hour within active hours? Has the configured interval elapsed since the last heartbeat?
3. If all conditions are met and no other API call is in progress, a heartbeat prompt is built and sent to the AI with the full tool-calling loop. Tool calls (e.g. shell commands) are bound to the heartbeat conversation's own session, not to whichever chat the user is viewing
4. The last heartbeat timestamp is updated and a log entry is recorded
5. After a successful run, the notification store's retention limits are swept

When the overall scheduling-enabled toggle is off, heartbeats do not run regardless of the heartbeat toggle, interval, or active hours.

## Response Handling

- If the AI response contains "HEARTBEAT_OK", nothing is shown to the user
- Any other non-blank response is appended to a dedicated heartbeat conversation, which keeps its 50 most recent messages, and the unread-heartbeat indicator is set
- A dismissable banner appears at the top of the chat when the heartbeat has something to report
- **Android push notification**: when the heartbeat produces a non-OK report *and* the app is not currently in the foreground (tracked at the process level, so any visible activity counts), a push notification titled "Pots heartbeat" fires. Tapping it launches/foregrounds the app and opens the heartbeat conversation. The notification uses a fixed id so a fresh report replaces the previous unread one instead of stacking. The body is the heartbeat response with markdown and dynamic-UI blocks stripped, capped at 240 characters with an ellipsis; the cut lands on a word boundary when one falls within the last 40 characters, otherwise it is a hard cut. Desktop/iOS/web no-op (banner-only).
- Tapping the banner loads the heartbeat conversation so the user can read the report and reply
- The X button dismisses the banner without navigating
- Heartbeat conversations are included in the chat history list with a "Heartbeat" label badge, and can also be accessed via the banner
- The heartbeat prompt is sent as a standalone message (not including user chat history as context)
- If the API call fails, a failure entry is recorded in the heartbeat log and the pending email/SMS/notification queues are left untouched for the next run. A run cut short because the scheduler itself stopped is not logged as a failure

## Prompt Building

The heartbeat prompt is assembled by a pure, unit-tested builder; every conditional section is omitted when it has nothing to show. Sections, in order:

1. **Custom prompt** — user-defined text from settings, or the default prompt if empty. The default instructs the AI to review memories and tasks, respond "HEARTBEAT_OK" if nothing needs attention, or address anything that does, and reminds it that it cannot change the heartbeat schedule
2. **Heartbeat Additions** — the prompts of all pending heartbeat-triggered tasks, each with its description and id, under `## Heartbeat Additions`. The section tells the AI to address each one and to reply with an acknowledgement rather than HEARTBEAT_OK, since the additions themselves are the attention
3. **Previous heartbeat results** — the last 3 messages in the heartbeat conversation (which can include scheduled-task output), so the AI can track trends, avoid repeating notifications, and detect persistent issues (e.g. "email still unread since last check")
4. **Pending tasks** — pending time- and cron-triggered tasks are listed with their description, id, scheduled time, and cron expression (if recurring). Heartbeat-triggered tasks appear under Heartbeat Additions instead
5. **Email status** — if email is enabled and accounts exist, each account's email address, unread count, and last sync time are included
6. **New emails** — headers (subject, from, preview) for emails polled since the last heartbeat pickup. Emails are fetched in the background by the email poll loop and buffered in a pending queue (capped at 100, FIFO). The heartbeat consumes the queue: everything the heartbeat saw is removed from the queue after a successful run, while emails that arrive during the heartbeat call remain for the next run. After consumption the heartbeat also advances each account's delivery watermark, so a follow-up `check_email` call from the user won't re-surface the same messages — Pots tracks read/unread internally and ignores the provider's `\Seen` flag
7. **New SMS** — SMS messages received since the last heartbeat (only while SMS is enabled). Consumed analogously to emails: buffered as they arrive, surfaced once under `## New SMS`, and cleared from the pending queue after a successful run so the next heartbeat only sees newer arrivals
8. **New notifications** — Android notifications captured since the last heartbeat (only while notification access is enabled), capped at 20 newest-first; any beyond the cap stay queued for later heartbeats. Consumed analogously to emails: buffered as they arrive, surfaced once under `## New Notifications`, and cleared from the pending queue after a successful run so the next heartbeat only sees newer arrivals
9. **Promotion candidates** — memories with 5 or more hits are listed with their key, hit count, category, and content, along with a suggestion to use the `promote_learning` tool

For the full contract of every prompt variation in Pots (chat remote/local, heartbeat, Splinterlands) see [system-prompts.md](system-prompts.md).

## Retry and fallback policy

The failure class decides whether the same instance is tried again. Every failure carries a `ProviderFailureReason`, and that reason is what the journal prints first.

| Reason | Cause | Retried? | What the journal shows |
|---|---|---|---|
| `Unauthorized` | 401, rejected/absent API key | no — goes straight to the fallback | `Unauthorized: 401 Unauthorized (Polza.ai / gpt-4o)` |
| `ModelNotFound` | 404, the model id is not served by this provider | no | `ModelNotFound: …` |
| `BadResponse` | 400/413/422, content policy, unreadable JSON | no | `BadResponse: <provider text>` |
| `BadResponse` (empty body) | the provider answered with nothing at all | yes — the one `BadResponse` shape a retry helps | `BadResponse (Polza.ai / gpt-4o)` |
| `RateLimited` | 429, provider overloaded | yes | `RateLimited: 429 Too Many Requests` |
| `Timeout` | request or socket timeout, attempt exceeded the budget | yes | `Timeout: no answer within 90s` |
| `Unreachable` | DNS, refused connection, TLS failure, 5xx from the provider | yes | `Unreachable: connection refused` |
| `Unknown` | anything unclassified | yes (preserves the old behaviour) | `Unknown: <detail>` |

Retries stop at three attempts **and** at a 90-second wall-clock budget for the primary instance; a single attempt is bounded by whatever is left of that budget, so a hanging endpoint can no longer own three full request timeouts. Backoff is 2 s, then 4 s. Quota/credit exhaustion (`RateLimited` in shape) is **not** retried — a retry cannot restore the balance.

The reason appears **once** in the line. A connection failure already carries `<reason>: <detail>` inside its own message, so the journal prints `<reason>: <detail> (<provider / model>)` and never `<reason>: <reason>: …`.

When the primary gives up:

- **fallback configured** → one attempt against it. If it answers, the entry is a success with a note: `fell back to OpenAI-Compatible API / llama3 after Unauthorized: 401 Unauthorized (Polza.ai / gpt-4o)`. If it fails too, the journal carries both lines: `<primary line>; fallback <fallback line>`.
- **no fallback configured** → the line ends with `; no fallback model selected in Settings → Agent → Heartbeat`.
- **fallback set to the primary itself** → the line ends with `; reserve equals the primary instance` — a reserve *is* selected, it just cannot take over, and saying "no fallback selected" there would be a lie.
- **nothing configured at all** → `no configured service instances` (previously an empty string recorded as a successful run that did nothing).

## Heartbeat Log

- Stores up to 50 most recent heartbeat entries; the oldest are evicted first. The settings UI folds the list into a collapsed block, so a longer history costs no screen space
- The journal can be emptied with `HeartbeatManager.clearLog()`
- Each entry records success/failure, a timestamp, an optional error line, and an optional note
- Displayed in the settings UI under the heartbeat section
- Entries show an OK/FAIL indicator and a formatted local timestamp
- Failed entries display `<reason>: <provider detail> (<provider / model>)` (up to 3 lines, ellipsized) below the timestamp in the error color. The reason is a `ProviderFailureReason` name — `Unauthorized`, `ModelNotFound`, `RateLimited`, `Timeout`, `Unreachable`, `BadResponse`, `Unknown` — never a bare exception class name
- Successful entries with a note (today: a fallback switch) show that note in the muted color, so a run that silently used the reserve is visible
- Two routing failures are reported verbatim rather than with a reason prefix, because the message is already the whole answer: `configured instance not found: <id>` and `no configured service instances`

## Promote Learning

When a memory has been reinforced 5 or more times, it becomes a promotion candidate. The `promote_learning` tool is available to the AI in any conversation — normal chats as well as heartbeat runs — whenever the scheduled-tasks toggle is on (it is hidden when scheduling is off). It:

1. Looks up the memory by key
2. Appends the provided `soul_addition` text to the soul/system prompt
3. Removes the original memory from the memory store
4. Returns confirmation with the promoted key and hit count

This allows well-established patterns to graduate from ephemeral memory into permanent AI behavior.

## Settings UI

The heartbeat section lives in the Agent tab of settings. The toggle and reset button are always visible; every other control only appears while heartbeat is enabled:

- **Toggle** — enables or disables heartbeat with a switch
- **Interval display** — shows the current interval in minutes in the section description
- **Interval slider** — a snap-to-preset slider with positions for 5m, 10m, 15m, 30m, 45m, 1h, 2h, 4h. Displays the formatted value (e.g. "15m", "2h") next to the label
- **Active hours range slider** — a dual-thumb range slider spanning 0–24 (24-hour clock). Displays "H:00 – H:00" next to the label (unpadded hours); the upper-bound value 24 renders as 0:00 to indicate full-day coverage
- **Model pickers** — separate primary and fallback dropdowns, always shown. Each lists trial presets, configured remote services whose model supports tool calling, and the downloaded on-device model when one is configured, with icons and model IDs. The primary's "Default" option uses the remote-first default above; the fallback's default option means "same as primary". A removed service is cleared from either slot on the next state load. When Pulse runs on the local model it receives the built-in tools and MCP servers selected in Settings → Local Model, exactly like a normal local chat
- **Custom prompt editor** — a text field (max 4000 characters) for editing the heartbeat prompt, with a save button that appears when changes are detected. Shows the default prompt text when no custom prompt is set. A character counter (X/4000) is displayed in the editor as the user types
- **Reset to default** — when a custom heartbeat prompt is set, a reset button appears in the heartbeat section header next to the toggle. Tapping it opens a confirmation dialog; confirming clears the custom prompt and restores the default
- **Log display** — a "Recent" label, followed by each log entry (if any) with an OK/FAIL indicator and timestamp
- **Manual refresh** — a refresh icon next to the "Recent" label (always shown while heartbeat is enabled) runs a heartbeat immediately, bypassing the active-hours window and the interval-due check. Only fires while heartbeat is enabled and scheduling is on; the icon shows a progress spinner during the call

## AI Tools

| Tool | Purpose |
|---|---|
| `promote_learning` | Promote a reinforced memory into the soul/system prompt |

Standing additions to heartbeat behaviour are created with `schedule_task(on_heartbeat=true)` — see [tasks.md](tasks.md#heartbeat-triggered-tasks). Those prompts are appended to the main heartbeat self-check, not replaced.

## Managing Scheduled Tasks

Scheduled tasks and heartbeat additions are ordinary app data, not model state — they can be
inspected and changed without talking to the agent.

**Where they live.** Everything is in the app's plain `SharedPreferences` (not the encrypted
store), through `AppSettings`:

| Key | Contents |
|---|---|
| `scheduled_tasks` (`KEY_SCHEDULED_TASKS`) | The whole task list, JSON-encoded `ScheduledTask` array. `TaskStore` owns it |
| `heartbeat_log` (`KEY_HEARTBEAT_LOG`) | The heartbeat journal, JSON-encoded `HeartbeatLogEntry` array |
| `heartbeat_config` (`KEY_HEARTBEAT_CONFIG`) | Interval, active hours, enabled flag, model slots, custom prompt |

**Lifecycle.** `TaskStatus` is `PENDING`, `PAUSED`, or `COMPLETED`. Every executor —
the time poll loop, the cron advance, and the heartbeat-addition gatherer — filters on
`PENDING`, so pausing is a pure status flip: the schedule is untouched and nothing can fire
by accident. Resuming flips it back. Neither needs the schedule recomputed.

**What a heartbeat task is.** `schedule_task(on_heartbeat=true)` creates a task with
`TaskTrigger.HEARTBEAT`. It has no clock and no cron: its prompt is appended to every
heartbeat self-check under `## Heartbeat Additions` for as long as the task is `PENDING`.
Its run history is the heartbeat journal, not `recentExecutions`.

**A past heartbeat answer is not an instruction.** Only a `PENDING` task in
`## Pending Tasks` / `## Heartbeat Additions` asks the pulse to do something. Text in a
previous heartbeat reply is a record of what the model said then; if the task behind it was
deleted or paused, nothing is scheduled any more. The way to stop a check that keeps
reappearing is therefore to delete or pause the task — not to ask the model in chat.

**In the UI.** Settings → Agent → Scheduled Tasks lists every task with a type badge, a
status chip, and the next run. Tapping a row opens the task card with pause/resume, run now,
edit (description, prompt, time or cron, validated), and delete. Run history and the raw
prompt sit in collapsed blocks. The list stays visible when scheduling is off, marked
"schedule is off", so tasks are never invisible just because the switch is down.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/HeartbeatManager.kt` | Config, log management, wrapper that gathers inputs for the pure prompt builder |
| `composeApp/src/commonMain/.../data/SettingsJson.kt` | Shared settings-backed JSON persistence: decode-or-default, encode-and-write, locked read-modify-write |
| `composeApp/src/commonMain/.../data/HeartbeatPromptBuilder.kt` | Pure heartbeat prompt assembly (sections, caps) |
| `composeApp/src/commonMain/.../tools/HeartbeatTools.kt` | AI tool definitions for heartbeat and promotion |
| `composeApp/src/commonMain/.../data/TaskScheduler.kt` | Poll loop that triggers heartbeat checks |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Persisted heartbeat config, prompt, and log storage |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Heartbeat conversation creation, unread flag management; default model selection for `askWithTools` |
| `composeApp/src/commonMain/.../ui/chat/composables/HeartbeatBanner.kt` | Dismissable notification banner UI |
| `composeApp/src/commonMain/.../ui/settings/HeartbeatSection.kt` | Heartbeat settings UI section |
| `composeApp/src/commonMain/.../ui/settings/AgentSettings.kt` | Agent tab layout that hosts the heartbeat section |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Heartbeat setting changes, eligible model list, manual refresh |
| `composeApp/src/commonMain/.../tools/AgentToolSet.kt` | Exposes `promote_learning` only while scheduling is enabled |
| `composeApp/src/commonMain/.../data/MemoryStore.kt` | Promotion-candidate query (5+ hits) |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Opens the heartbeat conversation when a notification tap requests it |
| `composeApp/src/commonMain/.../Platform.kt` | `expect fun sendHeartbeatNotification` — push notification for background heartbeat reports |
| `composeApp/src/androidMain/.../HeartbeatNotifier.android.kt` | Android notification + deep-link intent extra |
| `androidApp/src/main/kotlin/.../MainActivity.kt` | Reads the deep-link extra on launch/new intent and requests opening the heartbeat conversation |
| `androidApp/src/main/kotlin/.../PotsApplication.kt` | Tracks process foreground state for the scheduler |
