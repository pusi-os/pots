@file:OptIn(kotlin.time.ExperimentalTime::class)

// Pure builders for the chat system prompt. Every input is passed explicitly — no DI,
// no suspend, no resource loading, no Clock — so tests can call `buildChatSystemPrompt`
// directly with hand-crafted inputs. Section composition is controlled by
// `SystemPromptVariant`; each `if (variant == ...)` block is the single source of
// truth for where a section belongs. No post-hoc regex stripping.

package com.pusios.pots.data

import com.pusios.pots.skills.SkillManifest
import kotlin.time.Instant

/**
 * Identifies which flavour of chat system prompt to build. Public because it's part of
 * [DataRepository.getActiveSystemPrompt]'s signature — callers pick the variant based on
 * whether they're dispatching to a remote or on-device service.
 */
enum class SystemPromptVariant {
    /** Full chat prompt for remote services — every section available. */
    CHAT_REMOTE,

    /**
     * Trimmed prompt for on-device LiteRT plain chat — only sections a 2-4B Gemma can
     * coherently attend to. Soul + basic memory guidance + runtime `## Context`. Drops
     * memory category dumps, scheduled tasks, Structured Learning guidance, and pots-ui
     * modes (the latter is also hidden from the UI for on-device services — see
     * `ChatScreen.kt`).
     */
    CHAT_LOCAL,
}

/** Runtime state rendered into the `## Context` section. */
internal data class ChatPromptRuntimeContext(
    /** Local-zoned ISO 8601 with explicit offset, e.g. `2026-04-22T22:32:39+02:00`. */
    val nowLocalIsoWithOffset: String,
    /** IANA zone id, e.g. `Europe/Berlin`. Rendered next to the local time for clarity. */
    val timeZoneId: String,
    /** UTC ISO 8601 with `Z` suffix — kept so the model can double-check the offset. */
    val nowUtcIsoString: String,
    val platform: String,
    val modelId: String,
    val providerName: String,
)

/** Which pots-ui section, if any, to render. */
internal enum class ChatPromptUiMode { NONE, DYNAMIC_UI, INTERACTIVE_UI }

/**
 * Shared shape for rendering a connected email account into a prompt section — used by
 * both the chat `## Email Accounts` block and the heartbeat `## Email Status` block.
 * Carries enough context for the AI to reason about account state (unread, last sync,
 * last error). Message bodies/previews don't belong here; those are surfaced separately
 * by the heartbeat's `## New Emails` section or fetched via the email-reading tools.
 */
internal data class EmailAccountSummary(
    val email: String,
    val unreadCount: Int,
    val lastSyncEpochMs: Long,
    val lastError: String? = null,
)

/**
 * Total character budget for the memory category sections (`## Your Memories`, etc.)
 * when building the `CHAT_LOCAL` variant. Memories are appended in order — general →
 * preferences → learnings → errors — and entries that would push the combined size
 * past this budget are dropped silently at the entry boundary. Set generously enough
 * to cover a typical user's memory set (~20-40 entries) without starving the model's
 * attention budget on a 16K-context local model.
 */
private const val LOCAL_MEMORY_BUDGET_CHARS = 2_000

/**
 * One-line honesty guard composed into every chat variant. Targets the two observed
 * failure modes: (1) the model inventing tool outputs / file contents instead of
 * admitting a tool returned nothing readable, and (2) pots-ui buttons whose labels
 * imply operations the callback can't actually perform (downloads, exports, etc.).
 * No header — a single sentence doesn't earn a `##` section.
 */
internal const val DEFAULT_HONESTY_RULE =
    "Do not fabricate tool outputs, file contents, citations, or completed work."

/** Language and clarity policy shared by every persona and chat variant. */
internal const val DEFAULT_LANGUAGE_SECTION =
    "## Язык и ясность\n" +
        "По умолчанию отвечай на естественном, понятном русском языке; если пользователь явно пишет на другом языке или просит его — отвечай на нём. " +
        "Объясняй специальные термины простыми словами, сначала давай вывод или следующий шаг, затем только нужные детали. " +
        "Не скрывай существенные оговорки, но не перегружай ответ."

/**
 * Universal tool-use policy composed into every chat variant. Lives as its own constant
 * (not in the soul string) so it survives user customization of the soul — the same
 * reasoning as [DEFAULT_HONESTY_RULE]. Has a `##` header because it's three sentences
 * of addressable policy, not a single inline rule.
 */
