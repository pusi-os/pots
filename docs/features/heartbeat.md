# Heartbeat

**Last verified:** 2026-11-28

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
2. On each poll, it checks: is the overall scheduling-enabled toggle on (the tasks setting that gates all scheduled work)? Is heartbeat enabled? Is the current hour within active hours? Is the backoff gate (`nextAllowedAtEpochMs`) clear? Has the interval, with a deterministic ±10 % jitter, elapsed since the last attempt? A missed interval is not replayed — a device that slept for six hours runs once, not twelve times.
3. **Single flight.** The tick and the "Run now" button both go through one mutex. A run that finds another in flight does nothing; a *button* press that finds one records a `busy` line in the journal, because the user pressed something and deserves an answer. A tick stays silent in that case — it is not an event.
4. A heartbeat prompt is built from **external state only** (see [Prompt Building](#prompt-building)) and sent with the full tool-calling loop under a 5-minute wall-clock limit.
5. **Fresh session per run.** The run gets an ephemeral conversation id `hb-run-<runId>` and binds its tool calls (shell, files) to that session. The id is unique per run, so two runs never share a shell and the model never sees a previous run's transcript. The session is closed when the run ends. Shell sessions live only in the running process and are never persisted, so a run that dies with the process takes its shell with it — there is nothing to clean up at the next start-up.
6. The outcome is classified from the run's **evidence** (see [Outcome contract](#outcome-contract)), the journal entry is written, and — on a failure — the exponential backoff is armed.
7. After a successful run, the notification store's retention limits are swept.

When the overall scheduling-enabled toggle is off, heartbeats do not run regardless of the heartbeat toggle, interval, or active hours. Switching the pulse off mid-run cancels the run: nothing of a half-run lands in the Pulse conversation, the session is closed and a `cancelled` line is journalled.

### Backoff

A failure is not retried on the next 60-second tick. `ERROR` and `TIMEOUT` arm a backoff of `interval × 2^(failures-1)`, capped at one hour; a success clears it. Before 3.21.8 a dead key meant ~1440 provider calls a day.

### A run that seems stuck

Every run is bounded twice: the provider call is cut at 5 minutes wall clock (`timeout`), and the tool loop stops itself at its budget (`budget`). Neither outcome writes into the Pulse. To stop a run *now*, switch the pulse off — `Settings → Agent → Heartbeat` cancels the in-flight job, closes its `hb-run-*` session and journals `cancelled`; nothing half-finished survives. Killing the app does the same thing less politely: the process — and with it the in-memory shell of the run — dies, and that run's journal line may be missing.

The scheduler runs on the process scope, so on Android it only ticks while `DaemonService` keeps the process alive. In Doze the OS suspends that process and the timer with it; when the device wakes, the interval check sees a single missed window and runs **once** — the missed intervals are not replayed.

## Outcome contract

The reply text is not evidence. One run ends in exactly one of these, decided from the tool trace the run produced and from what the prompt actually carried:

| Outcome | Decided by | Reaches the user? |
|---|---|---|
| `ok_silent` | the reply is `HEARTBEAT_OK`, optionally with up to 300 characters of trailing/leading text | no |
| `reported` | at least one tool call was executed, **or** the prompt carried fresh material (new mail/SMS/notification that arrived since the last run) | yes — conversation entry, and a push when the app is in the background |
| `chatter` | no tool call and nothing fresh: the model only talked (including "I'll check this next time") | **no** — journal only |
| `duplicate` | same content fingerprint delivered inside the suppression window | no — counted, see [Anti-repeat](#anti-repeat) |
| `budget` | the run hit its tool/token ceiling | no — the answer is partial by construction |
| `timeout` | no answer within 5 minutes | no |
| `error` | every configured instance failed | no |
| `cancelled` | the pulse was switched off (or the scheduler stopped) mid-run | no |
| `busy` | a manual run found another run in flight | no |

The action contract in the prompt matches this: anything the run intends to do is done with a tool **in this run**, filed with `schedule_task` (`execute_at` / `cron` / `on_heartbeat`), or not done. Nothing reads a promise back, so a promise is never delivered.

A heartbeat addition is **not** fresh material: it sits in the prompt on every run, so if it counted, a standing instruction plus "I'll check it next time" would notify the user on every single tick. Only a tool call or newly arrived mail/SMS/notification makes a tool-less answer a report.

`HEARTBEAT_OK` is matched by **position** (start or end of the reply) with a 300-character remainder, not as a substring: a report that quotes the token in the middle of a sentence is a report, and the token is stripped from the delivered body.

### Anti-repeat

Every delivered report is fingerprinted (whitespace- and case-normalised content hash) and stored in the `heartbeat_ledger` setting, capped at 40 entries. The same fingerprint inside a **6-hour window** is suppressed as `duplicate` instead of being delivered again. Suppressions are counted per fingerprint; three of them inside 24 hours surface "the pulse keeps repeating the same report (N times in 24 h)" in both the settings journal and the top of the Pulse conversation. Clearing the history clears the ledger too.

### Budget

One run is limited to **12 tool calls and 60 000 tokens**. The ceiling is enforced in the local tool loop (which answers with a `toolErrorJson` telling the model to answer with what it has) and reported back as `budgetExhausted`, which classifies the run as `budget` and keeps the partial answer out of the conversation. The run's pending mail/SMS/notification queues are left untouched too: nothing is consumed by an answer the user never saw, so the next run shows the same material again.

## Response Handling

- Nothing from a run that ended `ok_silent`, `chatter`, `duplicate`, `budget`, `timeout`, `error` or `cancelled` is written into the Pulse conversation — those outcomes live in the run journal only
- A `reported` run is appended to the dedicated Pulse conversation, which keeps its 50 most recent messages, and the unread-heartbeat indicator is set
- A dismissable banner appears at the top of the chat when the pulse has something to report
- **Android push notification**: when a report is delivered *and* the app is not currently in the foreground (tracked at the process level, so any visible activity counts), a push notification titled "Pots heartbeat" fires. Tapping it launches/foregrounds the app and opens the Pulse conversation. The notification uses a fixed id so a fresh report replaces the previous unread one instead of stacking. The body is the response with markdown and dynamic-UI blocks stripped, capped at 240 characters with an ellipsis; the cut lands on a word boundary when one falls within the last 40 characters, otherwise it is a hard cut. Desktop/iOS/web no-op (banner-only).
- Tapping the banner loads the Pulse conversation so the user can read the report and reply
- The X button dismisses the banner without navigating
- Pulse conversations are included in the chat history list with a "Heartbeat" label badge, and can also be accessed via the banner
- If the API call fails, a failure entry is recorded in the journal and the pending email/SMS/notification queues are left untouched for the next run
- The Pulse conversation is a **write-only journal** for the scheduler and **read-only** for the model: typing in it does not reach a run, and no run reads it

## Prompt Building

The heartbeat prompt is assembled by a pure, unit-tested builder; every conditional section is omitted when it has nothing to show. Sections, in order:

1. **Custom prompt** — user-defined text from settings, or the default prompt if empty. The default instructs the AI to review memories and tasks, respond "HEARTBEAT_OK" if nothing needs attention, or address anything that does, and reminds it that it cannot change the heartbeat schedule
2. **Heartbeat Additions** — the prompts of all pending heartbeat-triggered tasks, each with its description and id, under `## Heartbeat Additions`. The section tells the AI to address each one in this run: do it with a tool now, file it with `schedule_task`, or say plainly that it is not needed — a promise to do it later is not an action
3. **Pending tasks** — pending time- and cron-triggered tasks are listed with their description, id, scheduled time, and cron expression (if recurring). Heartbeat-triggered tasks appear under Heartbeat Additions instead
4. **Email status** — if email is enabled and accounts exist, each account's email address, unread count, and last sync time are included
5. **New emails** — headers (subject, from, preview) for emails polled since the last heartbeat pickup, inside an `<untrusted-data source="email">` fence. Emails are fetched in the background by the email poll loop and buffered in a pending queue (capped at 100, FIFO). The heartbeat consumes the queue: everything the heartbeat saw is removed from the queue once the run concludes (`ok_silent` or `reported`), while emails that arrive during the heartbeat call remain for the next run. A run cut off by its budget consumes nothing — its answer never reached the user. After consumption the heartbeat also advances each account's delivery watermark, so a follow-up `check_email` call from the user won't re-surface the same messages — Pots tracks read/unread internally and ignores the provider's `\Seen` flag
6. **New SMS** — SMS messages received since the last heartbeat (only while SMS is enabled), inside an `<untrusted-data source="sms">` fence. Consumed analogously to emails: buffered as they arrive, surfaced once under `## New SMS`, and cleared from the pending queue once the run concludes so the next heartbeat only sees newer arrivals (a budget-cut run keeps the queue)
7. **New notifications** — Android notifications captured since the last heartbeat (only while notification access is enabled), capped at 20 newest-first, inside an `<untrusted-data source="notification">` fence; any beyond the cap stay queued for later heartbeats. Consumed analogously to emails: buffered as they arrive, surfaced once under `## New Notifications`, and cleared from the pending queue once the run concludes so the next heartbeat only sees newer arrivals (a budget-cut run keeps the queue)
8. **Promotion candidates** — memories with 5 or more hits are listed with their key, hit count, category, and content, with a note that promotion into the soul happens in a normal chat (the `promote_learning` tool refuses to run headless)
9. **Pulse limits** — the run's tool and token ceiling, restated in the prompt so the model budgets itself instead of being cut off

**There is no section carrying previous heartbeat answers.** The builder takes no conversation input at all, so feeding the model its own output is a compile error rather than a matter of discipline; the Pulse conversation is never part of the prompt, and each run starts from external state only. Everything inside an `<untrusted-data>` fence is data from other apps, not instructions.

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

- Stores up to 50 most recent runs; the oldest are evicted first. The settings UI folds the list into a collapsed block, so a longer history costs no screen space
- Each entry records the outcome (see [Outcome contract](#outcome-contract)), a timestamp, wall-clock duration, the `provider / model` the run used, how many tools it executed, whether anything was delivered to the user, an optional error line and an optional note (today: a fallback switch)
- Cleared with "Clear pulse history" in the settings UI, which also empties the Pulse conversation and the anti-repeat ledger — one button for "forget what the pulse did", so the three cannot drift apart
- Failed entries display `<reason>: <provider detail> (<provider / model>)` (up to 3 lines, ellipsized) below the timestamp in the error color. The reason is a `ProviderFailureReason` name — `Unauthorized`, `ModelNotFound`, `RateLimited`, `Timeout`, `Unreachable`, `BadResponse`, `Unknown` — never a bare exception class name
- Two routing failures are reported verbatim rather than with a reason prefix, because the message is already the whole answer: `configured instance not found: <id>` and `no configured service instances`
- Rows written by older versions still decode: the added fields default to zero, so an old entry simply has no duration or model

## Promote Learning

When a memory has been reinforced 5 or more times, it becomes a promotion candidate. The `promote_learning` tool is available to the AI in normal chats whenever the scheduled-tasks toggle is on (it is hidden when scheduling is off). **It refuses to run in a headless run**: a heartbeat or scheduled task gets `promote_learning is not available in a background run`, because a prompt-injected email could otherwise ask for a permanent soul instruction while nobody is watching. A background run mentions the candidate in its report instead. In a chat, the tool:

1. Looks up the memory by key
2. Appends the provided `soul_addition` text to the soul/system prompt
3. Removes the original memory from the memory store
4. Returns confirmation with the promoted key and hit count

This allows well-established patterns to graduate from ephemeral memory into permanent AI behavior.

## Settings UI

Settings → Agent → Heartbeat. Beyond the toggle, interval slider, active-hours slider, prompt editor and model pickers:

- **Run now** — starts a run immediately, bypassing the interval, active hours and backoff (the owner is looking at the screen and asked). Still requires the pulse to be on and still goes through the single-flight guard; a press that finds a run in flight writes a `busy` line
- **Show what the next run will get** — the exact prompt the next run would receive, assembled now, so "does the pulse know about X?" is a question with a visible answer
- **Clear pulse history** — with a confirmation, empties the Pulse conversation, the run journal and the anti-repeat ledger
- **Run journal** — collapsed by default; one line per run with outcome, time, duration, model, tool count and whether it notified anyone. A "pulse keeps repeating" warning appears above it when the same report was held back three times
- The Pulse conversation itself opens with a short explanation of what it is, and shows the one-time offer to clear the history accumulated before 3.21.8 (that history is no longer read by anything, but it is still visible; the offer is asked once and the answer is remembered)

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

**A past heartbeat answer is not a task.** The durable source of a standing check is a
`PENDING` task in `## Pending Tasks` / `## Heartbeat Additions`; a previous heartbeat reply is
only a record of what the model said then. If the task behind a check was deleted or paused,
nothing is scheduled any more — so deleting or pausing the task, not asking the model in chat,
is what stops a check that keeps reappearing. The pulse prompt never carries previous replies
(see [Prompt Building](#prompt-building)), so a stale check cannot survive through chat history
either.

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
| `composeApp/src/commonMain/.../ui/settings/ScheduledTasksSection.kt` | Scheduled tasks UI (pause/resume/run now/edit/create) that also shows the pulse journal per task |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Heartbeat setting changes, eligible model list, manual refresh |
| `composeApp/src/commonMain/.../tools/AgentToolSet.kt` | Exposes `promote_learning` only while scheduling is enabled |
| `composeApp/src/commonMain/.../data/MemoryStore.kt` | Promotion-candidate query (5+ hits) |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Opens the heartbeat conversation when a notification tap requests it |
| `composeApp/src/commonMain/.../Platform.kt` | `expect fun sendHeartbeatNotification` — push notification for background heartbeat reports |
| `composeApp/src/androidMain/.../HeartbeatNotifier.android.kt` | Android notification + deep-link intent extra |
| `androidApp/src/main/kotlin/.../MainActivity.kt` | Reads the deep-link extra on launch/new intent and requests opening the heartbeat conversation |
| `androidApp/src/main/kotlin/.../PotsApplication.kt` | Tracks process foreground state for the scheduler |
