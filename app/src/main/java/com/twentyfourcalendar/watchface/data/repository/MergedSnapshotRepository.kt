package com.twentyfourcalendar.watchface.data.repository

import com.twentyfourcalendar.watchface.data.model.DailySnapshot
import com.twentyfourcalendar.watchface.data.model.Task
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
        started = SharingStarted.Eagerly,
        // Use the already-loaded cache as the initial value so that snapshot.value
        // is non-null on the very first render frame, without any coroutine timing race.
        // calendarRepository._snapshot was populated synchronously by loadFromLocalCache()
        // before this repository was created.
        initialValue = calendarRepository.snapshot.value?.copy(
            sleep = sleepRepository.sleepData.value,
            sunriseMinutes = sunTimesRepository.sunTimes.value?.sunriseMinutes,
            sunsetMinutes = sunTimesRepository.sunTimes.value?.sunsetMinutes,
        ),
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
