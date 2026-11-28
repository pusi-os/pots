@file:OptIn(ExperimentalUuidApi::class)

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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Ceilings for one autonomous heartbeat run.
 *
 * Nobody is watching a background check, so it has to bound itself: twelve tool calls is enough
 * for "read the mail, check the calendar, write the answer", and past that the run is looping.
 */
internal val HEARTBEAT_BUDGET = HeadlessBudget(maxToolCalls = 12, maxTokens = 60_000)

/** Hard wall-clock limit for one heartbeat run, tool calls included. */
internal val HEARTBEAT_RUN_TIMEOUT = 5.minutes

/** Outcomes that arm the scheduler-level backoff. A budget stop or a cancellation is not a failure. */
private val FAILED_HEARTBEAT_OUTCOMES = setOf(HeartbeatOutcome.ERROR, HeartbeatOutcome.TIMEOUT)

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
    private class ProviderRun(val result: HeadlessRunResult, val note: String? = null) {
        val content: String get() = result.content
    }

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
     * Guards the heartbeat: one run at a time, whether it came from the tick or from the
     * "Run now" button. Two runs over the same external state used to double the tool calls and
     * the conversation entries.
     */
    private val heartbeatLock = Mutex()

    /** The in-flight heartbeat run, so switching the pulse off can cancel it. */
    private var heartbeatJob: Job? = null

    /** Content fingerprints of delivered reports — see [HeartbeatLedger]. */
    private val heartbeatLedger: HeartbeatLedger? = appSettings?.let { HeartbeatLedger(it) }

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
        // A previous process may have died mid-run; its ephemeral shells are still on disk.
        dataRepository.closeOrphanedHeartbeatSessions()
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
                    // On the process scope, not on the loop's own job: the loop must survive a
                    // cancelled run, and the run must be cancellable on its own.
                    launchHeartbeat(manual = false)
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
        try {
            val taskConversationId = dataRepository.getOrCreateHeartbeatConversationId()
            val run = askWithRetry(
                prompt = task.prompt,
                primaryInstanceId = task.originInstanceId,
                fallbackInstanceId = heartbeatManager?.getConfig()?.heartbeatFallbackInstanceId,
                modelIdOverride = task.originModelId,
                conversationIdOverride = taskConversationId,
            )
            if (run.content.isNotBlank()) {
                val header = task.description.ifBlank { "Scheduled task" }
                dataRepository.addAssistantMessage("**$header**\n\n${run.content}")
            }
            val now = Clock.System.now()
            taskStore?.updateTask(
                task.copy(
                    lastResult = "Run manually at $now",
                    recentExecutions = appendExecution(task, success = true, message = run.note),
                    lastRunEpochMs = now.toEpochMilliseconds(),
                    runCount = task.runCount + 1,
                ),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A manual run that failed has to say so in the task's own history — the sheet has
            // nowhere else to show it, and the user pressed the button expecting an answer.
            val reason = if (e is ProviderRunFailedException) e.message.orEmpty() else formatException(e)
            taskStore?.updateTask(
                task.copy(
                    lastResult = "Manual run failed at $startedAt: $reason",
                    recentExecutions = appendExecution(task, success = false, message = reason),
                    lastRunEpochMs = startedAt.toEpochMilliseconds(),
                    runCount = task.runCount + 1,
                ),
            )
        }
    }

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
        budget: HeadlessBudget? = null,
    ): ProviderRun {
        val startedAt = TimeSource.Monotonic.markNow()
        var lastFailure: Exception? = null
        for (attempt in 1..PRIMARY_ATTEMPTS) {
            val remaining = PRIMARY_ATTEMPT_BUDGET - startedAt.elapsedNow()
            if (remaining <= Duration.ZERO) break
            try {
                // A hanging endpoint must not own the whole budget: the attempt is bounded by
                // what is left of it, so the fallback still gets its turn.
                val result = withTimeoutOrNull(remaining) {
                    dataRepository.askWithToolsDetailed(
                        prompt = prompt,
                        instanceId = primaryInstanceId,
                        modelIdOverride = modelIdOverride,
                        conversationIdOverride = conversationIdOverride,
                        budget = budget,
                    )
                } ?: throw OpenAICompatibleConnectionException(
                    reason = ProviderFailureReason.Timeout,
                    detail = "no answer within $PRIMARY_ATTEMPT_BUDGET",
                )
                return ProviderRun(result)
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
            val result = dataRepository.askWithToolsDetailed(
                prompt = prompt,
                instanceId = fallbackInstanceId,
                modelIdOverride = null,
                conversationIdOverride = conversationIdOverride,
                budget = budget,
            )
            return ProviderRun(
                result = result,
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
     * Starts one heartbeat run on the process scope and waits for it, so the tick never overlaps
     * a run and a cancelled run does not take the loop down with it.
     */
    private suspend fun launchHeartbeat(manual: Boolean) {
        val job = schedulerScope.launch { runHeartbeatSingleFlight(manual) }
        heartbeatJob = job
        job.join()
    }

    /**
     * Single-flight wrapper: the tick and the "Run now" button both come through here.
     *
     * A second run over a running one used to double the provider calls, the tool calls and the
     * conversation entries. The busy case is only journalled for a button press — a tick that
     * finds a run in flight is normal and would otherwise fill the journal with noise.
     */
    private suspend fun runHeartbeatSingleFlight(manual: Boolean) {
        val manager = heartbeatManager ?: return
        if (!heartbeatLock.tryLock()) {
            if (manual) {
                manager.recordRun(
                    HeartbeatLogEntry(
                        timestampEpochMs = Clock.System.now().toEpochMilliseconds(),
                        success = true,
                        outcome = HeartbeatOutcome.BUSY,
                        note = "a run was already in flight",
                    ),
                )
            }
            return
        }
        try {
            runHeartbeat()
        } finally {
            heartbeatLock.unlock()
        }
    }

    /**
     * One heartbeat run in a fresh session.
     *
     * Nothing from the previous run reaches the model: the prompt is built from external state
     * only, and the run binds its tool calls to an ephemeral `hb-run-*` session that is closed
     * when the run ends. The run is classified by its evidence — the tool trace — not by its
     * prose, because a promise to check something later is inert.
     */
    private suspend fun runHeartbeat() {
        val manager = heartbeatManager ?: return
        val startedAtMs = Clock.System.now().toEpochMilliseconds()
        val startedMark = TimeSource.Monotonic.markNow()
        val config = manager.getConfig()
        val runId = Uuid.random().toString()
        val sessionId = "$HEARTBEAT_SESSION_PREFIX$runId"
        val modelLabel = instanceLabel(config.heartbeatInstanceId)
        val pendingEmails = emailStore?.getPending().orEmpty()
        val pendingSms = smsStore?.getPending().orEmpty()
        val pendingNotifications = notificationStore?.getPending().orEmpty()

        var outcome = HeartbeatOutcome.ERROR
        var detail: String? = null
        var note: String? = null
        var toolCount = 0
        var notified = false
        var cancellation: CancellationException? = null
        try {
            val prompt = manager.buildHeartbeatPrompt(
                pendingEmails = pendingEmails,
                pendingSms = pendingSms,
                pendingNotifications = pendingNotifications,
                budget = HEARTBEAT_BUDGET,
            )
            val run = withTimeout(HEARTBEAT_RUN_TIMEOUT) {
                askWithRetry(
                    prompt = prompt,
                    primaryInstanceId = config.heartbeatInstanceId,
                    fallbackInstanceId = config.heartbeatFallbackInstanceId,
                    conversationIdOverride = sessionId,
                    budget = HEARTBEAT_BUDGET,
                )
            }
            note = run.note
            toolCount = run.result.toolCalls.size
            val decision = classifyHeartbeatReply(
                content = run.content,
                toolCalls = run.result.toolCalls,
                // "Fresh" is what the run was given: mail/SMS/notifications that arrived since the
                // last run, or a standing instruction the user wrote. Pending tasks and promotion
                // candidates are always in the prompt, so they cannot make a quiet run report.
                hasFreshMaterial = pendingEmails.isNotEmpty() || pendingSms.isNotEmpty() ||
                    pendingNotifications.isNotEmpty() || manager.hasHeartbeatAdditions(),
                budgetExhausted = run.result.budgetExhausted,
            )
            when (decision) {
                HeartbeatDecision.Silent -> outcome = HeartbeatOutcome.OK_SILENT
                HeartbeatDecision.Chatter -> outcome = HeartbeatOutcome.CHATTER
                HeartbeatDecision.Budget -> outcome = HeartbeatOutcome.BUDGET
                is HeartbeatDecision.Report -> {
                    outcome = deliverHeartbeatReport(decision.body, startedAtMs)
                    notified = outcome == HeartbeatOutcome.REPORTED
                }
            }
            consumeHeartbeatSnapshot(pendingEmails, pendingSms, pendingNotifications)
            notificationStore?.sweep()
        } catch (e: TimeoutCancellationException) {
            outcome = HeartbeatOutcome.TIMEOUT
            detail = "no answer within $HEARTBEAT_RUN_TIMEOUT"
        } catch (e: CancellationException) {
            // Switching the pulse off (or stopping the scheduler) cancels the run. That is not a
            // failure, and nothing of a half-run may land in the Pulse conversation.
            outcome = HeartbeatOutcome.CANCELLED
            cancellation = e
        } catch (e: Exception) {
            // The journal is the owner's only window into a background failure: name the reason
            // and the instance, not the exception class. A run that exhausted every configured
            // instance arrives already described (ProviderRunFailedException).
            outcome = HeartbeatOutcome.ERROR
            detail = if (e is ProviderRunFailedException) e.message.orEmpty() else failureLine(e, config.heartbeatInstanceId)
        } finally {
            // A cancelled coroutine cannot suspend, but closing the run's shell and writing the
            // journal entry are the whole point of cancelling honestly: do both, then let the
            // cancellation propagate. Closing the session outside this block would skip the
            // journal line on every cancel — the exact case the owner looks for afterwards.
            withContext(NonCancellable) {
                dataRepository.closeHeadlessSession(sessionId)
                val durationMs = startedMark.elapsedNow().inWholeMilliseconds
                manager.markAttempt(startedAtMs, failed = outcome in FAILED_HEARTBEAT_OUTCOMES)
                manager.recordRun(
                    HeartbeatLogEntry(
                        timestampEpochMs = startedAtMs,
                        success = outcome !in FAILED_HEARTBEAT_OUTCOMES,
                        outcome = outcome,
                        durationMs = durationMs,
                        modelLabel = modelLabel,
                        toolCount = toolCount,
                        notified = notified,
                        error = detail.takeIf { outcome in FAILED_HEARTBEAT_OUTCOMES },
                        note = note,
                    ),
                )
            }
        }
        cancellation?.let { throw it }
    }

    /**
     * Delivers one report: the conversation entry, the push when the app is in the background,
     * and the fingerprint that stops the same content from being delivered again.
     */
    private suspend fun deliverHeartbeatReport(body: String, nowMs: Long): HeartbeatOutcome {
        val fingerprint = heartbeatFingerprint(body)
        val ledger = heartbeatLedger
        if (ledger != null && ledger.isDuplicate(fingerprint, nowMs)) {
            ledger.recordSuppressed(fingerprint, nowMs)
            return HeartbeatOutcome.DUPLICATE
        }
        dataRepository.addAssistantMessage(body)
        ledger?.recordDelivered(fingerprint, nowMs)
        // Push-notify only when the user won't see the in-app banner.
        // Tapping the notification deep-links into the heartbeat
        // conversation via `EXTRA_OPEN_HEARTBEAT` (Android actual).
        // Strip markdown + pots-ui fences before sending to the tray —
        // the notification surface can't render them and raw fence
        // text (```pots-ui {...}```) is unreadable.
        if (!appInForeground) {
            val preview = truncateForNotification(parseMarkdown(body).toSpeakableText())
            if (preview.isNotBlank()) {
                sendHeartbeatNotification(title = "Pots heartbeat", body = preview)
            }
        }
        return HeartbeatOutcome.REPORTED
    }

    /**
     * Consumes exactly the snapshot the run was shown — messages that arrived during the call stay
     * pending for the next heartbeat. The email watermark advances too, so a follow-up
     * `check_email` does not re-surface the same UIDs.
     */
    private suspend fun consumeHeartbeatSnapshot(
        pendingEmails: List<EmailMessage>,
        pendingSms: List<SmsMessage>,
        pendingNotifications: List<NotificationRecord>,
    ) {
        if (pendingEmails.isNotEmpty()) {
            emailStore?.let { store ->
                store.removePending(pendingEmails)
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
    }

    /**
     * User-pressed manual heartbeat (Settings → Agent → Heartbeat). Bypasses the active-hours
     * window, the interval and the backoff — the owner is looking at the screen and asked. Still
     * requires heartbeat to be enabled and scheduling overall to be on, and still goes through
     * the single-flight guard. Returns false when the run could not start.
     */
    suspend fun triggerHeartbeatNow(): Boolean {
        val manager = heartbeatManager ?: return false
        if (appSettings?.isSchedulingEnabled() != true) return false
        if (!manager.getConfig().enabled) return false
        launchHeartbeat(manual = true)
        return true
    }

    /**
     * Cancels an in-flight heartbeat run. Called when the pulse is switched off: a run whose
     * premise ("the pulse is on") just disappeared must not finish and write into the Pulse.
     */
    fun cancelHeartbeatRun() {
        heartbeatJob?.cancel()
    }

    /** Stops the scheduler loop and any heartbeat run it started. */
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        activeJob?.cancel()
        activeJob = null
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