internal const val DEFAULT_TOOL_USE_SECTION =
    "## Tool Use\n" +
        "Use tools to verify work and resolve ambiguity. " +
        "Don't ask the user for lookups you can do yourself. " +
        "Check for a tool before saying a capability is unavailable. " +
        "Summarize noisy output and state any uncertainty — don't dump raw logs."

/**
 * Universal acting-vs-clarifying policy composed into every chat variant. Caps the
 * model's tendency to ask multiple clarifying questions and to give up after the first
 * failed attempt. Constant rather than soul-embedded so soul customization can't drop it.
 */
internal const val DEFAULT_ACTING_SECTION =
    "## When to Act\n" +
        "Take the most reasonable interpretation and proceed. " +
        "Ask at most one clarifying question, only when genuinely blocked. " +
        "If a first attempt fails, try another approach or explain the blocker. " +
        "See work through to a usable result."

/**
 * Voice output guidance. Composed into every chat variant: the agent's spoken
 * replies (and HandsFree dialogue) must stay pronounceable, and emotions are
 * expressed through the character rather than through glyphs.
 */
internal const val DEFAULT_VOICE_OUTPUT_SECTION =
    "## Voice Output\n" +
        "When a reply will be spoken aloud (including in HandsFree mode), write only " +
        "speakable text: no emojis, emoticons, markdown, URLs, code or other symbols the " +
        "text-to-speech engine cannot pronounce, unless they are part of an interactive UI " +
        "element in the response. For ordinary dialogue keep the text plain. " +
        "To convey emotion or emphasis while speaking, trigger a Potsik animation " +
        "(the potsik tool, action=play_animation) instead of emojis; a single animation " +
        "or a short sequence or loop is fine. " +
        "When the user says they are done, goodbye, disconnect or hang up, end the " +
        "HandsFree dialogue after answering."

/**
 * Advanced memory guidance — references `memory_learn` (not in `LOCAL_TOOL_ALLOWLIST`)
 * and `memory_reinforce`. Only composed into the `CHAT_REMOTE` variant; the on-device
 * variant omits it entirely because small Gemma models can't reliably call
 * `memory_learn` (4 params + enum).
 */
internal const val DEFAULT_STRUCTURED_LEARNING_SECTION =
    "## Structured Learning\n" +
        "Use memory_learn to record categorized learnings:\n" +
        "- Record user corrections and preferences as PREFERENCE entries\n" +
        "- Record things that worked well as LEARNING entries\n" +
        "- Record error resolutions as ERROR entries\n" +
        "Use memory_reinforce when a stored learning produced a good outcome."

/**
 * Teaches the model how the two automation mechanisms differ. Only composed into the
 * `CHAT_REMOTE` variant — scheduling tools aren't in the local allowlist, and the
 * heartbeat-is-user-controlled rule doesn't matter on-device. Placed before the
 * Scheduled Tasks data dump so the guidance precedes any rendered tasks.
 */
internal const val DEFAULT_AUTOMATION_SECTION =
    "## Automation\n" +
        "Every form of \"run something without the user typing it\" goes through `schedule_task`. " +
        "The tool has three mutually exclusive triggers:\n" +
        "- `execute_at` — one-off at a specific datetime (reminders, \"check back at 3pm\").\n" +
        "- `cron` — recurring on a schedule (\"every morning at 8\", \"every 15 minutes\").\n" +
        "- `on_heartbeat: true` — appended to every heartbeat self-check. Use this when the user asks for *standing* heartbeat behaviour (e.g. \"greet me on every heartbeat\", \"always summarize new emails\", \"flag overdue tasks each check\"). These are `HEARTBEAT` trigger tasks and show up in `list_tasks` alongside time/cron tasks.\n" +
        "Each scheduled or heartbeat run starts fresh, so embed any context the prompt needs. Use `list_tasks` / `cancel_task` to inspect or remove.\n" +
        "Heartbeat itself (on/off toggle, interval, active hours) is user-controlled in Settings → Agent → Heartbeat — you cannot enable, disable, or reschedule it. If the user asks for recurring updates and heartbeat seems off, either schedule a cron task or tell them to enable Heartbeat in settings — never claim to have \"enabled\" or \"turned on\" heartbeat." + "\n" +
        "Language rule for reminders: whenever you call schedule_task, write `description` (the text shown to the user in the task list and notifications) in the language the user is speaking in — Russian for Russian requests, even if you are answering other parts in English. `prompt` is for your future self and should follow the same rule; the firing prompt should ask the user in their language. Never silently English a reminder the user gave in Russian."

