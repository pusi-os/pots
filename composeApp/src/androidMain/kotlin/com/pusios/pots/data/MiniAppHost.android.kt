package com.pusios.pots.data.miniapp

import android.content.Context
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.CalendarEvent
import com.pusios.pots.tools.CalendarRepository
import com.pusios.pots.tools.CalendarResult
import com.pusios.pots.tools.PermissionController
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent

actual fun createMiniAppHostBridge(): MiniAppHostBridge {
    val context: Context = KoinJavaComponent.get(Context::class.java)
    return AndroidMiniAppHostBridge(
        CalendarRepository(context, PermissionController(AppPermission.CALENDAR)),
    )
}

/** Calendar access for a web app, through the same repository the agent's tools use. */
private class AndroidMiniAppHostBridge(
    private val calendar: CalendarRepository,
) : MiniAppHostBridge {

    override fun listCalendar(fromMs: Long, toMs: Long): List<Map<String, Any?>>? {
        if (!calendar.hasCalendarPermission()) return null
        return calendar.listEvents(fromMs, toMs).map { it.toHostMap() }
    }

    override fun createCalendar(
        title: String,
        startIso: String,
        endIso: String?,
        notes: String?,
    ): Long? {
        if (!calendar.hasCalendarPermission()) return -1L
        return runBlocking {
            when (
                val result = calendar.createEvent(
                    title = title,
                    startTimeIso = startIso,
                    endTimeIso = endIso,
                    description = notes,
                    location = null,
                    allDay = false,
                    reminderMinutes = 0,
                )
            ) {
                is CalendarResult.Success -> result.eventId
                is CalendarResult.Error -> -1L
            }
        }
    }

    private fun CalendarEvent.toHostMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "startMillis" to startMillis,
        "endMillis" to endMillis,
        "allDay" to allDay,
        "description" to description,
        "location" to location,
        "calendar" to calendarName,
        "start" to startTime,
        "end" to endTime,
    )
}
