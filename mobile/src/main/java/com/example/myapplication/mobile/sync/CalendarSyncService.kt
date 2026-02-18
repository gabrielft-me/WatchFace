package com.example.myapplication.mobile.sync

import android.Manifest
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Syncs calendar events from the phone to the watch via the Wearable Data Layer.
 * Wear OS watches don't have local calendar data - it must be pushed from the phone.
 */
object CalendarSyncHelper {

    private const val DATA_PATH = "/watch_face/calendar_snapshot"
    private const val KEY_SNAPSHOT_JSON = "snapshot_json"
    private const val KEY_TIMESTAMP = "timestamp"

    private val urlRegex = Regex("""https?://[^\s)]+""")

    fun syncToWatch(context: Context) {
        android.util.Log.d("CalendarSync", "=== Starting sync to watch ===")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("CalendarSync", "No calendar permission - skipping sync")
            return
        }

        val events = fetchCalendarEvents(context)
        android.util.Log.d("CalendarSync", "Fetched ${events.size} events from phone calendar")
        
        events.take(5).forEach { event ->
            android.util.Log.d("CalendarSync", "  - ${event.title}: color=${event.color} (${event.color?.let{"0x${Integer.toHexString(it)}"} ?: "null"})")
        }
        
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)

        val snapshot = SnapshotDto(
            dateLocal = today.toString(),
            timezoneId = zoneId.id,
            events = events,
            lastSyncAt = System.currentTimeMillis(),
        )

        val json = GsonInstance.gson.toJson(snapshot)
        android.util.Log.d("CalendarSync", "JSON size: ${json.length} chars")

        val putDataReq = PutDataMapRequest.create(DATA_PATH).apply {
            dataMap.putString(KEY_SNAPSHOT_JSON, json)
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        }.asPutDataRequest()

        try {
            val dataClient = Wearable.getDataClient(context)
            Tasks.await(dataClient.putDataItem(putDataReq))
            android.util.Log.d("CalendarSync", "✓ Successfully synced to watch")
        } catch (e: Exception) {
            android.util.Log.e("CalendarSync", "Failed to sync to watch", e)
        }
    }

    fun fetchEvents(context: Context): List<EventDto> = fetchCalendarEvents(context)

    private fun fetchCalendarEvents(context: Context): List<EventDto> {
        val contentResolver: ContentResolver = context.contentResolver
        val events = mutableListOf<EventDto>()
        val zoneId = ZoneId.systemDefault()

        val today = LocalDate.now(zoneId)
        val startOfRange = ZonedDateTime.of(today.minusDays(3), LocalTime.MIDNIGHT, zoneId)
        val endOfRange = ZonedDateTime.of(today.plusDays(4), LocalTime.MIDNIGHT, zoneId)

        val startMillis = startOfRange.toInstant().toEpochMilli()
        val endMillis = endOfRange.toInstant().toEpochMilli()

        val uri: Uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DISPLAY_COLOR,
        )

        val calendarInfo = resolveCalendarInfo(contentResolver)

        try {
            contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val startIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndex(CalendarContract.Instances.END)
                val locationIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val descriptionIndex = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
                val allDayIndex = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val calendarIdIndex = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
                val colorIndex = cursor.getColumnIndex(CalendarContract.Instances.DISPLAY_COLOR)

                while (cursor.moveToNext()) {
                    val id = if (idIndex >= 0) cursor.getLong(idIndex).toString() else ""
                    val title = if (titleIndex >= 0) cursor.getString(titleIndex) ?: "No Title" else "No Title"
                    val startMs = if (startIndex >= 0) cursor.getLong(startIndex) else 0L
                    val endMs = if (endIndex >= 0) cursor.getLong(endIndex) else startMs
                    val location = if (locationIndex >= 0) cursor.getString(locationIndex) else null
                    val description = if (descriptionIndex >= 0) cursor.getString(descriptionIndex) else null
                    val allDay = if (allDayIndex >= 0) cursor.getInt(allDayIndex) == 1 else false
                    val calendarId = if (calendarIdIndex >= 0) cursor.getString(calendarIdIndex) else null
                    val displayColor = if (colorIndex >= 0) {
                        val colorVal = cursor.getInt(colorIndex)
                        // Treat 0 as null (no color set)
                        if (colorVal == 0) null else colorVal
                    } else null

                    val instanceId = "${id}_${startMs}"
                    val calInfo = calendarId?.let { calendarInfo[it] }
                    val finalColor = displayColor ?: calInfo?.color
                    val meetingLink = extractMeetingLink(location, description)

                    events.add(
                        EventDto(
                            id = instanceId,
                            title = title,
                            startEpochMillis = startMs,
                            endEpochMillis = endMs,
                            location = location,
                            description = description,
                            meetingLink = meetingLink,
                            allDay = allDay,
                            calendarId = calendarId,
                            calendarName = calInfo?.name,
                            color = finalColor,
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Permission not granted
        }

        return events
    }

    private data class CalendarInfo(
        val name: String,
        val color: Int?,
    )

    private fun resolveCalendarInfo(contentResolver: ContentResolver): Map<String, CalendarInfo> {
        val result = mutableMapOf<String, CalendarInfo>()
        try {
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR,
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val nameCol = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val colorCol = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_COLOR)
                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol).toString() else null
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val color = if (colorCol >= 0) cursor.getInt(colorCol) else null
                    if (id != null && name != null) {
                        result[id] = CalendarInfo(name, color)
                    }
                }
            }
        } catch (e: SecurityException) { }
        return result
    }

    private fun extractMeetingLink(location: String?, description: String?): String? {
        val text = listOfNotNull(location, description).joinToString(" ").trim()
        if (text.isEmpty()) return null
        return urlRegex.find(text)?.value
    }

    data class SnapshotDto(
        val dateLocal: String,
        val timezoneId: String,
        val events: List<EventDto>,
        val lastSyncAt: Long,
    )

    data class EventDto(
        val id: String,
        val title: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val location: String? = null,
        val description: String? = null,
        val meetingLink: String? = null,
        val allDay: Boolean = false,
        val calendarId: String? = null,
        val calendarName: String? = null,
        val color: Int? = null,
    )
}

/**
 * Listens for sync requests from the watch and pushes calendar data via the Data Layer.
 */
class CalendarSyncService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == MESSAGE_PATH_REQUEST_SYNC) {
            serviceScope.launch {
                CalendarSyncHelper.syncToWatch(applicationContext)
            }
        }
    }

    companion object {
        const val MESSAGE_PATH_REQUEST_SYNC = "/watch_face/request_sync"
    }
}