/**
 * Composes the full chat system prompt for the given [variant].
 *
 * Returns an empty string when there is literally nothing to render (which the caller
 * should map to `null`). All inputs are passed explicitly — memory lists are pre-split
 * by the caller so this function doesn't touch the `MemoryStore`.
 */
internal fun buildChatSystemPrompt(
    variant: SystemPromptVariant,
    soul: String,
    hasTools: Boolean,
    memoryEnabled: Boolean,
    schedulingEnabled: Boolean,
    memoryInstructions: String?,
    generalMemories: List<MemoryEntry>,
    preferenceMemories: List<MemoryEntry>,
    learningMemories: List<MemoryEntry>,
    errorMemories: List<MemoryEntry>,
    pendingTasks: List<ScheduledTask>,
    heartbeatAdditions: List<ScheduledTask>,
    emailAccounts: List<EmailAccountSummary>,
    runtime: ChatPromptRuntimeContext,
    uiMode: ChatPromptUiMode,
    activeSkill: SkillManifest? = null,
): String = buildString {
    append(soul)

    if (isNotEmpty()) append("\n\n")
    append(DEFAULT_HONESTY_RULE)

    if (isNotEmpty()) append("\n\n")
    append(DEFAULT_LANGUAGE_SECTION)

    // Tool-use policy only renders when the model is actually being given tools. With every
    // tool disabled (or a model that doesn't support tool calls) "use tools to verify work"
    // is noise that misleads the model. When-to-act is a general behavioral fundamental and
    // renders regardless, same rationale as the honesty rule.
    if (hasTools) {
        if (isNotEmpty()) append("\n\n")
        append(DEFAULT_TOOL_USE_SECTION)
    }
    if (isNotEmpty()) append("\n\n")
    append(DEFAULT_ACTING_SECTION)

    if (isNotEmpty()) append("\n\n")
    append(DEFAULT_VOICE_OUTPUT_SECTION)

    if (!memoryInstructions.isNullOrEmpty()) {
        if (isNotEmpty()) append("\n\n")
        append(memoryInstructions)
    }

    // Structured Learning references memory_learn / memory_reinforce, so it only makes sense
    // when memory is enabled (those tools are absent otherwise). Remote-only — memory_learn
    // isn't in the local allowlist.
    if (variant == SystemPromptVariant.CHAT_REMOTE && memoryEnabled) {
        if (isNotEmpty()) append("\n\n")
        append(DEFAULT_STRUCTURED_LEARNING_SECTION)
    }

    // Memory category sections are emitted for BOTH variants. memory_store / memory_forget /
    // memory_reinforce are in the local allowlist, and memories may have been learned via
    // remote models — the local model should be able to reference them. A char-count budget
    // (unlimited for remote; [LOCAL_MEMORY_BUDGET_CHARS] for local) prevents runaway growth
    // on small on-device context windows.
    val memoryBudget = when (variant) {
        SystemPromptVariant.CHAT_REMOTE -> Int.MAX_VALUE
        SystemPromptVariant.CHAT_LOCAL -> LOCAL_MEMORY_BUDGET_CHARS
    }
    var remaining = memoryBudget
    remaining = appendMemoryCategorySection("Your Memories", generalMemories, withHitCount = false, remaining)
    remaining = appendMemoryCategorySection("User Preferences", preferenceMemories, withHitCount = false, remaining)
    remaining = appendMemoryCategorySection("Learnings", learningMemories, withHitCount = true, remaining)
    appendMemoryCategorySection("Known Issues & Resolutions", errorMemories, withHitCount = false, remaining)

    // Automation guidance + Email Accounts + Scheduled Tasks stay remote-only — the
    // matching tools aren't in the local allowlist. The Automation guidance references
    // schedule_task / list_tasks / cancel_task, so it only renders when scheduling is
    // enabled (those tools are absent otherwise). Email Accounts is independent of
    // scheduling; the data dumps only render when non-empty.
    if (variant == SystemPromptVariant.CHAT_REMOTE) {
        if (schedulingEnabled) {
            if (isNotEmpty()) append("\n\n")
            append(DEFAULT_AUTOMATION_SECTION)
        }
        if (emailAccounts.isNotEmpty()) {
            appendEmailAccountsSection(emailAccounts)
        }
        if (pendingTasks.isNotEmpty()) {
            appendScheduledTasksSection(pendingTasks)
        }
        if (heartbeatAdditions.isNotEmpty()) {
            appendHeartbeatAdditionsSection(heartbeatAdditions)
        }
    }

    if (activeSkill != null) {
        appendActiveSkillSection(activeSkill)
    }

    appendContextSection(runtime)

    if (variant == SystemPromptVariant.CHAT_REMOTE) {
        when (uiMode) {
            ChatPromptUiMode.DYNAMIC_UI -> appendDynamicUiSection()
            ChatPromptUiMode.INTERACTIVE_UI -> appendInteractiveUiSection()
            ChatPromptUiMode.NONE -> {}
        }
    }
}

