package com.example.myapplication.data.model

import java.time.Instant

/** Calendar event for the current day. */
data class Event(
    val id: String,
    val title: String,
    val startDateTime: Instant,
    val endDateTime: Instant,
    val location: String? = null,
    val description: String? = null,
    val meetingLink: String? = null,
    val allDay: Boolean = false,
    val calendarId: String? = null,
    val calendarName: String? = null,  // Display name of the calendar
    val color: Int? = null,  // Event or calendar display color from Google Calendar
)
