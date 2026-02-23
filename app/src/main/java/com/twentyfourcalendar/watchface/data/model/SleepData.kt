package com.twentyfourcalendar.watchface.data.model

import java.time.Instant
import java.time.LocalDate

/** Sleep session from Health Connect (e.g. Samsung Health). */
data class SleepData(
    val date: LocalDate,
    val startTime: Instant,
    val endTime: Instant,
    val durationMinutes: Int,
    val qualityScore: Int?,  // 0–100, derived from stages; null if unknown
)