/**
 * Active skill block. Only emitted when the user prefixed the current turn's
 * message with `/skill-id`, so the body stays out of the prompt on every other
 * turn — matches the lean-prompt rule of "disabled features add zero bytes."
 *
 * On Android, the skill's bundled files live at `~/skills/<id>/` inside the
 * Linux sandbox, accessible via the existing `execute_shell_command` tool.
 */
private fun StringBuilder.appendActiveSkillSection(skill: SkillManifest) {
    append("\n\n## Active skill: ")
    append(skill.displayName)
    append('\n')
    append(skill.body.trim())
    if (skill.bundledFilePaths.isNotEmpty()) {
        append("\n\nBundled files (available at `~/skills/")
        append(skill.id)
        append("/` in the Linux sandbox):\n")
        for (path in skill.bundledFilePaths.sorted()) {
            append("- ")
            append(path)
            append('\n')
        }
    }
}

/**
 * Appends a memory category section subject to a char budget. Entries are added one by
 * one until the next entry would push the section past [remainingBudget]; remaining
 * entries are dropped silently. If no entries fit, the header is not emitted either.
 *
 * Returns the remaining budget after emission so the caller can thread it through the
 * next category section. [Int.MAX_VALUE] means unlimited (no truncation).
 */
private fun StringBuilder.appendMemoryCategorySection(
    header: String,
    entries: List<MemoryEntry>,
    withHitCount: Boolean,
    remainingBudget: Int,
): Int {
    if (entries.isEmpty() || remainingBudget <= 0) return remainingBudget

    val section = StringBuilder()
    section.append("\n\n## ").append(header).append("\n")
    var included = 0
    for (entry in entries) {
        val entryStart = section.length
        section.append("- **").append(entry.key).append("**")
        if (withHitCount) {
            section.append(" (reinforced ").append(entry.hitCount).append("x)")
        }
        section.append(": ").append(entry.content).append('\n')
        if (section.length > remainingBudget) {
            // This entry pushed us over. Revert it and stop.
            section.setLength(entryStart)
            break
        }
        included++
    }
    if (included == 0) {
        // Not even the first entry fit — don't emit the header alone.
        return remainingBudget
    }
    append(section)
    return (remainingBudget - section.length).coerceAtLeast(0)
}

private fun StringBuilder.appendEmailAccountsSection(accounts: List<EmailAccountSummary>) {
    append("\n\n## Email Accounts\n")
    append("The user has these email accounts connected. Use them via the existing email tools — ")
    append("do NOT suggest adding, re-authenticating, or connecting a new account unless the user explicitly asks.\n")
    append("**Sending policy**: before calling `compose_email` or `reply_email`, present the full draft (to, subject, body) in chat and get explicit confirmation (\"send it\" / \"looks good\" / \"yes\"). Never call the send tools on the same turn you draft — the user must have a chance to correct tone, recipients, or content first. If the user later says \"change X and send\", re-present the updated draft and confirm again.\n")
    for (account in accounts) {
        append("- **")
        append(account.email)
        append("**: ")
        if (account.lastError != null) {
            append("sync failing — ")
            append(account.lastError)
        } else {
            append(account.unreadCount)
            append(" unread")
            if (account.lastSyncEpochMs > 0) {
                append(" (last sync: ")
                append(Instant.fromEpochMilliseconds(account.lastSyncEpochMs))
                append(')')
            }
        }
        append('\n')
    }
}

