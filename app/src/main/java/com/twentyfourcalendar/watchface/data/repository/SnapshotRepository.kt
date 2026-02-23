package com.twentyfourcalendar.watchface.data.repository

import com.twentyfourcalendar.watchface.data.model.DailySnapshot
import com.twentyfourcalendar.watchface.data.model.Task
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

interface SnapshotRepository {
    val snapshot: StateFlow<DailySnapshot?>

    suspend fun refresh()

    suspend fun updateTask(task: Task)

    /** Dynamically computes sunrise/sunset for the given date (no hardcoded fallbacks). */
    suspend fun getSunTimesForDate(date: LocalDate): SunTimesRepository.SunTimesData
}
