package com.twentyfourcalendar.watchface.data.repository

import com.twentyfourcalendar.watchface.data.model.DailySnapshot
import com.twentyfourcalendar.watchface.data.model.Task
import kotlinx.coroutines.flow.StateFlow

/** Source of calendar snapshot data - from local CalendarContract or from phone via Data Layer. */
interface CalendarSnapshotSource {
    val snapshot: StateFlow<DailySnapshot?>
    suspend fun refresh()
    suspend fun updateTask(task: Task)
}