private fun StringBuilder.appendHeartbeatAdditionsSection(additions: List<ScheduledTask>) {
    append("\n\n## Heartbeat Additions\n")
    append("Standing instructions the user has set to run on every heartbeat (trigger=HEARTBEAT). Don't duplicate these when the user asks for similar behaviour; cancel via `cancel_task` if they want one removed.\n")
    for (t in additions) {
        append("- **")
        append(t.description)
        append("** (id: ")
        append(t.id)
        append("): ")
        append(t.prompt)
        append('\n')
    }
}

private fun StringBuilder.appendScheduledTasksSection(pendingTasks: List<ScheduledTask>) {
    append("\n\n## Scheduled Tasks\n")
    for (t in pendingTasks) {
        append("- **")
        append(t.description)
        append("** (id: ")
        append(t.id)
        append(", scheduled: ")
        append(t.scheduledAt)
        append(")")
        if (t.cron != null) {
            append(" [cron: ")
            append(t.cron)
            append("]")
        }
        append('\n')
    }
}

private fun StringBuilder.appendContextSection(runtime: ChatPromptRuntimeContext) {
    append("\n\n## Context\n")
    // Lead with local time so the model anchors on the user's wall clock when computing
    // relative times ("in 3 minutes", "tomorrow at 9"). Tools that accept a naive datetime
    // (e.g. `schedule_task`'s `execute_at`) interpret it in this zone.
    append("- Local time: ")
    append(runtime.nowLocalIsoWithOffset)
    append(" (")
    append(runtime.timeZoneId)
    append(")\n")
    append("- UTC: ")
    append(runtime.nowUtcIsoString)
    append('\n')
    append("- Platform: ")
    append(runtime.platform)
    append('\n')
    append("- Model: ")
    append(runtime.modelId)
    append('\n')
    append("- Provider: ")
    append(runtime.providerName)
    append('\n')
}

private fun StringBuilder.appendDynamicUiSection() {
    append("\n## Dynamic UI\n")
    append("Когда есть 2–5 очевидных вариантов или нужен короткий ответ «да/нет», предложи уместные кнопки в конце ответа: например, подтвердить/отклонить черновик, выбрать вариант или следующий шаг. Делай это только когда кнопки ускоряют действие; не превращай каждый ответ в опрос. Для внешнего или необратимого действия сначала получи явное подтверждение. Кнопка callback лишь передаёт выбор пользователя и сама не выполняет действие — после выбора используй нужный инструмент и сообщи результат. Для черновика письма покажи полный черновик до кнопок «Отправить», «Изменить», «Отмена»; отправляй только после явного подтверждения. Пиши подписи кнопок коротко и по-русски.\n\n")
    append("You can enhance your chat responses with interactive UI elements using pots-ui blocks. ")
    append("Proactively use them whenever you need input from the user — don't just ask in plain text if a form, selector, or buttons would be more natural. ")
    append("For example, if the user asks you to help plan a trip, present destination options as buttons; if you need preferences, show a form; if presenting choices, use interactive cards. ")
    append("Use pots-ui whenever collecting data, offering choices, presenting structured information, or guiding multi-step workflows. ")
    append("You can mix pots-ui blocks with regular markdown text naturally — use markdown for explanations and pots-ui for interactive elements.\n\n")
    append(POTS_UI_COMPONENT_CATALOG)
    append("Layout tips:\n")
    append("- Put buttons INSIDE cards, directly below related content — never group all buttons separately at the bottom\n")
    append("- Use rows for groups of buttons or chips — rows wrap automatically, so any number of items is fine\n")
    append("- Keep button labels short (1-3 words)\n\n")
    append("Example:\n```pots-ui\n{\"type\":\"column\",\"children\":[{\"type\":\"text\",\"value\":\"Your name?\",\"style\":\"title\"},{\"type\":\"text_input\",\"id\":\"name\",\"placeholder\":\"Enter name\"},{\"type\":\"button\",\"label\":\"Submit\",\"action\":{\"type\":\"callback\",\"event\":\"submit\",\"collectFrom\":[\"name\"]}}]}\n```\n")
}

