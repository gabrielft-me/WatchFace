package com.example.myapplication.data.repository

import com.example.myapplication.data.model.DailySnapshot
import com.example.myapplication.data.model.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class InMemorySnapshotRepository(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : SnapshotRepository {
    private val _snapshot = MutableStateFlow<DailySnapshot?>(null)
    override val snapshot: StateFlow<DailySnapshot?> = _snapshot

    override suspend fun refresh() {
        val today = LocalDate.now(zoneId)
        _snapshot.value = DailySnapshot(
            dateLocal = today.toString(),
            timezoneId = zoneId.id,
            events = emptyList(),
            tasks = emptyList(),
            lastSyncAt = Instant.now(),
        )
    }

    override suspend fun updateTask(task: Task) {
        val current = _snapshot.value ?: return
        val updated = current.tasks.map { if (it.id == task.id) task else it }
        _snapshot.value = current.copy(tasks = updated, lastSyncAt = Instant.now())
    }

    override suspend fun getSunTimesForDate(date: LocalDate): SunTimesRepository.SunTimesData {
        val offsetSeconds = zoneId.rules.getOffset(Instant.now()).totalSeconds
        val lon = (offsetSeconds / 3600.0) * 15.0
        return SunTimesRepository.computeSunTimesForDate(
            date = date,
            latitude = 40.0,
            longitude = lon.coerceIn(-180.0, 180.0),
            zoneId = zoneId,
        )
    }

}
