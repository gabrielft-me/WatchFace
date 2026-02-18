package com.example.myapplication.data.repository

import android.content.Context
import android.net.Uri
import com.example.myapplication.data.model.DailySnapshot
import com.example.myapplication.data.model.Event
import com.example.myapplication.data.model.Task
import com.example.myapplication.data.repository.SunTimesRepository
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Gets calendar events from the phone via the Wearable Data Layer.
 * Wear OS watches don't have local calendar data - the phone companion app pushes it.
 */
class DataLayerCalendarRepository(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val onSyncCallback: ((eventCount: Int, lastSync: String) -> Unit)? = null,
) : CalendarSnapshotSource {

    private val _snapshot = MutableStateFlow<DailySnapshot?>(null)
    override val snapshot: StateFlow<DailySnapshot?> = _snapshot

    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val gson = Gson()

    init {
        dataClient.addListener { dataEvents: DataEventBuffer ->
            for (event in dataEvents) {
                if (event.type == com.google.android.gms.wearable.DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path == DATA_PATH
                ) {
                    parseFromDataItem(event.dataItem)
                }
            }
        }
        // Load any existing cached data from a previous sync
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            loadFromDataLayer()
        }
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            android.util.Log.d("DataLayerCalendar", "Requesting sync from phone...")
            requestSyncFromPhone()
            loadFromDataLayer()
        }
    }

    override suspend fun updateTask(task: Task) {
        // Tasks are read-only when synced from phone
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

    private suspend fun requestSyncFromPhone() {
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            for (node in nodes) {
                if (!node.isNearby) continue
                messageClient.sendMessage(node.id, MESSAGE_PATH_REQUEST_SYNC, ByteArray(0)).await()
                break
            }
        } catch (_: Exception) {
            // Phone may not be connected
        }
    }

    private suspend fun loadFromDataLayer() {
        try {
            val uri = Uri.parse("wear://*$DATA_PATH")
            val dataItems = dataClient.getDataItems(uri).await()
            for (item in dataItems) {
                parseFromDataItem(item)
                break
            }
        } catch (_: Exception) {
            // No data yet
        }
    }

    private fun parseFromDataItem(dataItem: com.google.android.gms.wearable.DataItem) {
        try {
            val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
            val json = dataMap.getString(KEY_SNAPSHOT_JSON) ?: return
            android.util.Log.d("DataLayerCalendar", "Received snapshot JSON from phone (${json.length} chars)")
            val dto = gson.fromJson(json, SnapshotDto::class.java)
            _snapshot.value = dto.toDailySnapshot()
            
            val eventCount = dto.events.size
            val now = java.time.LocalDateTime.now()
            val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            val timeStr = now.format(formatter)
            
            android.util.Log.d("DataLayerCalendar", "✓ Parsed $eventCount events with colors:")
            dto.events.take(5).forEach { event ->
                android.util.Log.d("DataLayerCalendar", "  - ${event.title}: color=${event.color} (${event.color?.let{"0x${Integer.toHexString(it)}"} ?: "null"})")
            }
            
            // Notify UI
            onSyncCallback?.invoke(eventCount, timeStr)
        } catch (e: Exception) {
            android.util.Log.e("DataLayerCalendar", "Failed to parse snapshot", e)
            e.printStackTrace()
        }
    }

    private data class SnapshotDto(
        val dateLocal: String,
        val timezoneId: String,
        val events: List<EventDto>,
        val lastSyncAt: Long,
    ) {
        fun toDailySnapshot() = DailySnapshot(
            dateLocal = dateLocal,
            timezoneId = timezoneId,
            events = events.map { it.toEvent() },
            tasks = emptyList(),
            lastSyncAt = Instant.ofEpochMilli(lastSyncAt),
        )
    }

    private data class EventDto(
        val id: String,
        val title: String,
        @SerializedName("startEpochMillis") val startEpochMillis: Long,
        @SerializedName("endEpochMillis") val endEpochMillis: Long,
        val location: String? = null,
        val description: String? = null,
        val meetingLink: String? = null,
        val allDay: Boolean = false,
        val calendarId: String? = null,
        val calendarName: String? = null,
        val color: Int? = null,
    ) {
        fun toEvent() = Event(
            id = id,
            title = title,
            startDateTime = Instant.ofEpochMilli(startEpochMillis),
            endDateTime = Instant.ofEpochMilli(endEpochMillis),
            location = location,
            description = description,
            meetingLink = meetingLink,
            allDay = allDay,
            calendarId = calendarId,
            calendarName = calendarName,
            color = color,
        )
    }

    companion object {
        private const val DATA_PATH = "/watch_face/calendar_snapshot"
        private const val KEY_SNAPSHOT_JSON = "snapshot_json"
        const val MESSAGE_PATH_REQUEST_SYNC = "/watch_face/request_sync"
    }
}
