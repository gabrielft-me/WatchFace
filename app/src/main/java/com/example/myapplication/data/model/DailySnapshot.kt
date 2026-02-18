package com.example.myapplication.data.model

import java.time.Instant

/** Payload sent to the watch for a given local day. */
data class DailySnapshot(
    val dateLocal: String,
    val timezoneId: String,
    val events: List<Event>,
    val tasks: List<Task>,
    val lastSyncAt: Instant,
    val sleep: SleepData? = null,
    val sunriseMinutes: Int? = null,  // Minutes since midnight
    val sunsetMinutes: Int? = null,   // Minutes since midnight
)
