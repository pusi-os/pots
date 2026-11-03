package com.pusios.pots.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Both pending task lists produced by [TaskStore.getPendingTasksPartitioned]. */
data class PendingTaskPartition(
    val scheduled: List<ScheduledTask>,
    val heartbeatAdditions: List<ScheduledTask>,
)

@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class TaskStore(appSettings: AppSettings) {

    private val _tasksFlow = MutableStateFlow<List<ScheduledTask>>(emptyList())

    /**
     * Live task list, refreshed on every write. The settings screen collects this so it
     * always shows exactly what the scheduler sees — including tasks the agent created in
     * the background while the screen was first opened.
     */
    val tasksFlow: StateFlow<List<ScheduledTask>> = _tasksFlow.asStateFlow()

    private val tasks = SettingsJsonList(
        read = appSettings::getScheduledTasksJson,
        write = appSettings::setScheduledTasksJson,
        itemSerializer = serializer<ScheduledTask>(),
        label = "TaskStore",
        onCorrupt = { appSettings.backupCorruptJson("TaskStore", it) },
        onWrite = { _tasksFlow.value = it },
        // Tasks persisted before the `trigger` field existed decode with the default (TIME).
        // Upgrade rows that carry a cron expression to CRON so the scheduler can distinguish
        // time/cron from heartbeat additions. Returning a non-null list persists the upgrade the
        // first time we see it, so every subsequent load is a no-op.
        migrate = { decoded ->
            val upgraded = decoded.map { task ->
                if (task.trigger == TaskTrigger.TIME && task.cron != null) task.copy(trigger = TaskTrigger.CRON) else task
            }
            upgraded.takeIf { it != decoded }
        },
    )

    init {
        _tasksFlow.value = tasks.get()
    }

    suspend fun addTask(
        description: String,
        prompt: String,
        scheduledAtEpochMs: Long,
        cron: String? = null,
        trigger: TaskTrigger = if (cron != null) TaskTrigger.CRON else TaskTrigger.TIME,
        originInstanceId: String? = null,
        originModelId: String? = null,
    ): ScheduledTask {
        val now = Clock.System.now()
        val effectiveScheduledAt = when (trigger) {
            TaskTrigger.HEARTBEAT -> 0L

            // heartbeat tasks are not time-gated
            TaskTrigger.CRON -> if (scheduledAtEpochMs == 0L) {
                try {
                    CronExpression(cron!!).nextAfter(now)?.toEpochMilliseconds() ?: now.toEpochMilliseconds()
                } catch (_: Exception) {
                    now.toEpochMilliseconds()
                }
            } else {
                scheduledAtEpochMs
            }

            TaskTrigger.TIME -> scheduledAtEpochMs
        }

        // Standing tasks (cron / heartbeat) are deduplicated by description: a re-scheduled
        // standing check updates the existing task instead of accumulating identical twins.
        // Without this, an agent run that calls schedule_task again leaves a duplicate the
        // user doesn't recognise, and deleting "the" task still leaves its twin firing.
        // One-off TIME tasks are exempt — scheduling the same reminder twice is legitimate.
        if (trigger != TaskTrigger.TIME) {
            val existing = tasks.get().firstOrNull {
                it.trigger != TaskTrigger.TIME && it.description == description && it.status == TaskStatus.PENDING
            }
            if (existing != null) {
                val updated = existing.copy(
                    prompt = prompt,
                    scheduledAtEpochMs = effectiveScheduledAt,
                    cron = cron,
                    trigger = trigger,
                    lastResult = null,
                    consecutiveFailures = 0,
                    originInstanceId = originInstanceId,
                    originModelId = originModelId,
                )
                tasks.update { current -> current.map { if (it.id == existing.id) updated else it } }
                return updated
            }
        }

        val task = ScheduledTask(
            id = Uuid.random().toString(),
            description = description,
            prompt = prompt,
            scheduledAtEpochMs = effectiveScheduledAt,
            createdAtEpochMs = now.toEpochMilliseconds(),
            cron = cron,
            trigger = trigger,
            originInstanceId = originInstanceId,
            originModelId = originModelId,
        )
        tasks.update { it + task }
        return task
    }

    fun getAllTasks(): List<ScheduledTask> = tasks.get()

    /**
     * Both pending scheduled tasks and heartbeat additions from a single load. Hot-path
     * callers (chat system prompt, heartbeat prompt) need both lists per invocation;
     * combining avoids re-parsing the tasks JSON twice.
     */
    fun getPendingTasksPartitioned(): PendingTaskPartition {
        val (additions, scheduled) = tasks.get()
            .filter { it.status == TaskStatus.PENDING }
            .partition { it.trigger == TaskTrigger.HEARTBEAT }
        return PendingTaskPartition(scheduled = scheduled, heartbeatAdditions = additions)
    }

    suspend fun updateTask(task: ScheduledTask): ScheduledTask {
        tasks.update { current ->
            if (current.none { it.id == task.id }) current else current.map { if (it.id == task.id) task else it }
        }
        return task
    }

    suspend fun removeTask(id: String): Boolean {
        var removed = false
        tasks.update { current ->
            removed = current.any { it.id == id }
            if (removed) current.filterNot { it.id == id } else current
        }
        return removed
    }

    /**
     * Drops every task whose description starts with [prefix]. Used by the mini-app
     * deletion cascade — see [miniAppTaskPrefix].
     */
    suspend fun removeTasksWithDescriptionPrefix(prefix: String): Int {
        var removed = 0
        tasks.update { current ->
            removed = current.count { it.description.startsWith(prefix) }
            if (removed > 0) current.filterNot { it.description.startsWith(prefix) } else current
        }
        return removed
    }

    fun getDueTasks(): List<ScheduledTask> {
        val now = Clock.System.now().toEpochMilliseconds()
        return tasks.get().filter {
            it.trigger != TaskTrigger.HEARTBEAT &&
                it.scheduledAtEpochMs <= now &&
                it.status == TaskStatus.PENDING
        }
    }
}
