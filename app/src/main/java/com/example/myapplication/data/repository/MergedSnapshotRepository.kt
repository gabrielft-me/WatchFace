package com.example.myapplication.data.repository

import com.example.myapplication.data.model.DailySnapshot
import com.example.myapplication.data.model.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/**
 * Combines calendar snapshot with sleep data and sun times into a single [SnapshotRepository].
 */
class MergedSnapshotRepository(
    private val calendarRepository: CalendarSnapshotSource,
    private val sleepRepository: SleepRepository,
    private val sunTimesRepository: SunTimesRepository,
    scope: CoroutineScope,
) : SnapshotRepository {

    override val snapshot: StateFlow<DailySnapshot?> = combine(
        calendarRepository.snapshot,
        sleepRepository.sleepData,
        sunTimesRepository.sunTimes,
    ) { snap, sleep, sunTimes ->
        snap?.copy(
            sleep = sleep,
            sunriseMinutes = sunTimes?.sunriseMinutes,
            sunsetMinutes = sunTimes?.sunsetMinutes,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null,
    )

    override suspend fun refresh() {
        calendarRepository.refresh()
        sleepRepository.refresh()
        // Note: sunTimesRepository refresh is called separately with location
    }

    suspend fun refreshSunTimes(latitude: Double, longitude: Double) {
        sunTimesRepository.refresh(latitude, longitude)
    }

    fun useDefaultSunTimes() {
        sunTimesRepository.useDefaults()
    }

    override suspend fun updateTask(task: Task) {
        calendarRepository.updateTask(task)
    }

    override suspend fun getSunTimesForDate(date: LocalDate): SunTimesRepository.SunTimesData {
        return sunTimesRepository.computeForDate(date)
    }
}