private fun StringBuilder.appendInteractiveUiSection() {
    append("\n## Interactive UI Mode (ACTIVE)\n")
    append("You are in full-screen interactive UI mode. The user ONLY sees rendered pots-ui components — they cannot see markdown, plain text, or anything outside a pots-ui fence.\n")
    append("Your ENTIRE response must be a single ```pots-ui code fence. No text before it, no text after it, no markdown. If you write anything outside the fence, the user will NOT see it.\n\n")
    append(POTS_UI_COMPONENT_CATALOG)
    append("Rules:\n")
    append("- Each response is a COMPLETE screen layout. Include all content and actions in one pots-ui block.\n")
    append("- Always include clear primary action buttons so the user can proceed.\n")
    append("- Every screen MUST have at least one interactive element with a callback action (button, countdown with expiry action, etc.). A screen without any callback is a dead end the user cannot proceed from.\n")
    append("- Use headline text for screen titles. Structure screens with cards for grouping related content.\n")
    append("- Use descriptive callback events (e.g., \"select_destination\", \"submit_form\") so you understand what the user selected.\n")
    append("- Do NOT include back buttons, navigation bars, or any navigation controls. The app provides a back button and close button in the toolbar. The user can also type instructions in a text field below your UI.\n")
    append("Layout:\n")
    append("- Put buttons INSIDE cards, directly below related content — never group all buttons separately at the bottom\n")
    append("- Use rows for groups of buttons or chips — rows wrap automatically, so any number of items is fine\n")
    append("- Keep button labels short (1-3 words)\n")
    append("- Use columns for vertical flow. Use the full component set: tabs, accordion, alerts, progress, chips, icons, etc.\n\n")
    append("Limitations — respect these strictly:\n")
    append("- The UI is static once rendered. NEVER show loading, fetching, or verifying states. You cannot fetch data or run operations asynchronously. Present all content immediately.\n")
    append("- Never use indeterminate progress (progress without a value) or text like \"Loading...\", \"Fetching...\", \"Verifying...\" as if something will happen later — nothing will.\n")
    append("- A screen is standalone unless it uses store_put/store_delete and a records node. Those persist to the mini-app's SQLite store and survive leaving the app; ordinary form values still live only until the next response.\n")
    append("- When the user asks for an app with data (lists, trackers, notes, counters), design the collections with mini_app_data first, then render a records node and store_put/store_delete actions.\n")
    append("- Only use the exact components and properties defined above. Do not invent attributes, component types, or behaviors that are not listed. If a component doesn't support a feature, do not pretend it does.\n")
    append("- Start with simple, clean layouts. A well-structured screen with a few cards and clear actions is better than a complex layout that pushes the component set beyond its capabilities.\n")
    append("- When unsure whether something will work, use a simpler approach. A working simple screen is always better than a broken ambitious one.\n\n")
    append("Example:\n```pots-ui\n{\"type\":\"column\",\"children\":[{\"type\":\"text\",\"value\":\"Welcome\",\"style\":\"headline\"},{\"type\":\"card\",\"children\":[{\"type\":\"text\",\"value\":\"What would you like to do?\",\"style\":\"title\"},{\"type\":\"button\",\"label\":\"Get Started\",\"action\":{\"type\":\"callback\",\"event\":\"get_started\"}}]}]}\n```\n")
}

/**
 * Pre-computed pots-ui component catalog text — shared between [appendDynamicUiSection]
 * and [appendInteractiveUiSection]. Pre-building the ~3KB of static text once (instead
 * of re-running ~30 `append` calls per message) is the main reason this is a top-level
 * val rather than a helper function.
 */
