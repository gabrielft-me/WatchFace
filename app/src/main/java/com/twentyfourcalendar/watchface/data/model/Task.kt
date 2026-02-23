package com.twentyfourcalendar.watchface.data.model

import java.time.Instant

data class Task(
    val id: String,
    val title: String,
    val dateTime: Instant,
    val completed: Boolean,
    val source: TaskSource,
    val editable: Boolean,
)

enum class TaskSource {
    LOCAL,
    GOOGLE_TASKS_IF_FUTURE,
}
