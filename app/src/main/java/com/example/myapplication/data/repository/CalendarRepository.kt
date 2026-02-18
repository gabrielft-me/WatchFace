package com.example.myapplication.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import com.example.myapplication.data.model.DailySnapshot
import com.example.myapplication.data.model.Event
import com.example.myapplication.data.repository.SunTimesRepository
import com.example.myapplication.data.model.Task
import com.example.myapplication.data.util.extractMeetingLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarRepository(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : CalendarSnapshotSource {
    private val _snapshot = MutableStateFlow<DailySnapshot?>(null)
    override val snapshot: StateFlow<DailySnapshot?> = _snapshot

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val calendarEvents = fetchCalendarEvents()
            val today = LocalDate.now(zoneId)

            _snapshot.value = DailySnapshot(
                dateLocal = today.toString(),
                timezoneId = zoneId.id,
                events = calendarEvents,
                tasks = emptyList(),
                lastSyncAt = Instant.now(),
            )
        }
    }

    override suspend fun updateTask(task: Task) {
        // Tasks are read-only from calendar, no-op for now
        val current = _snapshot.value ?: return
        val updated = current.tasks.map { if (it.id == task.id) task else it }
        _snapshot.value = current.copy(tasks = updated, lastSyncAt = Instant.now())
    }

    suspend fun getSunTimesForDate(date: LocalDate): SunTimesRepository.SunTimesData {
        val offsetSeconds = zoneId.rules.getOffset(Instant.now()).totalSeconds
        val lon = (offsetSeconds / 3600.0) * 15.0
        return SunTimesRepository.computeSunTimesForDate(
            date = date,
            latitude = 40.0,
            longitude = lon.coerceIn(-180.0, 180.0),
            zoneId = zoneId,
        )
    }

    /** Fallback when Instances returns nothing (e.g. some Wear OS providers). */
    private fun fetchEventsTableFallback(
        contentResolver: ContentResolver,
        calendarInfo: Map<String, CalendarInfo>,
        startMillis: Long,
        endMillis: Long,
    ): List<Event> {
        val list = mutableListOf<Event>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.DISPLAY_COLOR,
        )
        val selection = "${CalendarContract.Events.DTEND} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())
        try {
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { cursor ->
                Log.d(TAG, "Events table fallback cursor count: ${cursor.count}")
                val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                val locIdx = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                val descIdx = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val allDayIdx = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)
                val calIdIdx = cursor.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
                val colorIdx = cursor.getColumnIndex(CalendarContract.Events.DISPLAY_COLOR)
                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx).toString() else ""
                    val title = if (titleIdx >= 0) cursor.getString(titleIdx) ?: "No Title" else "No Title"
                    val startMs = if (startIdx >= 0) cursor.getLong(startIdx) else 0L
                    val endMs = if (endIdx >= 0) cursor.getLong(endIdx) else startMs
                    val location = if (locIdx >= 0) cursor.getString(locIdx) else null
                    val description = if (descIdx >= 0) cursor.getString(descIdx) else null
                    val allDay = if (allDayIdx >= 0) cursor.getInt(allDayIdx) == 1 else false
                    val calendarId = if (calIdIdx >= 0) cursor.getString(calIdIdx) else null
                    val displayColor = if (colorIdx >= 0) {
                        val colorVal = cursor.getInt(colorIdx)
                        Log.d(TAG, "[FALLBACK] Event $id - raw displayColor from cursor: $colorVal")
                        colorVal
                    } else {
                        Log.d(TAG, "[FALLBACK] Event $id - displayColor column not found (colorIdx=$colorIdx)")
                        null
                    }
                    val calInfo = calendarId?.let { calendarInfo[it] }
                    val finalColor = displayColor ?: calInfo?.color
                    Log.d(TAG, "[FALLBACK] Event '$title' (id:$id, calId:$calendarId) - displayColor:$displayColor (${displayColor?.let{"0x${Integer.toHexString(it)}"} ?: "null"}), calendarColor:${calInfo?.color} (${calInfo?.color?.let{"0x${Integer.toHexString(it)}"} ?: "null"}), finalColor:$finalColor (${finalColor?.let{"0x${Integer.toHexString(it)}"} ?: "null"})")
                    list.add(
                        Event(
                            id = "${id}_$startMs",
                            title = title,
                            startDateTime = Instant.ofEpochMilli(startMs),
                            endDateTime = Instant.ofEpochMilli(endMs),
                            location = location,
                            description = description,
                            meetingLink = extractMeetingLink(location, description),
                            allDay = allDay,
                            calendarId = calendarId,
                            calendarName = calInfo?.name,
                            color = finalColor,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Events table fallback failed", e)
        }
        return list
    }

    /** Log content providers that might expose calendar data (for diagnostics). */
    private fun logCalendarRelatedProviders() {
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PROVIDERS)
            val calendarLike = packages.flatMap { pkg: android.content.pm.PackageInfo ->
                val providers = pkg.providers ?: emptyArray()
                providers
                    .filter { pi: android.content.pm.ProviderInfo -> pi.authority?.contains("calendar", ignoreCase = true) == true }
                    .map { pi -> "${pi.authority} (${pkg.packageName})" }
            }
            Log.d(TAG, "Calendar-related content providers on device: $calendarLike")
        } catch (e: Exception) {
            Log.w(TAG, "Could not list providers", e)
        }
    }

    /**
     * Try to read from WearableCalendarContract (Wear OS local sync - 24h window).
     * Uses reflection so we don't require a specific dependency; works if wear-sdk provides the class.
     */
    private fun fetchFromWearableCalendarContract(
        contentResolver: ContentResolver,
        beginMillis: Long,
        endMillis: Long,
    ): List<Event> {
        val events = mutableListOf<Event>()
        try {
            val contractClass = Class.forName("android.support.wearable.provider.WearableCalendarContract")
            val instancesClass = contractClass.getDeclaredClasses().find { it.simpleName == "Instances" }
                ?: return events
            val contentUriField = instancesClass.getField("CONTENT_URI")
            @Suppress("UNCHECKED_CAST")
            val baseUri = contentUriField.get(null) as Uri
            val uri = baseUri.buildUpon().let { b ->
                ContentUris.appendId(b, beginMillis)
                ContentUris.appendId(b, endMillis)
                b.build()
            }
            Log.d(TAG, "[WEARABLE] Querying WearableCalendarContract URI: $uri")
            val projection = arrayOf(
                "event_id", "title", "begin", "end",
                "eventLocation", "description", "allDay", "calendar_id", "displayColor"
            )
            Log.d(TAG, "[WEARABLE] Projection: ${projection.joinToString()}")
            contentResolver.query(uri, projection, null, null, "begin ASC")?.use { cursor ->
                Log.d(TAG, "[WEARABLE] WearableCalendarContract cursor count: ${cursor.count}")
                Log.d(TAG, "[WEARABLE] WearableCalendarContract cursor columns: ${cursor.columnNames.joinToString()}")
                val idIdx = cursor.getColumnIndex("event_id")
                val titleIdx = cursor.getColumnIndex("title")
                val startIdx = cursor.getColumnIndex("begin")
                val endIdx = cursor.getColumnIndex("end")
                val locIdx = cursor.getColumnIndex("eventLocation")
                val descIdx = cursor.getColumnIndex("description")
                val allDayIdx = cursor.getColumnIndex("allDay")
                val calIdIdx = cursor.getColumnIndex("calendar_id")
                val colorIdx = cursor.getColumnIndex("displayColor")
                Log.d(TAG, "[WEARABLE] Column indices - event_id:$idIdx displayColor:$colorIdx")
                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx).toString() else ""
                    val title = if (titleIdx >= 0) cursor.getString(titleIdx) ?: "No Title" else "No Title"
                    val startMs = if (startIdx >= 0) cursor.getLong(startIdx) else 0L
                    val endMs = if (endIdx >= 0) cursor.getLong(endIdx) else startMs
                    val location = if (locIdx >= 0) cursor.getString(locIdx) else null
                    val description = if (descIdx >= 0) cursor.getString(descIdx) else null
                    val allDay = if (allDayIdx >= 0) cursor.getInt(allDayIdx) == 1 else false
                    val calendarId = if (calIdIdx >= 0) cursor.getString(calIdIdx) else null
                    val displayColor = if (colorIdx >= 0) {
                        val colorVal = cursor.getInt(colorIdx)
                        Log.d(TAG, "[WEARABLE] Event $id - raw displayColor: $colorVal (0x${Integer.toHexString(colorVal)})")
                        colorVal
                    } else {
                        Log.d(TAG, "[WEARABLE] Event $id - displayColor column not found")
                        null
                    }
                    events.add(
                        Event(
                            id = "${id}_$startMs",
                            title = title,
                            startDateTime = Instant.ofEpochMilli(startMs),
                            endDateTime = Instant.ofEpochMilli(endMs),
                            location = location,
                            description = description,
                            meetingLink = extractMeetingLink(location, description),
                            allDay = allDay,
                            calendarId = calendarId,
                            calendarName = null,
                            color = displayColor,
                        )
                    )
                    Log.d(TAG, "[WEARABLE] Added event '$title' with color: $displayColor (${displayColor?.let{"0x${Integer.toHexString(it)}"} ?: "null"})")
                }
            }
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "[WEARABLE] WearableCalendarContract not available (ClassNotFoundException)")
        } catch (e: Exception) {
            Log.w(TAG, "WearableCalendarContract query failed", e)
        }
        return events
    }

    /** Try Wear calendar providers: Samsung watch and Google wearable (from device provider list). */
    private fun fetchFromAlternativeCalendarAuthorities(
        contentResolver: ContentResolver,
        startMillis: Long,
        endMillis: Long,
        calendarInfo: Map<String, CalendarInfo>,
    ): List<Event> {
        Log.d(TAG, "[ALT_AUTH] === Trying Alternative Calendar Authorities ===")
        val authorities = listOf(
            "com.samsung.android.calendar.watch",                    // Samsung Calendar on Watch
            "com.google.android.wearable.provider.calendar",         // Google Wear calendar
            "com.samsung.android.watch.watchface.complication.calendar", // Samsung watchface complication
            "com.samsung.android.calendar",                         // Samsung Calendar (generic)
        )
        val pathVariants = listOf("instances/when", "instances", "events")
        val projectionFull = arrayOf("event_id", "title", "begin", "end", "eventLocation", "description", "allDay", "calendar_id", "displayColor")
        val projectionMinimal = arrayOf("_id", "title", "dtstart", "dtend")
        for (authority in authorities) {
            for (pathSegment in pathVariants) {
                for (projection in listOf(projectionFull, projectionMinimal)) {
                    try {
                        val uri = Uri.parse("content://$authority/$pathSegment").buildUpon().let { b ->
                            ContentUris.appendId(b, startMillis)
                            ContentUris.appendId(b, endMillis)
                            b.build()
                        }
                        val list = queryInstancesUri(contentResolver, uri, projection, calendarInfo)
                        if (list.isNotEmpty()) {
                            Log.d(TAG, "[ALT_AUTH] ✓ Found ${list.size} events from authority: $authority path: $pathSegment with projection: ${projection.joinToString()}")
                            return list
                        } else {
                            Log.d(TAG, "[ALT_AUTH] ✗ No events from authority: $authority path: $pathSegment")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "[ALT_AUTH] Exception for authority $authority path $pathSegment: ${e.message}")
                    }
                }
            }
        }
        Log.d(TAG, "[ALT_AUTH] No events found from any alternative authority")
        return emptyList()
    }

    private fun queryInstancesUri(
        contentResolver: ContentResolver,
        uri: Uri,
        projection: Array<String>,
        calendarInfo: Map<String, CalendarInfo>,
    ): List<Event> {
        val events = mutableListOf<Event>()
        Log.d(TAG, "[QUERY] Querying URI: $uri with projection: ${projection.joinToString()}")
        // Google wearable provider doesn't support sort order - try without it
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            Log.d(TAG, "[QUERY] Cursor count: ${cursor.count}, columns: ${cursor.columnNames.joinToString()}")
            Log.d("CAL", "columns=" + cursor.columnNames.joinToString())
            val idIdx = cursor.getColumnIndex("event_id").takeIf { it >= 0 } ?: cursor.getColumnIndex("_id")
            val titleIdx = cursor.getColumnIndex("title")
            val startIdx = cursor.getColumnIndex("begin").takeIf { it >= 0 } ?: cursor.getColumnIndex("dtstart")
            val endIdx = cursor.getColumnIndex("end").takeIf { it >= 0 } ?: cursor.getColumnIndex("dtend")
            if (startIdx < 0) {
                Log.w(TAG, "[QUERY] Missing start time column!")
                return@use
            }
            val locIdx = cursor.getColumnIndex("eventLocation").takeIf { it >= 0 } ?: cursor.getColumnIndex("event_location")
            val descIdx = cursor.getColumnIndex("description")
            val allDayIdx = cursor.getColumnIndex("allDay").takeIf { it >= 0 } ?: cursor.getColumnIndex("all_day")
            val calIdIdx = cursor.getColumnIndex("calendar_id").takeIf { it >= 0 } ?: cursor.getColumnIndex("cal_id")
            val colorIdx = cursor.getColumnIndex("displayColor").takeIf { it >= 0 } ?: cursor.getColumnIndex("display_color")
            Log.d(TAG, "[QUERY] Column indices - id:$idIdx title:$titleIdx start:$startIdx color:$colorIdx")
            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getLong(idIdx).toString() else ""
                val title = if (titleIdx >= 0) cursor.getString(titleIdx) ?: "No Title" else "No Title"
                val startMs = cursor.getLong(startIdx)
                val endMs = if (endIdx >= 0) cursor.getLong(endIdx) else startMs
                val location = if (locIdx >= 0) cursor.getString(locIdx) else null
                val description = if (descIdx >= 0) cursor.getString(descIdx) else null
                val allDay = when {
                    allDayIdx >= 0 -> cursor.getInt(allDayIdx) == 1
                    else -> false
                }
                val calendarId = if (calIdIdx >= 0) cursor.getString(calIdIdx) else null
                val displayColor = if (colorIdx >= 0) {
                    val colorVal = cursor.getInt(colorIdx)
                    // Google Wearable Provider returns 0 for events without color, treat as null
                    if (colorVal == 0) {
                        Log.d(TAG, "[QUERY] Event '$title' (id:$id, calId:$calendarId) - displayColor is 0, treating as null")
                        null
                    } else {
                        Log.d(TAG, "[QUERY] Event '$title' (id:$id, calId:$calendarId) - raw displayColor: $colorVal (0x${Integer.toHexString(colorVal)})")
                        colorVal
                    }
                } else {
                    Log.d(TAG, "[QUERY] Event '$title' (id:$id, calId:$calendarId) - displayColor column not found (colorIdx=$colorIdx)")
                    null
                }
                
                // Fallback to calendar color if event has no color
                val calInfo = calendarId?.let { calendarInfo[it] }
                val finalColor = displayColor ?: calInfo?.color
                
                if (displayColor == null && calInfo?.color != null) {
                    Log.d(TAG, "[QUERY] Event '$title' - Using calendar fallback color: ${calInfo.color} (0x${Integer.toHexString(calInfo.color)})")
                }
                
                events.add(
                    Event(
                        id = "${id}_$startMs",
                        title = title,
                        startDateTime = Instant.ofEpochMilli(startMs),
                        endDateTime = Instant.ofEpochMilli(endMs),
                        location = location,
                        description = description,
                        meetingLink = extractMeetingLink(location, description),
                        allDay = allDay,
                        calendarId = calendarId,
                        calendarName = calInfo?.name,
                        color = finalColor,
                    )
                )
                Log.d(TAG, "[QUERY] ✓ Added event '$title' with finalColor: $finalColor (${finalColor?.let{"0x${Integer.toHexString(it)}"} ?: "NO COLOR"})")
            }
        }
        Log.d(TAG, "[QUERY] Query complete. Returning ${events.size} events")
        return events
    }

    private data class CalendarInfo(
        val name: String,
        val color: Int?,
    )

    private fun resolveCalendarInfo(contentResolver: ContentResolver): Map<String, CalendarInfo> {
        val result = mutableMapOf<String, CalendarInfo>()
        Log.d(TAG, "=== STARTING CALENDAR INFO RESOLUTION ===")
        try {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR,
            )
            Log.d(TAG, "Querying Calendars table with projection: ${projection.joinToString()}")
            
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                Log.d(TAG, "Calendars cursor count: ${cursor.count}")
                Log.d(TAG, "Calendars cursor columns: ${cursor.columnNames.joinToString()}")
                
                val idCol = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val nameCol = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val colorCol = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_COLOR)
                
                Log.d(TAG, "Column indices - _ID:$idCol CALENDAR_DISPLAY_NAME:$nameCol CALENDAR_COLOR:$colorCol")
                
                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol).toString() else null
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val color = if (colorCol >= 0) {
                        val colorVal = cursor.getInt(colorCol)
                        Log.d(TAG, "Raw color value from cursor: $colorVal (0x${Integer.toHexString(colorVal)})")
                        colorVal
                    } else null
                    
                    if (id != null && name != null) {
                        result[id] = CalendarInfo(name, color)
                        Log.d(TAG, "✓ Calendar resolved - id:$id name:'$name' color:$color (${color?.let { "0x${Integer.toHexString(it)}" } ?: "null"})")
                    } else {
                        Log.w(TAG, "✗ Skipping calendar - id:$id name:$name (missing required field)")
                    }
                }
                Log.d(TAG, "Total calendars resolved: ${result.size}")
            } ?: run {
                Log.w(TAG, "Calendars query returned null cursor!")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException resolving calendar info", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception resolving calendar info", e)
        }
        Log.d(TAG, "=== FINISHED CALENDAR INFO RESOLUTION - ${result.size} calendars ===")
        return result
    }

    private fun fetchCalendarEvents(): List<Event> {
        val contentResolver: ContentResolver = context.contentResolver
        val events = mutableListOf<Event>()

        logCalendarRelatedProviders()

        // 1) Try Wear OS calendar providers: fetch 24h past + 7 days ahead (show full week)
        val now = System.currentTimeMillis()
        val twentyFourHoursPastMs = 24 * 60 * 60 * 1000L
        val sevenDaysAheadMs = 7 * 24 * 60 * 60 * 1000L
        
        // Resolve calendar info FIRST so we can use it as fallback
        val calendarInfo = resolveCalendarInfo(contentResolver)
        Log.d(TAG, "=== CALENDAR INFO SUMMARY (for fallback) ===")
        calendarInfo.forEach { (id, info) ->
            Log.d(TAG, "  Calendar ID $id: name='${info.name}' color=${info.color} (${info.color?.let{"0x${Integer.toHexString(it)}"} ?: "null"})")
        }
        
        var wearEvents = fetchFromWearableCalendarContract(contentResolver, now - twentyFourHoursPastMs, now + sevenDaysAheadMs)
        if (wearEvents.isEmpty()) {
            wearEvents = fetchFromAlternativeCalendarAuthorities(contentResolver, now - twentyFourHoursPastMs, now + sevenDaysAheadMs, calendarInfo)
        }
        if (wearEvents.isNotEmpty()) {
            Log.d(TAG, "Using Wear calendar provider (24h past + 7d ahead): ${wearEvents.size} event(s)")
            wearEvents.forEachIndexed { index, event ->
                Log.d(TAG, "  [$index] ${event.title} | ${event.startDateTime} -> ${event.endDateTime} | allDay=${event.allDay} | calendar=${event.calendarName ?: event.calendarId ?: "?"} | color=${event.color} (${event.color?.let{"0x${Integer.toHexString(it)}"} ?: "NO COLOR"})")
            }
            return wearEvents
        }

        // 2) Standard CalendarContract (7-day window)
        val today = LocalDate.now(zoneId)
        val startOfRange = ZonedDateTime.of(today.minusDays(3), LocalTime.MIDNIGHT, zoneId)
        val endOfRange = ZonedDateTime.of(today.plusDays(4), LocalTime.MIDNIGHT, zoneId)

        val startMillis = startOfRange.toInstant().toEpochMilli()
        val endMillis = endOfRange.toInstant().toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_ID,
        )

        // Use Instances table to get recurring events properly expanded
        val instancesProjection = arrayOf(
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

        val uri: Uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { builder ->
            ContentUris.appendId(builder, startMillis)
            ContentUris.appendId(builder, endMillis)
            builder.build()
        }

        Log.d(TAG, "Calendars found: ${calendarInfo.size} | query range: $startMillis -> $endMillis | uri: $uri")

        var cursor: Cursor? = null
        try {
            Log.d(TAG, "=== QUERYING INSTANCES TABLE ===")
            Log.d(TAG, "Instances URI: $uri")
            Log.d(TAG, "Instances projection: ${instancesProjection.joinToString()}")
            
            cursor = contentResolver.query(
                uri,
                instancesProjection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )

            Log.d(TAG, "Instances cursor: ${if (cursor == null) "null" else "count=${cursor.count}"}")
            cursor?.let {
                Log.d(TAG, "Instances cursor columns: ${it.columnNames.joinToString()}")
            }

            cursor?.let {
                val idIndex = it.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val titleIndex = it.getColumnIndex(CalendarContract.Instances.TITLE)
                val startIndex = it.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIndex = it.getColumnIndex(CalendarContract.Instances.END)
                val locationIndex = it.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val descriptionIndex = it.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
                val allDayIndex = it.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val calendarIdIndex = it.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
                val colorIndex = it.getColumnIndex(CalendarContract.Instances.DISPLAY_COLOR)

                while (it.moveToNext()) {
                    val id = if (idIndex >= 0) it.getLong(idIndex).toString() else ""
                    val title = if (titleIndex >= 0) it.getString(titleIndex) ?: "No Title" else "No Title"
                    val startMs = if (startIndex >= 0) it.getLong(startIndex) else 0L
                    val endMs = if (endIndex >= 0) it.getLong(endIndex) else startMs
                    val location = if (locationIndex >= 0) it.getString(locationIndex) else null
                    val description = if (descriptionIndex >= 0) it.getString(descriptionIndex) else null
                    val allDay = if (allDayIndex >= 0) it.getInt(allDayIndex) == 1 else false
                    val calendarId = if (calendarIdIndex >= 0) it.getString(calendarIdIndex) else null
                    val displayColor = if (colorIndex >= 0) {
                        val colorVal = it.getInt(colorIndex)
                        Log.d(TAG, "[INSTANCES] Event $id - raw displayColor from cursor: $colorVal")
                        colorVal
                    } else {
                        Log.d(TAG, "[INSTANCES] Event $id - displayColor column not found (colorIndex=$colorIndex)")
                        null
                    }

                    // Create unique ID for this instance (event ID + start time)
                    val instanceId = "${id}_${startMs}"

                    val calInfo = calendarId?.let { calendarInfo[it] }
                    val finalColor = displayColor ?: calInfo?.color
                    Log.d(TAG, "[INSTANCES] Event '$title' (id:$id, calId:$calendarId) - displayColor:$displayColor (${displayColor?.let{"0x${Integer.toHexString(it)}"} ?: "null"}), calendarColor:${calInfo?.color} (${calInfo?.color?.let{"0x${Integer.toHexString(it)}"} ?: "null"}), finalColor:$finalColor (${finalColor?.let{"0x${Integer.toHexString(it)}"} ?: "null"})")
                    val event = Event(
                        id = instanceId,
                        title = title,
                        startDateTime = Instant.ofEpochMilli(startMs),
                        endDateTime = Instant.ofEpochMilli(endMs),
                        location = location,
                        description = description,
                        meetingLink = extractMeetingLink(location, description),
                        allDay = allDay,
                        calendarId = calendarId,
                        calendarName = calInfo?.name,
                        color = finalColor,
                    )
                    events.add(event)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException reading calendar", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading calendar", e)
        } finally {
            cursor?.close()
        }

        // Fallback: on some Wear devices Instances can be empty; try Events table directly
        if (events.isEmpty()) {
            Log.d(TAG, "Instances empty, trying Events table fallback")
            events.addAll(fetchEventsTableFallback(contentResolver, calendarInfo, startMillis, endMillis))
        }

        // Fallback: try alternative content authorities (e.g. Samsung or other OEM calendar on Wear)
        if (events.isEmpty()) {
            events.addAll(fetchFromAlternativeCalendarAuthorities(contentResolver, startMillis, endMillis, calendarInfo))
        }

        Log.d(TAG, "Calendar events read: ${events.size} event(s)")
        events.forEachIndexed { index, event ->
            Log.d(TAG, "  [$index] ${event.title} | ${event.startDateTime} -> ${event.endDateTime} | allDay=${event.allDay} | calendar=${event.calendarName ?: event.calendarId ?: "?"} | color=${event.color} (${event.color?.let{"0x${Integer.toHexString(it)}"} ?: "NO COLOR"})")
        }

        return events
    }

    companion object {
        private const val TAG = "CalendarRepository"
    }
}