private val POTS_UI_COMPONENT_CATALOG: String = buildString {
    append("Format: wrap a JSON object in ```pots-ui fences.\n\n")
    append("Components: column, row, card, box, text, button, text_input, checkbox, switch, select, radio_group, slider, chip_group, table, list, records, divider, image, icon, code, progress, countdown, alert, tabs, accordion, quote, badge, stat, avatar.\n")
    append("- text: {\"type\":\"text\",\"value\":\"...\",\"style\":\"headline|title|body|caption\",\"bold\":true,\"color\":\"primary|secondary|error\"} — do NOT use markdown formatting (**, *, #, etc.) in text values; use the bold/italic/style properties instead\n")
    append("- button: {\"type\":\"button\",\"label\":\"...\",\"action\":{...},\"variant\":\"filled|outlined|text|tonal\"}\n")
    append("- text_input: {\"type\":\"text_input\",\"id\":\"...\",\"label\":\"...\",\"placeholder\":\"...\",\"value\":\"...\"}\n")
    append("- checkbox: {\"type\":\"checkbox\",\"id\":\"...\",\"label\":\"...\",\"checked\":false}\n")
    append("- switch: {\"type\":\"switch\",\"id\":\"...\",\"label\":\"...\",\"checked\":false}\n")
    append("- select: {\"type\":\"select\",\"id\":\"...\",\"label\":\"...\",\"options\":[\"A\",\"B\"],\"selected\":\"A\"}\n")
    append("- radio_group: {\"type\":\"radio_group\",\"id\":\"...\",\"label\":\"...\",\"options\":[\"A\",\"B\"],\"selected\":\"A\"}\n")
    append("- slider: {\"type\":\"slider\",\"id\":\"...\",\"label\":\"...\",\"value\":50,\"min\":0,\"max\":100,\"step\":10}\n")
    append("- chip_group: {\"type\":\"chip_group\",\"id\":\"...\",\"chips\":[{\"label\":\"Tag\",\"value\":\"tag\"}],\"selection\":\"single|multi|none\"} — selection mode: \"single\" (default, one at a time), \"multi\" (any number), or \"none\" (display-only tags, no interaction, id not needed). For \"single\" and \"multi\" a button must collectFrom the chip_group id to send the selection.\n")
    append("- list: {\"type\":\"list\",\"items\":[...],\"ordered\":false} — bullet (or numbered) list; the app renders bullets/numbers automatically, so do NOT include bullet characters (•, -, *) or numbering in item text\n")
    append("- table: {\"type\":\"table\",\"headers\":[\"Col1\",\"Col2\"],\"rows\":[[\"a\",\"b\"]]} — columns share equal width; keep to 3-4 columns max on mobile, use short cell values\n")
    append("- records: {\"type\":\"records\",\"collection\":\"tasks\",\"title\":\"Tasks\",\"fields\":[\"title\",\"done\"],\"emptyText\":\"Nothing yet\",\"recordAction\":{\"type\":\"store_delete\",\"collection\":\"tasks\",\"recordId\":\"{{record.id}}\"},\"recordActionLabel\":\"Delete\"} — renders saved records; fields are the keys to show, empty means all keys; recordAction is optional per row.\n")
    append("- icon: {\"type\":\"icon\",\"name\":\"home|settings|search|add|delete|edit|check|check_circle|close|arrow_back|arrow_forward|star|favorite|share|info|warning|person|group|mail|phone|calendar|location|refresh|menu|more|send|notifications|trending_up|trending_down|trending_flat|thumb_up|thumb_down|visibility|lock|shopping_cart|play|pause|stop|download|upload|cloud|attachment|link|code|terminal|build|bug|lightbulb|science|school|work|account_circle|language|translate|dark_mode|light_mode|bolt|rocket|money|credit_card|receipt|inventory|category|dashboard|analytics|chart|pie_chart|show_chart|timer|alarm|task|bookmark|flag|tag|pin|copy|paste|cut|undo|redo|filter|sort|swap|sync|wifi|bluetooth|battery|speed|shield|verified|health|fitness|food|coffee|airplane|hotel|car|earth|map|compass|pet|leaf|water|weather|party|trophy|medal|premium\",\"size\":24,\"color\":\"primary|secondary|error\"} — you can also use any emoji as the name (e.g. \"name\":\"⚔️\" or \"name\":\"🗺️\"); prefer emojis when they better convey the meaning than the generic Material icons\n")
    append("- code: {\"type\":\"code\",\"code\":\"...\",\"language\":\"kotlin\"} — a copy-to-clipboard icon is rendered automatically; do NOT add your own copy button next to it.\n")
    append("- progress: {\"type\":\"progress\",\"value\":0.5,\"label\":\"50%\"} (always provide a value 0.0-1.0 to show a determinate bar; do NOT omit value to fake a loading state)\n")
    append("- countdown: {\"type\":\"countdown\",\"seconds\":300,\"label\":\"Time left\",\"action\":{\"type\":\"callback\",\"event\":\"timer_done\"}} (seconds is relative duration from render; action is optional, fires on expiry)\n")
    append("- alert: {\"type\":\"alert\",\"message\":\"...\",\"title\":\"...\",\"severity\":\"info|success|warning|error\"}\n")
    append("- tabs: {\"type\":\"tabs\",\"tabs\":[{\"label\":\"Tab 1\",\"children\":[...]},{\"label\":\"Tab 2\",\"children\":[...]}],\"selectedIndex\":0}\n")
    append("- accordion: {\"type\":\"accordion\",\"title\":\"...\",\"children\":[...],\"expanded\":false}\n")
    append("- box: {\"type\":\"box\",\"children\":[...],\"contentAlignment\":\"center|top_start|top_center|top_end|center_start|center_end|bottom_start|bottom_center|bottom_end\"}\n")
    append("- quote: {\"type\":\"quote\",\"text\":\"...\",\"source\":\"Author Name\"} — blockquote with accent border\n")
    append("- badge: {\"type\":\"badge\",\"value\":\"3\",\"color\":\"primary|secondary|error\"} — small colored pill for counts or status\n")
    append("- stat: {\"type\":\"stat\",\"value\":\"\$1,234\",\"label\":\"Revenue\",\"description\":\"12% increase\"} — large metric display\n")
    append("- image: {\"type\":\"image\",\"url\":\"https://... or content://...\",\"alt\":\"...\",\"height\":160,\"aspectRatio\":1.33} — displays an image from a URL or content URI\n")
    append("- avatar: {\"type\":\"avatar\",\"name\":\"John Doe\",\"imageUrl\":\"https://...\",\"size\":40} — circular image or initials (24-80dp)\n\n")
    append("Actions (on buttons, countdown expiry):\n")
    append("- callback: {\"type\":\"callback\",\"event\":\"event_name\",\"data\":{\"key\":\"val\"},\"collectFrom\":[\"input_id1\",\"input_id2\"]} — collects input values and sends them back as a user message (e.g. \"Pressed: event_name\" or \"Responded with: key: value\"). You then reply with text or more UI. Use callbacks for: collecting choices, submitting forms, navigating between steps, confirming actions. Do NOT create callback buttons that imply operations you cannot perform — callbacks only send a message, they do not trigger system actions like printing, file export, or downloads.\n")
    append("- toggle: {\"type\":\"toggle\",\"targetId\":\"element_id\"} — shows/hides an element locally\n")
    append("- open_url: {\"type\":\"open_url\",\"url\":\"https://...\"}\n")
    append("- copy_to_clipboard: {\"type\":\"button\",\"action\":{\"type\":\"copy_to_clipboard\",\"text\":\"...\"}} — renders as a clipboard icon button; omit the button label. Offer next to copyable content like snippets, commands, or tokens.\n\n")
    append("- store_put: {\"type\":\"store_put\",\"collection\":\"tasks\",\"recordId\":\"{{form.id}}\",\"collectFrom\":[\"title\",\"done\"],\"navigateTo\":\"screen title\"} — writes a record into the app's persistent store. Values come from collectFrom plus the literal data map.\n")
    append("- store_delete: {\"type\":\"store_delete\",\"collection\":\"tasks\",\"recordId\":\"{{record.id}}\"} — deletes one record, or the whole collection when recordId is absent.\n")
    append("- navigate: {\"type\":\"navigate\",\"screenId\":\"screen title\"} — switches to another saved screen without an AI round-trip.\n")
    append("- host_call: {\"type\":\"host_call\",\"service\":\"calendar.list\",\"params\":{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-31T00:00:00Z\"},\"storeTo\":\"events\"} — runs a host service without an LLM and writes its result into a collection, where a records node can show it. Services: calendar.list, calendar.create, files.list, files.read, files.write, files.delete, notes.list, notes.write. List services clear storeTo first.\n")
    append("Persistent mini-apps:\n")
    append("- Saved apps keep their data in SQLite. The mini_app_data tool designs it: action=declare_collection with collection + fields (e.g. 'title:string, due:date, done:boolean'), action=insert/query/delete/clear for records, action=list_collections to inspect. Omit app_id while building; pass it for a saved app.\n")
    append("- Put a records node on the screen whenever the user should see saved rows, and a store_put button next to the form that creates them. A callback action sends a message to you; store_put/store_delete/navigate/host_call work offline in the saved app.\n")
    append("- For a native app that reads the calendar or files, use host_call with storeTo: the action fills a collection, and a records node on the same screen displays it. Do not use a callback for that, because callbacks need the LLM.\n")
    append("- Saved apps run without a model. A callback only reaches the agent if the app declares the 'llm' capability; otherwise prefer store_put/store_delete/navigate/host_call.\n")
    append("- action=declare_automation adds a standing task (title, prompt, optional cron); action=set_capabilities records what the app may use (calendar, notes, files, notifications, scheduling).\n")
    append("- Form inputs (text_input, checkbox, switch, select, radio_group, slider, chip_group) only store state locally. Their values are ONLY sent when a button's collectFrom includes their id. Always pair form inputs with a submit button that collects from them.\n\n")
}
