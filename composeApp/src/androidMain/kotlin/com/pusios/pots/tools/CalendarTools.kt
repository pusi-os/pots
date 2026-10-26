package com.pusios.pots.tools

import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tool_delete_calendar_event_description
import pots.composeapp.generated.resources.tool_delete_calendar_event_name
import pots.composeapp.generated.resources.tool_list_calendar_events_description
import pots.composeapp.generated.resources.tool_list_calendar_events_name
import pots.composeapp.generated.resources.tool_update_calendar_event_description
import pots.composeapp.generated.resources.tool_update_calendar_event_name
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Reads the user's calendar.
 *
 * One tool instead of several: an agent that wants to know what is on the calendar usually
 * wants to look at a window of it, and splitting "list" and "search" into two tools only
 * doubles the number of schemas the model has to choose between.
 */
object ListCalendarEventsTool {

    const val ID = "list_calendar_events"

    val toolInfo = ToolInfo(
        id = ID,
        name = "List Calendar Events",
        description = "List or search the user's calendar events",
        nameRes = Res.string.tool_list_calendar_events_name,
        descriptionRes = Res.string.tool_list_calendar_events_description,
    )

    fun create(calendarRepository: CalendarRepository): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "List or search the user's calendar events. Pass a time window to list " +
                "what is scheduled, or a query to find events by title, notes or location.",
            parameters = mapOf(
                "start_time" to ParameterSchema(
                    "string",
                    "Window start as ISO 8601, e.g. '2024-03-15T00:00:00'. Naive (no offset) is treated as user's local time.",
                    false,
                ),
                "end_time" to ParameterSchema(
                    "string",
                    "Window end as ISO 8601, same format as start_time. Defaults to 30 days after the start.",
                    false,
                ),
                "query" to ParameterSchema(
                    "string",
                    "Search text matched against the title, description and location of each event.",
                    false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = args["query"] as? String
            val startIso = args["start_time"] as? String
            val endIso = args["end_time"] as? String

            val now = Instant.now()
            val from = startIso?.let { parseIsoOrNull(it) } ?: now
            val to = endIso?.let { parseIsoOrNull(it) } ?: from.plus(30, ChronoUnit.DAYS)

            val events = if (!query.isNullOrBlank()) {
                calendarRepository.searchEvents(query = query, fromMillis = from.toEpochMilli(), toMillis = to.toEpochMilli())
            } else {
                calendarRepository.listEvents(fromMillis = from.toEpochMilli(), toMillis = to.toEpochMilli())
            }

            return mapOf(
                "success" to true,
                "count" to events.size,
                "events" to events.map { it.toMap() },
            )
        }
    }
}

/**
 * Changes an existing calendar event.
 *
 * Every field is optional: an argument that is not passed leaves the stored value untouched,
 * so "move it by an hour" does not wipe the notes.
 */
object UpdateCalendarEventTool {

    const val ID = "update_calendar_event"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Update Calendar Event",
        description = "Change the title, time, notes, location or all-day flag of a calendar event",
        nameRes = Res.string.tool_update_calendar_event_name,
        descriptionRes = Res.string.tool_update_calendar_event_description,
    )

    fun create(calendarRepository: CalendarRepository): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Change an existing calendar event. Only the fields you pass are changed. " +
                "Use list_calendar_events to find the event id first.",
            parameters = mapOf(
                "event_id" to ParameterSchema("string", "Id of the event to change", true),
                "title" to ParameterSchema("string", "New event title", false),
                "start_time" to ParameterSchema("string", "New start time as ISO 8601, e.g. '2024-03-15T14:30:00'", false),
                "end_time" to ParameterSchema("string", "New end time, same format as start_time", false),
                "description" to ParameterSchema("string", "New event notes or description", false),
                "location" to ParameterSchema("string", "New event location", false),
                "all_day" to ParameterSchema("boolean", "Whether this is an all-day event", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val eventId = (args["event_id"] as? Number)?.toLong()
                ?: (args["event_id"] as? String)?.toLongOrNull()
                ?: return mapOf("success" to false, "error" to "event_id is required")

            return when (
                val result = calendarRepository.updateEvent(
                    eventId = eventId,
                    title = args["title"] as? String,
                    startTimeIso = args["start_time"] as? String,
                    endTimeIso = args["end_time"] as? String,
                    description = args["description"] as? String,
                    location = args["location"] as? String,
                    allDay = args["all_day"] as? Boolean,
                )
            ) {
                is CalendarResult.Success -> mapOf(
                    "success" to true,
                    "event_id" to result.eventId,
                    "title" to result.title,
                    "scheduled_for" to result.startTime,
                    "message" to "Event '${result.title}' updated successfully",
                )

                is CalendarResult.Error -> mapOf(
                    "success" to false,
                    "error" to result.message,
                )
            }
        }
    }
}

/** Removes a calendar event. */
object DeleteCalendarEventTool {

    const val ID = "delete_calendar_event"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Delete Calendar Event",
        description = "Delete a calendar event",
        nameRes = Res.string.tool_delete_calendar_event_name,
        descriptionRes = Res.string.tool_delete_calendar_event_description,
    )

    fun create(calendarRepository: CalendarRepository): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Delete a calendar event. Use list_calendar_events to find the event id first, " +
                "and confirm with the user before deleting.",
            parameters = mapOf(
                "event_id" to ParameterSchema("string", "Id of the event to delete", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val eventId = (args["event_id"] as? Number)?.toLong()
                ?: (args["event_id"] as? String)?.toLongOrNull()
                ?: return mapOf("success" to false, "error" to "event_id is required")

            return when (val result = calendarRepository.deleteEvent(eventId)) {
                is CalendarResult.Success -> mapOf(
                    "success" to true,
                    "event_id" to result.eventId,
                    "message" to "Event deleted successfully",
                )

                is CalendarResult.Error -> mapOf(
                    "success" to false,
                    "error" to result.message,
                )
            }
        }
    }
}

/** Serializes an event into the flat map the model receives. */
internal fun CalendarEvent.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "start" to startTime,
    "end" to endTime,
    "start_epoch_ms" to startMillis,
    "end_epoch_ms" to endMillis,
    "all_day" to allDay,
    "description" to description,
    "location" to location,
    "calendar" to calendarName,
)

/**
 * Parses an ISO 8601 instant, falling back to the start of the day in UTC when the value is
 * not a full timestamp.
 *
 * The fallback matters: an agent that says "tomorrow" produces a bare date, and a bare date
 * must still resolve to a usable time window instead of failing the whole call.
 */
private fun parseIsoOrNull(iso: String): Instant? {
    val trimmed = iso.trim()
    return try {
        Instant.parse(trimmed)
    } catch (_: Exception) {
        try {
            java.time.LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}
