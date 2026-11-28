package com.pusios.pots.data

import com.pusios.pots.email.EmailPoller
import com.pusios.pots.getBackgroundDispatcher
import com.pusios.pots.isEmailSupported
import com.pusios.pots.isSmsSupported
import com.pusios.pots.network.ConfiguredInstanceNotFoundException
import com.pusios.pots.network.NoConfiguredServiceException
import com.pusios.pots.network.OpenAICompatibleConnectionException
import com.pusios.pots.network.ProviderFailureReason
import com.pusios.pots.network.isRetryableProviderFailure
import com.pusios.pots.network.providerFailureReason
import com.pusios.pots.sendHeartbeatNotification
import com.pusios.pots.sms.SmsPoller
import com.pusios.pots.ui.markdown.parseMarkdown
import com.pusios.pots.ui.markdown.toSpeakableText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@OptIn(ExperimentalTime::class)
class TaskScheduler(
    private val dataRepository: DataRepository,
    private val taskStore: TaskStore? = null,
    private val appSettings: AppSettings? = null,
    private val heartbeatManager: HeartbeatManager? = null,
    private val emailStore: EmailStore? = null,
    private val emailPoller: EmailPoller? = null,
    private val smsStore: SmsStore? = null,
    private val smsPoller: SmsPoller? = null,
    private val notificationStore: NotificationStore? = null,
    private val enabled: Boolean = true,
    private val backgroundDispatcher: CoroutineContext = getBackgroundDispatcher(),
) {
    private companion object {
        /** Attempts against one instance, and only for failures a retry can plausibly fix. */
        const val PRIMARY_ATTEMPTS = 3

        /**
         * Wall clock one instance may spend failing before the fallback takes over. Without a
         * budget, three attempts on a hanging endpoint meant three full request timeouts —
         * minutes of silence on every heartbeat.
         */
        val PRIMARY_ATTEMPT_BUDGET = 90.seconds

        /** Backoff before retry n; short, because [PRIMARY_ATTEMPT_BUDGET] is the real bound. */
        val RETRY_BACKOFF = 2.seconds

        const val POLL_INTERVAL_MS = 60_000L
        const val MAX_BACKOFF_MS = 3_600_000L // 1 hour
        const val HEARTBEAT_CONTEXT_COUNT = 3

        /** Shown in the journal when the primary fails and no reserve is configured. */
        const val NO_FALLBACK_SUFFIX = "; no fallback model selected in Settings → Agent → Heartbeat"

        /**
         * Shown when the reserve points at the primary itself. Saying "no fallback selected"
         * there would be a lie: one is selected, it just cannot take over.
         */
        const val RESERVE_EQUALS_PRIMARY_SUFFIX = "; reserve equals the primary instance"

        /** Per-task execution log size — surfaced in the task details sheet. */
        const val MAX_TASK_LOG_ENTRIES = 10

        /**
         * Cap the notification body — Android's collapsed text cuts off around ~60
         * chars anyway, and the expanded BigTextStyle view is capped to keep the
         * notification panel tidy. The full response remains in the heartbeat
         * conversation, which opens when the user taps the notification.
         */
        const val HEARTBEAT_NOTIFICATION_PREVIEW_CHARS = 240
    }

    /** A successful run plus what the journal should say about it (e.g. that it fell back). */
    private class ProviderRun(val content: String, val note: String? = null)

    /**
     * Every instance the run could use is out. Carries the finished journal line, because only
     * the retry loop knows which instance failed and whether a fallback was configured.
     */
    private class ProviderRunFailedException(message: String) : Exception(message)

    /**
     * Process-lifetime scope. Decoupled from any caller's scope so scheduled tasks and
     * heartbeats keep firing when a short-lived caller (e.g. `ChatViewModel.viewModelScope`)
     * is cancelled — as long as the OS keeps the process alive (which on Android means
     * `DaemonService` holding a foreground notification).
     */
    private val schedulerScope = CoroutineScope(
        SupervisorJob() + backgroundDispatcher + CoroutineName("TaskScheduler"),
    )

    private var activeJob: Job? = null

    /**
     * Predicate the loop consults before executing a task, to avoid racing with an
     * in-flight foreground API call. Assigned by the UI layer (`ChatViewModel`) while it
     * is alive and reset to `{ false }` when it's cleared. Default = "nothing loading",
     * which is the right answer for the daemon-only path.
     */
    @Volatile
    var isLoadingCheck: () -> Boolean = { false }

    /**
     * Whether the app is currently in the foreground (the user can see the in-app banner).
     * On Android this mirrors `ProcessLifecycleOwner` — set true on the first Activity
     * start, false when all activities stop. Other platforms leave it at the default
     * false since their actuals for [sendHeartbeatNotification] are no-ops anyway.
     *
     * When a heartbeat produces a non-OK report and this is `false`, the scheduler
     * escalates to a push notification instead of relying on the (invisible) banner.
     */
    @Volatile
    var appInForeground: Boolean = false

    /**
     * Starts the scheduler loop on the internal long-lived scope. Idempotent — repeated
     * calls (e.g. from both `DaemonService.onCreate` and `ChatViewModel.init`) return
     * immediately if the loop is already running.
     */
    fun start() {
        if (!enabled || taskStore == null || appSettings == null) return
        if (activeJob?.isActive == true) return
        activeJob = schedulerScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS.milliseconds)
                if (!appSettings.isSchedulingEnabled()) continue

                val dueTasks = taskStore.getDueTasks()
                for (task in dueTasks) {
                    if (isLoadingCheck()) break

                    try {
                        runScheduledTask(task)
                    } catch (e: Exception) {
                        // Stopped mid-run (daemon shut down): leave the task due so it runs next
                        // time instead of recording a failure and advancing past this occurrence.
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        handleTaskFailure(task, formatException(e))
                    }
                }

                if (!isLoadingCheck() && heartbeatManager?.isHeartbeatDue() == true) {
                    runHeartbeat()
                }

                // Email polling
                if (!isLoadingCheck() && isEmailSupported && appSettings.isEmailEnabled() && emailStore != null) {
                    checkNewEmails { isLoadingCheck() }
                }

                // SMS polling — FOSS-only (gated on `isSmsSupported`, which is true only
                // when READ_SMS is declared in the merged manifest).
                if (!isLoadingCheck() && isSmsSupported && appSettings.isSmsEnabled() && smsStore != null && smsPoller != null) {
                    checkNewSms()
                }
            }
        }
    }

    /**
     * One due scheduled task: ask the model, post the answer into the Pulse conversation,
     * advance the schedule.
     *
     * The run's note (e.g. "fell back to … after Unauthorized …") goes into the task's own
     * execution log, the same way the Pulse journal records it — a scheduled run that quietly
     * switched models must not leave the owner guessing which model answered.
     */
    internal suspend fun runScheduledTask(task: ScheduledTask) {
        // Bind tool calls to the heartbeat conversation's sandbox session
        // — the scheduled-task result also lands in that conversation
        // (see `addAssistantMessage`), so routing its shell commands
        // there too keeps shell state coherent with the visible output.
        val taskConversationId = dataRepository.getOrCreateHeartbeatConversationId()
        val run = askWithRetry(
            prompt = task.prompt,
            primaryInstanceId = task.originInstanceId,
            fallbackInstanceId = heartbeatManager?.getConfig()?.heartbeatFallbackInstanceId,
            modelIdOverride = task.originModelId,
            conversationIdOverride = taskConversationId,
        )
        val response = run.content
        if (response.isNotBlank()) {
            val header = task.description.ifBlank { "Scheduled task" }
            dataRepository.addAssistantMessage("**$header**\n\n$response")
        }
        handleTaskCompletion(task, run.note)
    }

    /**
     * Runs one task immediately without touching its schedule or status — the "run now"
     * action, which has to work on paused and already-completed tasks too. The answer lands
     * in the heartbeat conversation exactly like an automatic run, and the execution log
     * records it, so the owner sees what the run produced.
     */
    suspend fun runTaskNow(task: ScheduledTask) {
        val startedAt = Clock.System.now()
        // Re-read by id: the sheet's copy may be older than a pause/resume the user just made,
        // and writing that copy back would silently undo it.
        val current = latestTask(task)
        try {
            val taskConversationId = dataRepository.getOrCreateHeartbeatConversationId()
            val run = askWithRetry(
                prompt = current.prompt,
                primaryInstanceId = current.originInstanceId,
                fallbackInstanceId = heartbeatManager?.getConfig()?.heartbeatFallbackInstanceId,
                modelIdOverride = current.originModelId,
                conversationIdOverride = taskConversationId,
            )
            if (run.content.isNotBlank()) {
                val header = current.description.ifBlank { "Scheduled task" }
                dataRepository.addAssistantMessage("**$header**\n\n${run.content}")
            }
            val now = Clock.System.now()
            // Read again at write time: a run can take minutes, and a pause pressed meanwhile
            // must survive the result write.
            val latest = latestTask(task)
            taskStore?.updateTask(
                latest.copy(
                    lastResult = "Run manually at $now",
                    recentExecutions = appendExecution(latest, success = true, message = run.note),
                    lastRunEpochMs = now.toEpochMilliseconds(),
                    runCount = latest.runCount + 1,
                ),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A manual run that failed has to say so in the task's own history — the sheet has
            // nowhere else to show it, and the user pressed the button expecting an answer.
            val reason = if (e is ProviderRunFailedException) e.message.orEmpty() else formatException(e)
            val latest = latestTask(task)
            taskStore?.updateTask(
                latest.copy(
                    lastResult = "Manual run failed at $startedAt: $reason",
                    recentExecutions = appendExecution(latest, success = false, message = reason),
                    lastRunEpochMs = startedAt.toEpochMilliseconds(),
                    runCount = latest.runCount + 1,
                ),
            )
        }
    }

    /** The task as the store holds it now, falling back to the caller's copy if it is gone. */
    private fun latestTask(task: ScheduledTask): ScheduledTask = taskStore?.getAllTasks()?.firstOrNull { it.id == task.id } ?: task

    /**
     * One run against the primary instance, then the configured fallback.
     *
     * Retries are decided by the failure class: a rejected key, a missing model or a malformed
     * response will not change on a second try, so those go straight to the fallback; only
     * transport failures (unreachable, timeout, rate limit) are repeated, within
     * [PRIMARY_ATTEMPT_BUDGET] of wall clock. Every failure is described for the journal —
     * reason, the provider's own words, and the instance it ran through.
     */
    private suspend fun askWithRetry(
        prompt: String,
        primaryInstanceId: String?,
        fallbackInstanceId: String?,
        modelIdOverride: String? = null,
        conversationIdOverride: String? = null,
    ): ProviderRun {
        val startedAt = TimeSource.Monotonic.markNow()
        var lastFailure: Exception? = null
        for (attempt in 1..PRIMARY_ATTEMPTS) {
            val remaining = PRIMARY_ATTEMPT_BUDGET - startedAt.elapsedNow()
            if (remaining <= Duration.ZERO) break
            try {
                // A hanging endpoint must not own the whole budget: the attempt is bounded by
                // what is left of it, so the fallback still gets its turn.
                val content = withTimeoutOrNull(remaining) {
                    dataRepository.askWithTools(
                        prompt = prompt,
                        instanceId = primaryInstanceId,
                        modelIdOverride = modelIdOverride,
                        conversationIdOverride = conversationIdOverride,
                    )
                } ?: throw OpenAICompatibleConnectionException(
                    reason = ProviderFailureReason.Timeout,
                    detail = "no answer within $PRIMARY_ATTEMPT_BUDGET",
                )
                return ProviderRun(content)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastFailure = e
                if (!e.isRetryableProviderFailure()) break
                val backoff = RETRY_BACKOFF * attempt
                if (startedAt.elapsedNow() + backoff >= PRIMARY_ATTEMPT_BUDGET) break
                delay(backoff)
            }
        }
        val primaryFailure = lastFailure ?: IllegalStateException("Model call failed")

        // Nothing is configured at all: a fallback would fail the same way, and the journal
        // line is already the whole answer — pointing at the fallback setting would mislead.
        val nothingConfigured = primaryFailure is NoConfiguredServiceException
        val reserveIsSameInstance = !fallbackInstanceId.isNullOrBlank() && fallbackInstanceId == primaryInstanceId
        val canFallBack = !nothingConfigured && !fallbackInstanceId.isNullOrBlank() && !reserveIsSameInstance
        if (!canFallBack) {
            val suffix = when {
                nothingConfigured -> ""
                reserveIsSameInstance -> RESERVE_EQUALS_PRIMARY_SUFFIX
                else -> NO_FALLBACK_SUFFIX
            }
            throw ProviderRunFailedException(failureLine(primaryFailure, primaryInstanceId) + suffix)
        }
        try {
            val content = dataRepository.askWithTools(
                prompt = prompt,
                instanceId = fallbackInstanceId,
                modelIdOverride = null,
                conversationIdOverride = conversationIdOverride,
            )
            return ProviderRun(
                content = content,
                note = "fell back to ${instanceLabel(fallbackInstanceId)} after ${failureLine(primaryFailure, primaryInstanceId)}",
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw ProviderRunFailedException(
                failureLine(primaryFailure, primaryInstanceId) + "; fallback " + failureLine(e, fallbackInstanceId),
            )
        }
    }

    /** Journal line for a failure: `<reason>: <provider detail> (<instance>)`. */
    private fun failureLine(e: Exception, instanceId: String?): String = when (e) {
        // Its message is already `<reason>: <detail>`, so prefixing the reason again would
        // print it twice ("Unreachable: Unreachable: connection refused").
        is OpenAICompatibleConnectionException -> describeFailure(e.reason, e.detail, instanceLabel(instanceId))

        // Already a full sentence about routing; a reason prefix would only add noise.
        is ConfiguredInstanceNotFoundException, is NoConfiguredServiceException -> e.message ?: e::class.simpleName.orEmpty()

        else -> describeFailure(e.providerFailureReason(), e.message, instanceLabel(instanceId))
    }

    /** `Provider / model` for the journal, so the owner knows which model to look at. */
    private fun instanceLabel(instanceId: String?): String? = serviceInstanceLabel(dataRepository.getServiceEntries(), instanceId)

    /**
     * Run one heartbeat cycle: build the prompt, call the AI, record the result, and
     * surface any non-OK response (in-app message + push notification when backgrounded).
     * Used by the scheduler loop's due-check and by [triggerHeartbeatNow] for user-pressed refresh.
     */
    private suspend fun runHeartbeat() {
        val manager = heartbeatManager ?: return
        val pendingEmails = emailStore?.getPending().orEmpty()
        val pendingSms = smsStore?.getPending().orEmpty()
        val pendingNotifications = notificationStore?.getPending().orEmpty()
        val config = manager.getConfig()
        try {
            val recentResponses = dataRepository.savedConversations.value
                .find { it.type == Conversation.TYPE_HEARTBEAT }
                ?.messages?.takeLast(HEARTBEAT_CONTEXT_COUNT)
                ?.map { it.content }
                ?: emptyList()
            val heartbeatPrompt = manager.buildHeartbeatPrompt(recentResponses, pendingEmails, pendingSms, pendingNotifications)
            // Resolve the heartbeat conversation id BEFORE the AI starts emitting
            // tool calls, so any execute_shell_command call binds to the heartbeat's
            // own persistent bash session rather than the chat the user happens to
            // be viewing right now.
            val heartbeatConversationId = dataRepository.getOrCreateHeartbeatConversationId()
            val run = askWithRetry(
                prompt = heartbeatPrompt,
                primaryInstanceId = config.heartbeatInstanceId,
                fallbackInstanceId = config.heartbeatFallbackInstanceId,
                conversationIdOverride = heartbeatConversationId,
            )
            val response = run.content
            manager.markHeartbeatExecuted()
            manager.recordHeartbeat(success = true, note = run.note)
            if (response.isNotBlank() && "HEARTBEAT_OK" !in response) {
                dataRepository.addAssistantMessage(response)
                // Push-notify only when the user won't see the in-app banner.
                // Tapping the notification deep-links into the heartbeat
                // conversation via `EXTRA_OPEN_HEARTBEAT` (Android actual).
                // Strip markdown + pots-ui fences before sending to the tray —
                // the notification surface can't render them and raw fence
                // text (```pots-ui {...}```) is unreadable.
                if (!appInForeground) {
                    val preview = truncateForNotification(
                        parseMarkdown(response).toSpeakableText(),
                    )
                    if (preview.isNotBlank()) {
                        sendHeartbeatNotification(
                            title = "Pots heartbeat",
                            body = preview,
                        )
                    }
                }
            }
            // Only clear the snapshot we actually showed to the AI — messages
            // that arrived during the call stay pending for the next heartbeat.
            if (pendingEmails.isNotEmpty()) {
                emailStore?.let { store ->
                    store.removePending(pendingEmails)
                    // Advance the per-account delivery watermark so the user's
                    // next `check_email` call won't re-surface the same UIDs
                    // the heartbeat just summarised.
                    val maxUidByAccount = pendingEmails
                        .groupBy { it.accountId }
                        .mapValues { (_, msgs) -> msgs.maxOf { it.uid } }
                    for ((accId, maxUid) in maxUidByAccount) {
                        val current = store.getSyncState(accId)
                        if (maxUid > current.lastSeenUid) {
                            store.updateSyncState(current.copy(lastSeenUid = maxUid))
                        }
                    }
                }
            }
            if (pendingSms.isNotEmpty()) {
                smsStore?.removePending(pendingSms)
            }
            if (pendingNotifications.isNotEmpty()) {
                notificationStore?.removePending(pendingNotifications)
            }
            // Sweep retention bounds opportunistically after each heartbeat run.
            notificationStore?.sweep()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // The journal is the owner's only window into a background failure: name the reason
            // and the instance, not the exception class. A run that exhausted every configured
            // instance arrives already described (ProviderRunFailedException).
            val description = if (e is ProviderRunFailedException) {
                e.message.orEmpty()
            } else {
                failureLine(e, config.heartbeatInstanceId)
            }
            manager.recordHeartbeat(success = false, error = description)
        }
    }

    /**
     * User-pressed manual heartbeat (Settings → Agent → Heartbeat refresh icon). Bypasses
     * the active-hours window and the interval-due check, but still requires heartbeat to
     * be enabled and scheduling overall to be on. No-ops if either is off.
     */
    suspend fun triggerHeartbeatNow() {
        val manager = heartbeatManager ?: return
        if (appSettings?.isSchedulingEnabled() != true) return
        if (!manager.getConfig().enabled) return
        runHeartbeat()
    }

    /**
     * Trims a heartbeat preview to fit a notification body: respects word boundaries
     * when cutting and appends an ellipsis so the user knows more text exists in the
     * conversation. Short inputs pass through unchanged.
     */
    private fun truncateForNotification(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= HEARTBEAT_NOTIFICATION_PREVIEW_CHARS) return trimmed
        val window = trimmed.substring(0, HEARTBEAT_NOTIFICATION_PREVIEW_CHARS)
        val lastSpace = window.lastIndexOf(' ')
        // Only prefer the word boundary if it's close to the cap; otherwise hard-cut —
        // a word boundary 100 chars back would throw away half the preview.
        val cut = if (lastSpace >= HEARTBEAT_NOTIFICATION_PREVIEW_CHARS - 40) lastSpace else window.length
        return window.substring(0, cut).trimEnd().trimEnd(',', ';', ':') + "…"
    }

    private suspend fun checkNewEmails(isLoading: () -> Boolean) {
        if (emailStore == null || appSettings == null || emailPoller == null) return
        val pollMinutes = appSettings.getEmailPollIntervalMinutes()
        if (pollMinutes <= 0) return // 0 = never poll automatically
        val pollIntervalMs = pollMinutes * 60_000L
        val now = Clock.System.now().toEpochMilliseconds()

        for (account in emailStore.getAccounts()) {
            if (isLoading()) break
            val syncState = emailStore.getSyncState(account.id)
            // Rate-limit by last attempt (success or failure) so repeated failures back off
            // at the configured poll interval instead of retrying every scheduler tick.
            val lastActivityMs = maxOf(syncState.lastSyncEpochMs, syncState.lastAttemptEpochMs)
            if (now - lastActivityMs < pollIntervalMs) continue
            emailPoller.poll(account)
        }
    }

    private suspend fun checkNewSms() {
        if (smsStore == null || appSettings == null || smsPoller == null) return
        val pollMinutes = appSettings.getSmsPollIntervalMinutes()
        if (pollMinutes <= 0) return
        val pollIntervalMs = pollMinutes * 60_000L
        val now = Clock.System.now().toEpochMilliseconds()
        val syncState = smsStore.getSyncState()
        val lastActivityMs = maxOf(syncState.lastSyncEpochMs, syncState.lastAttemptEpochMs)
        if (now - lastActivityMs < pollIntervalMs) return
        smsPoller.poll()
    }

    /**
     * Format an exception for the task log. Plain `e.message` collapses too much detail —
     * it's often null (NPE, IllegalStateException-no-arg) or terse ("401"), leaving the
     * user with "unknown error" or a number. Prepending the type name keeps the failure
     * useful for filing an issue.
     */
    private fun formatException(e: Exception): String {
        val type = e::class.simpleName ?: "Exception"
        val msg = e.message?.takeIf { it.isNotBlank() } ?: return type
        return "$type: $msg"
    }

    private fun appendExecution(task: ScheduledTask, success: Boolean, message: String?): List<TaskExecutionLogEntry> {
        val entry = TaskExecutionLogEntry(
            timestampEpochMs = Clock.System.now().toEpochMilliseconds(),
            success = success,
            message = message,
        )
        return (listOf(entry) + task.recentExecutions).take(MAX_TASK_LOG_ENTRIES)
    }

    private suspend fun handleTaskFailure(task: ScheduledTask, error: String? = null) {
        val now = Clock.System.now()
        val ranAtMs = now.toEpochMilliseconds()
        val failures = task.consecutiveFailures + 1
        val reason = error ?: "unknown error"
        val log = appendExecution(task, success = false, message = reason)

        if (task.cron != null) {
            // Cron task failed — advance to the next scheduled time instead of retrying every cycle
            val nextExecution = try {
                CronExpression(task.cron).nextAfter(now)
            } catch (_: Exception) {
                null
            }
            if (nextExecution != null) {
                taskStore!!.updateTask(
                    task.copy(
                        scheduledAtEpochMs = nextExecution.toEpochMilliseconds(),
                        lastResult = "Failed at $now: $reason (next retry at $nextExecution)",
                        consecutiveFailures = failures,
                        recentExecutions = log,
                        lastRunEpochMs = ranAtMs,
                        runCount = task.runCount + 1,
                    ),
                )
            } else {
                taskStore!!.updateTask(
                    task.copy(
                        status = TaskStatus.COMPLETED,
                        lastResult = "Failed at $now: $reason (no next schedule)",
                        consecutiveFailures = failures,
                        recentExecutions = log,
                        lastRunEpochMs = ranAtMs,
                        runCount = task.runCount + 1,
                    ),
                )
            }
        } else {
            // One-time task — apply exponential backoff
            val backoffMs = min(POLL_INTERVAL_MS * (1L shl min(failures, 10)), MAX_BACKOFF_MS)
            taskStore!!.updateTask(
                task.copy(
                    scheduledAtEpochMs = now.toEpochMilliseconds() + backoffMs,
                    lastResult = "Failed at $now: $reason (retry after ${backoffMs / 1000}s backoff)",
                    consecutiveFailures = failures,
                    recentExecutions = log,
                    lastRunEpochMs = ranAtMs,
                    runCount = task.runCount + 1,
                ),
            )
        }
    }

    private suspend fun handleTaskCompletion(task: ScheduledTask, note: String? = null) {
        val now = Clock.System.now()
        val ranAtMs = now.toEpochMilliseconds()
        // The run's note is the only thing a successful execution has to say; a plain run
        // leaves it null so the log stays one line per execution.
        val log = appendExecution(task, success = true, message = note)
        if (task.cron != null) {
            // Recurring task — compute next execution time
            val nextExecution = try {
                CronExpression(task.cron).nextAfter(now)
            } catch (e: Exception) {
                // Cron computation failed — leave pending for retry
                println("TaskScheduler: failed to compute next cron time for task ${task.id}: ${e.message}")
                taskStore!!.updateTask(
                    task.copy(
                        status = TaskStatus.PENDING,
                        lastResult = "Executed at $now (next schedule computation failed, will retry)",
                        consecutiveFailures = 0,
                        recentExecutions = log,
                        lastRunEpochMs = ranAtMs,
                        runCount = task.runCount + 1,
                    ),
                )
                return
            }
            if (nextExecution != null) {
                taskStore!!.updateTask(
                    task.copy(
                        scheduledAtEpochMs = nextExecution.toEpochMilliseconds(),
                        lastResult = "Executed at $now",
                        status = TaskStatus.PENDING,
                        consecutiveFailures = 0,
                        recentExecutions = log,
                        lastRunEpochMs = ranAtMs,
                        runCount = task.runCount + 1,
                    ),
                )
            } else {
                // No valid future time — mark completed
                taskStore!!.updateTask(
                    task.copy(
                        status = TaskStatus.COMPLETED,
                        lastResult = "Executed at $now (no next schedule)",
                        consecutiveFailures = 0,
                        recentExecutions = log,
                        lastRunEpochMs = ranAtMs,
                        runCount = task.runCount + 1,
                    ),
                )
            }
        } else {
            // One-time task — mark completed
            taskStore!!.updateTask(
                task.copy(
                    status = TaskStatus.COMPLETED,
                    lastResult = "Executed at $now",
                    consecutiveFailures = 0,
                    recentExecutions = log,
                    lastRunEpochMs = ranAtMs,
                    runCount = task.runCount + 1,
                ),
            )
        }
    }
}

/**
 * The heartbeat journal's failure line: `<reason>: <provider detail> (<instance>)`.
 *
 * The reason comes first so the owner can tell a rejected key from a dead endpoint at a
 * glance; the provider's own words follow, because that is what names the fix ("model not
 * found", "invalid api key"); the instance closes the line, because the run may have been
 * pointed at a provider other than the one the owner had in mind.
 */
internal fun describeFailure(reason: ProviderFailureReason, detail: String?, target: String?): String = buildString {
    append(reason.name)
    val text = detail?.trim().orEmpty()
    if (text.isNotEmpty()) append(": ").append(text)
    if (!target.isNullOrBlank()) append(" (").append(target).append(')')
}

/**
 * `<provider> / <model>` for a configured instance, or `instance <id>` when the pin no longer
 * resolves — the journal has to say which model failed, and that it was the missing one.
 */
internal fun serviceInstanceLabel(entries: List<ServiceEntry>, instanceId: String?): String? {
    val id = instanceId ?: return null
    val entry = entries.find { it.instanceId == id } ?: return "instance $id"
    return listOfNotNull(entry.serviceName, entry.modelId.takeIf { it.isNotBlank() }).joinToString(" / ")
}
