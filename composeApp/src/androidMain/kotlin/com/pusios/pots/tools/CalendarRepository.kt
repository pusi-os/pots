package com.pusios.pots.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.TimeZone

private const val TAG = "CalendarRepository"

sealed class CalendarResult {
    data class Success(val eventId: Long, val title: String, val startTime: String) : CalendarResult()
    data class Error(val message: String) : CalendarResult()
}

/**
 * A calendar event as the agent sees it.
 *
 * Times are epoch milliseconds so the agent can reason about them without parsing, and the
 * display strings are already formatted for the user's locale and time zone.
 */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val description: String?,
    val location: String?,
    val calendarName: String?,
    val startTime: String,
    val endTime: String,
)

class CalendarRepository(
    private val context: Context,
    private val permissionController: PermissionController,
) {

    fun hasCalendarPermission(): Boolean {
        val hasRead = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        val hasWrite = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        return hasRead && hasWrite
    }

    fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )

        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                if (idIndex >= 0) {
                    return cursor.getLong(idIndex)
                }
            }
        }
        return null
    }

    suspend fun createEvent(
        title: String,
        startTimeIso: String,
        endTimeIso: String?,
        description: String?,
        location: String?,
        allDay: Boolean,
        reminderMinutes: Int,
    ): CalendarResult {
        Log.d(TAG, "createEvent called: title=$title, startTime=$startTimeIso")

        // Request permission if not already granted
        if (!hasCalendarPermission()) {
            Log.d(TAG, "Permission not granted, requesting...")
            val granted = permissionController.requestPermission()
            Log.d(TAG, "Permission request result: $granted")
            if (!granted) {
                return CalendarResult.Error("Calendar permission denied. Please enable calendar access in Settings to create events.")
            }
        } else {
            Log.d(TAG, "Permission already granted")
        }

        val calendarId = getPrimaryCalendarId()
            ?: return CalendarResult.Error("No writable calendar found. Please set up a calendar account on your device.")

        val startMillis: Long
        val endMillis: Long
        val timeZone = TimeZone.getDefault().id

        try {
            startMillis = parseIsoDateTimeToEpochMs(startTimeIso)
            endMillis = if (endTimeIso != null) {
                parseIsoDateTimeToEpochMs(endTimeIso)
            } else {
                // Default to 1 hour after start
                startMillis + 60 * 60 * 1000
            }
        } catch (e: DateTimeParseException) {
            return CalendarResult.Error("Invalid date format. Please use ISO 8601 format (e.g., 2024-03-15T14:30:00)")
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment?.toLongOrNull()

            if (eventId != null) {
                // Add reminder if specified
                if (reminderMinutes > 0) {
                    addReminder(eventId, reminderMinutes)
                }

                val formattedStart = formatForDisplay(startMillis)
                CalendarResult.Success(eventId, title, formattedStart)
            } else {
                CalendarResult.Error("Failed to create calendar event")
            }
        } catch (e: Exception) {
            CalendarResult.Error("Error creating event: ${e.message}")
        }
    }

    /**
     * Lists the events in a time window.
     *
     * The window is half-open in the query but inclusive in the result: an event that starts
     * exactly at [fromMillis] is included, and one that ends exactly at [toMillis] is not.
     */
    fun listEvents(
        fromMillis: Long,
        toMillis: Long,
        limit: Int = DEFAULT_QUERY_LIMIT,
    ): List<CalendarEvent> {
        if (!hasCalendarPermission()) return emptyList()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )
        val selection = "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DTEND} > ?"
        val selectionArgs = arrayOf(toMillis.toString(), fromMillis.toString())

        return context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC LIMIT $limit",
        )?.use { cursor ->
            val events = mutableListOf<CalendarEvent>()
            val idIndex = cursor.getColumnIndex(CalendarContract.Events._ID)
            val titleIndex = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIndex = cursor.getColumnIndex(CalendarContract.Events.DTEND)
            val allDayIndex = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val descriptionIndex = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val locationIndex = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val calendarIndex = cursor.getColumnIndex(CalendarContract.Events.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val start = cursor.getLong(startIndex)
                val end = cursor.getLong(endIndex)
                events += CalendarEvent(
                    id = cursor.getLong(idIndex),
                    title = cursor.getString(titleIndex) ?: "(no title)",
                    startMillis = start,
                    endMillis = end,
                    allDay = cursor.getInt(allDayIndex) == 1,
                    description = cursor.getString(descriptionIndex),
                    location = cursor.getString(locationIndex),
                    calendarName = cursor.getString(calendarIndex),
                    startTime = formatForDisplay(start),
                    endTime = formatForDisplay(end),
                )
            }
            events
        } ?: emptyList()
    }

    /**
     * Finds events whose title, description or location contains [query].
     *
     * The match is case-insensitive and substring-based — an agent that was told "find my
     * dentist appointment" should not have to reproduce the exact wording of the event.
     */
    fun searchEvents(
        query: String,
        fromMillis: Long? = null,
        toMillis: Long? = null,
        limit: Int = DEFAULT_QUERY_LIMIT,
    ): List<CalendarEvent> {
        if (!hasCalendarPermission()) return emptyList()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (fromMillis != null && toMillis != null) {
            clauses += "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DTEND} > ?"
            args += toMillis.toString()
            args += fromMillis.toString()
        }
        val selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")

        return context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            "${CalendarContract.Events.DTSTART} DESC LIMIT $limit",
        )?.use { cursor ->
            val needle = query.trim().lowercase()
            val events = mutableListOf<CalendarEvent>()
            val idIndex = cursor.getColumnIndex(CalendarContract.Events._ID)
            val titleIndex = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIndex = cursor.getColumnIndex(CalendarContract.Events.DTEND)
            val allDayIndex = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val descriptionIndex = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val locationIndex = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val calendarIndex = cursor.getColumnIndex(CalendarContract.Events.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val title = cursor.getString(titleIndex) ?: "(no title)"
                val description = cursor.getString(descriptionIndex)
                val location = cursor.getString(locationIndex)
                val haystack = listOfNotNull(title, description, location)
                    .joinToString(" ")
                    .lowercase()
                if (!haystack.contains(needle)) continue
                val start = cursor.getLong(startIndex)
                val end = cursor.getLong(endIndex)
                events += CalendarEvent(
                    id = cursor.getLong(idIndex),
                    title = title,
                    startMillis = start,
                    endMillis = end,
                    allDay = cursor.getInt(allDayIndex) == 1,
                    description = description,
                    location = location,
                    calendarName = cursor.getString(calendarIndex),
                    startTime = formatForDisplay(start),
                    endTime = formatForDisplay(end),
                )
            }
            events
        } ?: emptyList()
    }

    /**
     * Updates an existing event.
     *
     * Only the fields that are passed in are changed; a null argument leaves the stored value
     * alone. That keeps a partial update ("move it by an hour") from wiping the description.
     */
    fun updateEvent(
        eventId: Long,
        title: String? = null,
        startTimeIso: String? = null,
        endTimeIso: String? = null,
        description: String? = null,
        location: String? = null,
        allDay: Boolean? = null,
    ): CalendarResult {
        if (!hasCalendarPermission()) {
            return CalendarResult.Error("Calendar permission denied. Please enable calendar access in Settings.")
        }

        val values = ContentValues()
        title?.let { values.put(CalendarContract.Events.TITLE, it) }
        description?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
        location?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        allDay?.let { values.put(CalendarContract.Events.ALL_DAY, if (it) 1 else 0) }

        try {
            startTimeIso?.let {
                values.put(CalendarContract.Events.DTSTART, parseIsoDateTimeToEpochMs(it))
            }
            endTimeIso?.let {
                values.put(CalendarContract.Events.DTEND, parseIsoDateTimeToEpochMs(it))
            }
        } catch (e: DateTimeParseException) {
            return CalendarResult.Error("Invalid date format. Please use ISO 8601 format (e.g., 2024-03-15T14:30:00)")
        }

        if (values.size() == 0) {
            return CalendarResult.Error("Nothing to update. Pass at least one field to change.")
        }

        return try {
            val updated = context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString()),
            )
            if (updated > 0) {
                val event = listEvents(0, Long.MAX_VALUE, DEFAULT_QUERY_LIMIT).firstOrNull { it.id == eventId }
                CalendarResult.Success(eventId, title ?: event?.title ?: "", event?.startTime ?: "")
            } else {
                CalendarResult.Error("Event $eventId not found")
            }
        } catch (e: Exception) {
            CalendarResult.Error("Error updating event: ${e.message}")
        }
    }

    /** Deletes an event. Returns the id of the deleted event on success. */
    fun deleteEvent(eventId: Long): CalendarResult {
        if (!hasCalendarPermission()) {
            return CalendarResult.Error("Calendar permission denied. Please enable calendar access in Settings.")
        }
        return try {
            val deleted = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString()),
            )
            if (deleted > 0) {
                CalendarResult.Success(eventId, "", "")
            } else {
                CalendarResult.Error("Event $eventId not found")
            }
        } catch (e: Exception) {
            CalendarResult.Error("Error deleting event: ${e.message}")
        }
    }

    private fun addReminder(eventId: Long, minutesBefore: Int) {
        val reminderValues = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
    }

    private fun parseIsoDateTimeToEpochMs(isoString: String): Long {
        val trimmed = isoString.trim()

        // Offset-qualified inputs (e.g. "2024-03-15T14:30:00+02:00" or "...Z") must
        // be converted directly to an instant. Going through LocalDateTime.parse with
        // a relaxed formatter silently drops the offset and re-anchors to system zone,
        // which surfaces as an off-by-N-hours bug whenever the AI sends an offset that
        // differs from the device's local offset.
        try {
            return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            return Instant.parse(trimmed).toEpochMilli()
        } catch (_: DateTimeParseException) {
        }

        // Naive inputs: interpret in the device's current zone.
        val formatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        )
        for (formatter in formatters) {
            try {
                val ldt = LocalDateTime.parse(trimmed, formatter)
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }

        throw DateTimeParseException("Unable to parse date: $isoString", isoString, 0)
    }

    companion object {
        /** Upper bound on the number of events a single query returns. */
        const val DEFAULT_QUERY_LIMIT = 50
    }

    private fun formatForDisplay(millis: Long): String {
        val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a")
        val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        return dateTime.format(formatter)
    }
}
