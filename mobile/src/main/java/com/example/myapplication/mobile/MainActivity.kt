package com.example.myapplication.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.myapplication.mobile.sync.CalendarSyncHelper
import com.example.myapplication.mobile.sync.CalendarSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Phone companion app. Requests calendar permission, syncs events to the watch,
 * and displays the synced events so the user can see what the watch will show.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var calendarObserver: ContentObserver? = null

    private val eventsState = mutableStateOf<List<CalendarSyncHelper.EventDto>>(emptyList())
    private val lastSyncState = mutableStateOf<String?>(null)
    private val isSyncingState = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            triggerSync()
            CalendarSyncScheduler.schedule(this)
            startObservingCalendar()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompanionScreen(
                    events = eventsState.value,
                    lastSync = lastSyncState.value,
                    isSyncing = isSyncingState.value,
                    onSyncNow = { triggerSync() }
                )
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            == PackageManager.PERMISSION_GRANTED
        ) {
            triggerSync()
            CalendarSyncScheduler.schedule(this)
            startObservingCalendar()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    private fun triggerSync() {
        scope.launch {
            isSyncingState.value = true
            withContext(Dispatchers.IO) {
                CalendarSyncHelper.syncToWatch(applicationContext)
                val events = CalendarSyncHelper.fetchEvents(applicationContext)
                withContext(Dispatchers.Main) {
                    eventsState.value = events
                    val now = java.time.LocalTime.now()
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    lastSyncState.value = now
                    isSyncingState.value = false
                }
            }
        }
    }

    private fun startObservingCalendar() {
        val handler = Handler(Looper.getMainLooper())
        calendarObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scope.launch(Dispatchers.IO) {
                    CalendarSyncHelper.syncToWatch(applicationContext)
                    val events = CalendarSyncHelper.fetchEvents(applicationContext)
                    withContext(Dispatchers.Main) {
                        eventsState.value = events
                        val now = java.time.LocalTime.now()
                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        lastSyncState.value = now
                    }
                }
            }
        }
        contentResolver.registerContentObserver(
            CalendarContract.Events.CONTENT_URI, true, calendarObserver!!
        )
        contentResolver.registerContentObserver(
            CalendarContract.Instances.CONTENT_URI, true, calendarObserver!!
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        calendarObserver?.let { contentResolver.unregisterContentObserver(it) }
    }
}

// ─── Composables ─────────────────────────────────────────────────────────────

@Composable
fun CompanionScreen(
    events: List<CalendarSyncHelper.EventDto>,
    lastSync: String?,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
) {
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)

    // Group events by local date, exclude all-day events from the timeline
    val grouped: Map<LocalDate, List<CalendarSyncHelper.EventDto>> = events
        .filter { !it.allDay }
        .groupBy { event ->
            Instant.ofEpochMilli(event.startEpochMillis)
                .atZone(zoneId)
                .toLocalDate()
        }
        .toSortedMap()

    val allDayEvents = events.filter { it.allDay }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0D0D0D)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            item {
                CompanionHeader(
                    eventCount = events.size,
                    lastSync = lastSync,
                    isSyncing = isSyncing,
                    onSyncNow = onSyncNow
                )
            }

            if (events.isEmpty() && !isSyncing) {
                item { EmptyState() }
            }

            // ── All-day events ───────────────────────────────────────────────
            if (allDayEvents.isNotEmpty()) {
                item {
                    DayHeader(label = "ALL DAY")
                }
                items(allDayEvents) { event ->
                    EventCard(event = event, zoneId = zoneId, allDay = true)
                }
            }

            // ── Grouped timed events ─────────────────────────────────────────
            grouped.forEach { (date, dayEvents) ->
                item {
                    DayHeader(label = formatDayHeader(date, today))
                }
                items(dayEvents) { event ->
                    EventCard(event = event, zoneId = zoneId)
                }
            }
        }
    }
}

@Composable
fun CompanionHeader(
    eventCount: Int,
    lastSync: String?,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "⌚",
                fontSize = 28.sp,
                modifier = Modifier.padding(end = 10.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Watch Face Companion",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = "$eventCount events synced to watch",
                    color = Color(0xFF999999),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Sync status badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF2A2A2A))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .weight(1f)
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        color = Color(0xFF4FC3F7),
                        strokeWidth = 1.5.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (lastSync != null) Color(0xFF4CAF50)
                                else Color(0xFF666666)
                            )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isSyncing -> "Syncing…"
                        lastSync != null -> "Synced at $lastSync"
                        else -> "Not synced yet"
                    },
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                onClick = onSyncNow,
                enabled = !isSyncing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4FC3F7),
                    contentColor = Color.Black
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("Sync Now", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun DayHeader(label: String) {
    Text(
        text = label,
        color = Color(0xFF4FC3F7),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 0.dp)
            .padding(top = 20.dp, bottom = 6.dp)
    )
    HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp)
}

@Composable
fun EventCard(
    event: CalendarSyncHelper.EventDto,
    zoneId: ZoneId,
    allDay: Boolean = false,
) {
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    val startTime = Instant.ofEpochMilli(event.startEpochMillis)
        .atZone(zoneId)
    val endTime = Instant.ofEpochMilli(event.endEpochMillis)
        .atZone(zoneId)

    val timeLabel = if (allDay) "All day" else
        "${startTime.format(timeFmt)} – ${endTime.format(timeFmt)}"

    val eventColor = event.color
        ?.let { Color(it) }
        ?: Color(0xFF4FC3F7)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1C1C1C))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Color dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(eventColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeLabel,
                    color = Color(0xFF999999),
                    fontSize = 12.sp
                )
                event.calendarName?.let { cal ->
                    Text(
                        text = "  ·  $cal",
                        color = Color(0xFF666666),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!event.location.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "📍 ${event.location}",
                    color = Color(0xFF666666),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Meeting link badge
        if (event.meetingLink != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1E3A4A))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "🔗 Link",
                    color = Color(0xFF4FC3F7),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "📅", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No events found",
            color = Color(0xFF999999),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Events from your calendar will appear here",
            color = Color(0xFF555555),
            fontSize = 13.sp
        )
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun formatDayHeader(date: LocalDate, today: LocalDate): String {
    val dayName = when (date) {
        today -> "TODAY"
        today.plusDays(1) -> "TOMORROW"
        today.minusDays(1) -> "YESTERDAY"
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase()
    }
    val monthDay = date.dayOfMonth
    val month = date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
    return "$dayName, $month $monthDay"
}
