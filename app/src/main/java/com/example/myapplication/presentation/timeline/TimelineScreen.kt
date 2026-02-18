package com.example.myapplication.presentation.timeline

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import androidx.core.content.res.ResourcesCompat
import com.example.myapplication.R
import com.example.myapplication.data.model.DailySnapshot
import com.example.myapplication.data.model.Event
import com.example.myapplication.data.model.SleepData
import com.example.myapplication.data.model.Task
import com.example.myapplication.data.repository.InMemorySnapshotRepository
import com.example.myapplication.data.repository.SnapshotRepository
import com.example.myapplication.data.repository.SunTimesRepository
import com.example.myapplication.data.util.IntervalMinutes
import com.example.myapplication.data.util.angleForInstantPrecise
import com.example.myapplication.data.util.angleForMinutes
import com.example.myapplication.data.util.angleFromTouch
import com.example.myapplication.data.util.assignLayers
import com.example.myapplication.data.util.backToBackMarkers
import com.example.myapplication.data.util.isAngleWithinArc
import com.example.myapplication.data.util.minutesSinceMidnight
import com.example.myapplication.data.util.normalizeAngle0To360
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun TimelineScreen(
    repository: SnapshotRepository = remember { InMemorySnapshotRepository() },
) {
    val snapshot by repository.snapshot.collectAsState()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { Log.w("TimelineScreen", "TimelineScreen mounted (rotary should work when popup is open)") }
    var selectedPopupItem by remember { mutableStateOf<TimelinePopupItem?>(null) }
    var selectedSleep by remember { mutableStateOf<SleepData?>(null) }
    var dayOffset by remember { mutableStateOf(0) }  // 0 = today, 1 = tomorrow, -1 = yesterday
    val coroutineScope = rememberCoroutineScope()
    val zoneId = remember { ZoneId.systemDefault() }

    // Sync is handled by MainActivity every minute
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }

    // Calculate the selected date based on offset
    val selectedDate = remember(now, dayOffset) {
        ZonedDateTime.ofInstant(now, zoneId).plusDays(dayOffset.toLong()).toLocalDate()
    }

    // Filter events and tasks for the selected date
    val layout = remember(snapshot, now, dayOffset) {
        snapshot?.let { snap ->
            val filteredSnapshot = filterSnapshotByDate(snap, selectedDate, zoneId)
            buildTimelineLayout(filteredSnapshot, zoneId, now)
        }
    }

    // Dynamically fetch sun times for the selected date (no hardcoded fallbacks)
    val sunTimesForSelectedDate by produceState<SunTimesRepository.SunTimesData?>(initialValue = null, selectedDate, repository) {
        value = repository.getSunTimesForDate(selectedDate)
    }

    val accumulatedRotaryForEvent = remember { mutableStateOf(0f) }
    val rotaryFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        rotaryFocusRequester.requestFocus()
        Log.d("TimelineScreen", "Rotary: root Box requested focus")
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rotaryFocusRequester)
            .focusable()
            .onRotaryScrollEvent { event ->
                Log.w("RotaryTest", "root pixels=" + event.verticalScrollPixels)
                Log.d("TimelineScreen", "Rotary: root Box received scroll pixels=${event.verticalScrollPixels}")
                if (selectedPopupItem == null || layout == null) {
                    accumulatedRotaryForEvent.value = 0f
                    Log.d("TimelineScreen", "Rotary: no popup or layout, ignoring")
                    return@onRotaryScrollEvent true
                }
                val popupItems = buildPopupItems(layout)
                val currentIdx = popupItems.indexOfFirst { item ->
                    when (val sel = selectedPopupItem) {
                        is TimelinePopupItem.Event -> item is TimelinePopupItem.Event &&
                            item.layout.event.id == sel.layout.event.id &&
                            item.layout.event.startDateTime == sel.layout.event.startDateTime &&
                            item.layout.event.endDateTime == sel.layout.event.endDateTime
                        is TimelinePopupItem.Task -> item is TimelinePopupItem.Task &&
                            item.layout.task.id == sel.layout.task.id
                        null -> false
                    }
                }
                if (currentIdx < 0) {
                    Log.d("TimelineScreen", "Rotary: currentIdx=$currentIdx, ignoring")
                    return@onRotaryScrollEvent true
                }
                // +pixels = right (clockwise) = next, -pixels = left = prev (from RotaryTest log)
                var acc = accumulatedRotaryForEvent.value + event.verticalScrollPixels
                val threshold = 6f
                when {
                    acc >= threshold -> {
                        val newIdx = (currentIdx + 1) % popupItems.size
                        selectedPopupItem = popupItems[newIdx]
                        accumulatedRotaryForEvent.value = 0f
                        Log.d("TimelineScreen", "Rotary: next (right) -> idx=$newIdx")
                    }
                    acc <= -threshold -> {
                        val newIdx = (currentIdx - 1 + popupItems.size) % popupItems.size
                        selectedPopupItem = popupItems[newIdx]
                        accumulatedRotaryForEvent.value = 0f
                        Log.d("TimelineScreen", "Rotary: prev (left) -> idx=$newIdx")
                    }
                    else -> accumulatedRotaryForEvent.value = acc
                }
                true
            }
    ) {
        if (snapshot == null) {
            Text(
                modifier = Modifier.align(Alignment.Center),
                text = "Sincronizando...",
                color = Color(0xFF9AA5B1),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            TimelineCanvas(
                layout = layout,
                snapshot = snapshot,
                selectedDate = selectedDate,
                sunriseMinutes = sunTimesForSelectedDate?.sunriseMinutes,
                sunsetMinutes = sunTimesForSelectedDate?.sunsetMinutes,
                now = now,
                dayOffset = dayOffset,
                selectedEventRef = (selectedPopupItem as? TimelinePopupItem.Event)?.layout?.event?.let {
                    SelectedEventRef(
                        id = it.id,
                        startDateTime = it.startDateTime,
                        endDateTime = it.endDateTime,
                    )
                },
                selectedEventAnchorAngle = selectedPopupItem?.angle,
                onDayOffsetChange = { newOffset ->
                    dayOffset = newOffset.coerceIn(-30, 30)
                },
                onEventTap = { event, _ ->
                    val l = layout!!
                    val current = selectedPopupItem as? TimelinePopupItem.Event
                    val isSameEvent = current?.layout?.event?.let {
                        it.id == event.id && it.startDateTime == event.startDateTime && it.endDateTime == event.endDateTime
                    } == true
                    selectedPopupItem = if (isSameEvent) null else TimelinePopupItem.Event(l.events.find { el ->
                        el.event.id == event.id && el.event.startDateTime == event.startDateTime && el.event.endDateTime == event.endDateTime
                    }!!)
                    Log.d("TimelineScreen", "EventTap: ${event.title} isSame=$isSameEvent -> popup=${selectedPopupItem != null}")
                },
                onTaskTap = { task ->
                    val l = layout!!
                    val current = selectedPopupItem as? TimelinePopupItem.Task
                    val isSameTask = current?.layout?.task?.id == task.id
                    selectedPopupItem = if (isSameTask) null else TimelinePopupItem.Task(l.tasks.find { it.task.id == task.id }!!)
                    Log.d("TimelineScreen", "TaskTap: ${task.title} isSame=$isSameTask -> popup=${selectedPopupItem != null}")
                },
                onSleepTap = { selectedSleep = it },
            )
        }

        if (selectedPopupItem != null && layout != null) {
            val popupItems = buildPopupItems(layout)
            val currentIdx = popupItems.indexOfFirst { item ->
                when (val sel = selectedPopupItem) {
                    is TimelinePopupItem.Event -> item is TimelinePopupItem.Event &&
                        item.layout.event.id == sel.layout.event.id &&
                        item.layout.event.startDateTime == sel.layout.event.startDateTime &&
                        item.layout.event.endDateTime == sel.layout.event.endDateTime
                    is TimelinePopupItem.Task -> item is TimelinePopupItem.Task &&
                        item.layout.task.id == sel.layout.task.id
                    null -> false
                }
            }
            if (currentIdx >= 0) {
                TimelineItemDetailOverlay(
                    item = selectedPopupItem!!,
                    now = now,
                    anchorAngle = selectedPopupItem!!.angle,
                    onDismiss = { selectedPopupItem = null },
                    onRotaryScrollDelta = { delta ->
                        // +delta = right = next, -delta = left = prev (from RotaryTest log)
                        var acc = accumulatedRotaryForEvent.value + delta
                        val threshold = 6f
                        when {
                            acc >= threshold -> {
                                val newIdx = (currentIdx + 1) % popupItems.size
                                selectedPopupItem = popupItems[newIdx]
                                accumulatedRotaryForEvent.value = 0f
                                Log.d("TimelineScreen", "Rotary(overlay): next (right) -> idx=$newIdx")
                            }
                            acc <= -threshold -> {
                                val newIdx = (currentIdx - 1 + popupItems.size) % popupItems.size
                                selectedPopupItem = popupItems[newIdx]
                                accumulatedRotaryForEvent.value = 0f
                                Log.d("TimelineScreen", "Rotary(overlay): prev (left) -> idx=$newIdx")
                            }
                            else -> accumulatedRotaryForEvent.value = acc
                        }
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = selectedSleep != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            selectedSleep?.let { sleep ->
                SleepDetailOverlay(
                    sleep = sleep,
                    onDismiss = { selectedSleep = null },
                )
            }
        }

    }
}

private data class TimelineLayout(
    val events: List<EventLayout>,
    val tasks: List<TaskLayout>,
    val backToBackMarkers: List<Float>,
    val allDayCount: Int,
)

private data class EventLayout(
    val event: Event,
    val startMinutes: Int,
    val endMinutes: Int,
    val startAngle: Float,
    val endAngle: Float,
    val layerIndex: Int,
    val isPast: Boolean,
)

private data class SelectedEventRef(
    val id: String,
    val startDateTime: Instant,
    val endDateTime: Instant,
)

/** Unified item for popup: event or task, for prev/next navigation. */
private sealed class TimelinePopupItem {
    data class Event(val layout: EventLayout) : TimelinePopupItem()
    data class Task(val layout: TaskLayout) : TimelinePopupItem()

    val angle: Float
        get() = when (this) {
            is Event -> (layout.startAngle + layout.endAngle) / 2f
            is Task -> layout.angle
        }
}

private data class TaskLayout(
    val task: Task,
    val angle: Float,
    val isPast: Boolean,
)

private fun buildPopupItems(layout: TimelineLayout): List<TimelinePopupItem> {
    val events = layout.events.map { TimelinePopupItem.Event(it) }
    val tasks = layout.tasks.map { TimelinePopupItem.Task(it) }
    return (events + tasks).sortedBy { it.angle }
}

private fun filterSnapshotByDate(
    snapshot: DailySnapshot,
    targetDate: LocalDate,
    zoneId: ZoneId,
): DailySnapshot {
    val filteredEvents = snapshot.events.filter { event ->
        val eventDate = ZonedDateTime.ofInstant(event.startDateTime, zoneId).toLocalDate()
        eventDate == targetDate || event.allDay
    }
    val filteredTasks = snapshot.tasks.filter { task ->
        val taskDate = ZonedDateTime.ofInstant(task.dateTime, zoneId).toLocalDate()
        taskDate == targetDate
    }
    Log.d("TimelineScreen", "Filtering for $targetDate: ${snapshot.events.size} events -> ${filteredEvents.size} filtered, ${snapshot.tasks.size} tasks -> ${filteredTasks.size} filtered")
    return snapshot.copy(events = filteredEvents, tasks = filteredTasks)
}

private fun buildTimelineLayout(
    snapshot: DailySnapshot,
    zoneId: ZoneId,
    now: Instant,
    backToBackToleranceMinutes: Int = 2,
): TimelineLayout {
    val nowMinutes = minutesSinceMidnight(now, zoneId)
    val baseEvents = snapshot.events
        .filterNot { it.allDay }
        .map { event ->
            val startMinutes = minutesSinceMidnight(event.startDateTime, zoneId)
            val endMinutes = minutesSinceMidnight(event.endDateTime, zoneId).coerceAtLeast(startMinutes)
            EventLayout(
                event = event,
                startMinutes = startMinutes,
                endMinutes = endMinutes,
                startAngle = angleForInstantPrecise(event.startDateTime, zoneId),
                endAngle = angleForInstantPrecise(event.endDateTime, zoneId),
                layerIndex = 0,
                isPast = endMinutes <= nowMinutes,
            )
        }
        .sortedBy { it.startMinutes }

    val intervals = baseEvents.map {
        IntervalMinutes(id = it.event.id, startMinutes = it.startMinutes, endMinutes = it.endMinutes)
    }
    val layered = assignLayers(intervals).associateBy { it.interval.id }
    val layeredEvents = baseEvents.map { event ->
        event.copy(layerIndex = layered[event.event.id]?.layerIndex ?: 0)
    }

    val tasks = snapshot.tasks.map { task ->
        val minutes = minutesSinceMidnight(task.dateTime, zoneId)
        TaskLayout(
            task = task,
            angle = angleForMinutes(minutes),
            isPast = minutes <= nowMinutes,
        )
    }

    val markers = backToBackMarkers(intervals, backToBackToleranceMinutes)
        .map { angleForMinutes(it) }

    return TimelineLayout(
        events = layeredEvents,
        tasks = tasks,
        backToBackMarkers = markers,
        allDayCount = snapshot.events.count { it.allDay },
    )
}

// Default color for events without a specified color
private val EventColor = Color(0xFFA4A4A5)

/**
 * Calculates timeline placeholder line color based on time of day (sun position).
 * Creates a gradient effect: night (dark blue) → sunrise (orange) → day (light blue) → sunset (purple/orange)
 *
 * @param canvasAngle The line's angle on the canvas (-90° = noon, 0° = 6 PM, 90° = midnight, 180° = 6 AM)
 * @param sunriseMinutes Minutes since midnight for sunrise (default 360 = 6 AM)
 * @param sunsetMinutes Minutes since midnight for sunset (default 1080 = 6 PM)
 */
private fun getTimelineGradientColor(
    canvasAngle: Float,
    sunriseMinutes: Int = 360,  // Default 6:00 AM
    sunsetMinutes: Int = 1080,  // Default 6:00 PM
): Color {
    // Define zone colors
    val nightColor = Color(0xFF1a1a3e)       // Dark blue for night
    val sunriseColor = Color(0xFFff6b35)     // Warm orange for sunrise
    val dayColor = Color(0xFF4a6d8a)         // Muted blue for day
    val sunsetColorOrange = Color(0xFFffa500) // Orange for sunset
    val sunsetColorPurple = Color(0xFF8b3a62) // Purple for sunset

    // Convert minutes to canvas angle
    // Formula: minutes / 1440f * 360f - 90f gives the canvas angle
    // But we need to map: 0 min (midnight) → 90°, 360 min (6 AM) → 180°, 720 min (noon) → 270°/-90°, 1080 min (6 PM) → 0°/360°
    fun minutesToCanvasAngle(minutes: Int): Float {
        // Map: midnight (0) → 90°, then each minute adds (360/1440) degrees
        return ((minutes / 1440f) * 360f + 90f) % 360f
    }

    // Normalize canvas angle to 0-360 range
    val normalizedAngle = ((canvasAngle % 360f) + 360f) % 360f

    val sunriseAngle = minutesToCanvasAngle(sunriseMinutes)
    val sunsetAngle = minutesToCanvasAngle(sunsetMinutes)

    // Transition width in degrees (for smooth blending)
    val transitionWidth = 20f

    // Calculate zone boundaries
    val sunriseStart = (sunriseAngle - transitionWidth + 360f) % 360f
    val sunriseEnd = (sunriseAngle + transitionWidth) % 360f
    val sunsetStart = (sunsetAngle - transitionWidth + 360f) % 360f
    val sunsetEnd = (sunsetAngle + transitionWidth) % 360f

    // Helper to check if angle is in range (handles wraparound)
    fun isInRange(angle: Float, start: Float, end: Float): Boolean {
        return if (start <= end) {
            angle in start..end
        } else {
            angle >= start || angle <= end
        }
    }

    // Helper for linear interpolation
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    fun lerpColor(c1: Color, c2: Color, t: Float): Color {
        return Color(
            red = lerp(c1.red, c2.red, t),
            green = lerp(c1.green, c2.green, t),
            blue = lerp(c1.blue, c2.blue, t),
            alpha = lerp(c1.alpha, c2.alpha, t)
        )
    }

    // Calculate distance between angles (accounting for wraparound)
    fun angleDistance(a: Float, b: Float): Float {
        val diff = abs(a - b)
        return if (diff > 180f) 360f - diff else diff
    }

    // Check which zone the angle falls into
    val distToSunrise = angleDistance(normalizedAngle, sunriseAngle)
    val distToSunset = angleDistance(normalizedAngle, sunsetAngle)

    // Sunrise transition zone
    if (distToSunrise <= transitionWidth) {
        val t = distToSunrise / transitionWidth
        // Near sunrise center → orange, away from it → blend to night/day
        // Determine if we're coming from night or going to day
        val isAfterSunrise = if (sunriseAngle < 180f) {
            normalizedAngle > sunriseAngle && normalizedAngle < sunriseAngle + 180f
        } else {
            normalizedAngle > sunriseAngle || normalizedAngle < (sunriseAngle + 180f) % 360f
        }
        return if (isAfterSunrise) {
            lerpColor(sunriseColor, dayColor, t)
        } else {
            lerpColor(sunriseColor, nightColor, t)
        }
    }

    // Sunset transition zone
    if (distToSunset <= transitionWidth) {
        val t = distToSunset / transitionWidth
        // Near sunset center → orange/purple blend, away → blend to day/night
        val sunsetColor = lerpColor(sunsetColorOrange, sunsetColorPurple, 0.5f)
        // Determine if we're coming from day or going to night
        val isAfterSunset = if (sunsetAngle < 180f) {
            normalizedAngle > sunsetAngle && normalizedAngle < sunsetAngle + 180f
        } else {
            normalizedAngle > sunsetAngle || normalizedAngle < (sunsetAngle + 180f) % 360f
        }
        return if (isAfterSunset) {
            lerpColor(sunsetColor, nightColor, t)
        } else {
            lerpColor(sunsetColor, dayColor, t)
        }
    }

    // Day zone: between sunrise and sunset
    // Night zone: between sunset and sunrise (wrapping through midnight)
    // Check if angle is in day zone (between sunrise and sunset, the shorter arc through noon)
    val isDay = if (sunriseAngle < sunsetAngle) {
        normalizedAngle > sunriseAngle + transitionWidth && normalizedAngle < sunsetAngle - transitionWidth
    } else {
        normalizedAngle > sunriseAngle + transitionWidth || normalizedAngle < sunsetAngle - transitionWidth
    }

    return if (isDay) dayColor else nightColor
}

@Composable
private fun TimelineCanvas(
    layout: TimelineLayout?,
    snapshot: DailySnapshot?,
    selectedDate: LocalDate,
    sunriseMinutes: Int?,
    sunsetMinutes: Int?,
    now: Instant,
    dayOffset: Int,
    selectedEventRef: SelectedEventRef?,
    selectedEventAnchorAngle: Float?,
    onDayOffsetChange: (Int) -> Unit,
    onEventTap: (Event, Float) -> Unit,
    onTaskTap: (Task) -> Unit,
    onSleepTap: (SleepData) -> Unit,
) {
    if (layout == null || snapshot == null) return
    val zoneId = remember { ZoneId.systemDefault() }
    val context = LocalContext.current
    val digitalTimeTypeface = remember {
        ResourcesCompat.getFont(context, R.font.nunito_medium)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val juraTypeface = remember {
        ResourcesCompat.getFont(context, R.font.jura_medium)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val juraBoldTypeface = remember {
        ResourcesCompat.getFont(context, R.font.jura_semibold)
            ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    val juraRegularTypeface = remember {
        ResourcesCompat.getFont(context, R.font.jura_regular)
            ?: Typeface.create("sans-serif", Typeface.NORMAL)
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(layout, dayOffset, now, zoneId, snapshot.sleep, selectedDate) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPosition = down.position

                    val size = size
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outerRadius = min(size.width, size.height) / 2f
                    val wheelCenterX = center.x + outerRadius * 0.38f
                    val wheelCenterY = center.y + outerRadius * 0.15f
                    val wheelRadius = outerRadius * 0.24f  // Match actual component size

                    // Check if touch started in the date wheel area
                    val dxWheel = downPosition.x - wheelCenterX
                    val dyWheel = downPosition.y - wheelCenterY
                    val isInWheelArea = hypot(dxWheel, dyWheel) < wheelRadius * 1.1f

                    var totalDragY = 0f
                    var isDragging = false

                    // Track pointer until release
                    do {
                        val event = awaitPointerEvent()
                        val currentPosition = event.changes.firstOrNull()?.position ?: break
                        val dragY = currentPosition.y - downPosition.y
                        totalDragY = dragY
                        // Consider it a drag if moved significantly (for event/task/sleep taps)
                        if (abs(dragY) > 10f) isDragging = true
                    } while (event.changes.any { it.pressed })

                    // Date change is only via crown/rotary scroll; no touch on wheel

                    // If it was a tap (not a drag), handle event/task/sleep taps
                    if (!isDragging && abs(totalDragY) < 10f && !isInWheelArea) {
                        val canvasSize = Size(size.width.toFloat(), size.height.toFloat())
                        val dx = downPosition.x - center.x
                        val dy = downPosition.y - center.y
                        val radius = hypot(dx, dy)
                        val angle = angleFromTouch(dx, dy)

                        val hitEvent = findEventHit(layout.events, radius, angle, canvasSize)
                        if (hitEvent != null) {
                            onEventTap(hitEvent.event, angle)
                        } else {
                            val hitTask = findTaskHit(layout.tasks, radius, angle, canvasSize)
                            if (hitTask != null) {
                                onTaskTap(hitTask.task)
                            } else {
                                val sleep = snapshot.sleep
                                if (sleep != null && sleep.date == selectedDate &&
                                    findSleepHit(sleep, zoneId, radius, angle, canvasSize)
                                ) {
                                    onSleepTap(sleep)
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val size = size
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = min(size.width, size.height) / 2f
        val arcStrokeWidth = outerRadius * 0.08f

        val arcRadius = outerRadius - arcStrokeWidth / 2f
        val arcRect = Rect(
            center.x - arcRadius,
            center.y - arcRadius,
            center.x + arcRadius,
            center.y + arcRadius,
        )

        // Get current time angle for comparison
        val currentTimeAngle = angleForMinutes(minutesSinceMidnight(now, zoneId))

        // Draw minimalistic placeholder lines around the timeline with day/night gradient
        val lineCount = 96  // One line every 15 minutes
        val lineLength = outerRadius * 0.018f

        // Use dynamically calculated sun times (fallback to snapshot flow while loading)
        val effectiveSunrise = sunriseMinutes ?: snapshot.sunriseMinutes ?: 360
        val effectiveSunset = sunsetMinutes ?: snapshot.sunsetMinutes ?: 1080

        for (i in 0 until lineCount) {
            val angle = (i / lineCount.toFloat()) * 360f - 90f
            val radians = Math.toRadians(angle.toDouble())
            // Position lines in the middle of the timeline arc
            val lineOuterRadius = arcRadius + lineLength / 2f
            val lineInnerRadius = arcRadius - lineLength / 2f

            // Calculate dynamic color based on sun position
            val lineColor = getTimelineGradientColor(
                canvasAngle = angle,
                sunriseMinutes = effectiveSunrise,
                sunsetMinutes = effectiveSunset,
            )

            val innerX = center.x + lineInnerRadius * cos(radians).toFloat()
            val innerY = center.y + lineInnerRadius * sin(radians).toFloat()
            val outerX = center.x + lineOuterRadius * cos(radians).toFloat()
            val outerY = center.y + lineOuterRadius * sin(radians).toFloat()

            drawLine(
                color = lineColor,
                start = Offset(innerX, innerY),
                end = Offset(outerX, outerY),
                strokeWidth = 1f,
                cap = StrokeCap.Round
            )
        }

        // Draw sleep arc inside timeline, touching outer edge
        val sleep = snapshot.sleep
        if (sleep != null && sleep.date == selectedDate) {
            val sleepStrokeWidth = arcStrokeWidth * 0.6f
            val sleepArcRadius = outerRadius - sleepStrokeWidth / 2f
            val sleepArcRect = Rect(
                center.x - sleepArcRadius,
                center.y - sleepArcRadius,
                center.x + sleepArcRadius,
                center.y + sleepArcRadius,
            )
            val sleepStartAngle = angleForMinutes(minutesSinceMidnight(sleep.startTime, zoneId))
            val sleepEndAngle = angleForMinutes(minutesSinceMidnight(sleep.endTime, zoneId))
            val sleepSweep = sweepForArc(sleepStartAngle, sleepEndAngle)
            val sleepFillColor = Color(0x664B3B8C)  // Semi-transparent indigo
            val sleepStrokeColor = Color(0xFF6B5B95)  // Purple
            drawArc(
                color = sleepFillColor,
                startAngle = sleepStartAngle,
                sweepAngle = sleepSweep,
                useCenter = false,
                topLeft = sleepArcRect.topLeft,
                size = Size(sleepArcRect.width, sleepArcRect.height),
                style = Stroke(width = sleepStrokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = sleepStrokeColor,
                startAngle = sleepStartAngle,
                sweepAngle = sleepSweep,
                useCenter = false,
                topLeft = sleepArcRect.topLeft,
                size = Size(sleepArcRect.width, sleepArcRect.height),
                style = Stroke(width = sleepStrokeWidth * 0.35f, cap = StrokeCap.Round),
            )
        }

        // Event/task drawing setup (drawn last so calendar sits on top)
        val eventStrokeWidth = arcStrokeWidth * 1.5f   // Thicker main arc for events
        val eventLineWidth = arcStrokeWidth * 0.58f    // Top outline: keep thin (unchanged visual)

        // Position event arc so ~10% extends past outer circle (90% visible)
        val eventArcRadius = outerRadius - eventStrokeWidth * 0.4f
        val eventArcRect = Rect(
            center.x - eventArcRadius,
            center.y - eventArcRadius,
            center.x + eventArcRadius,
            center.y + eventArcRadius,
        )

        // Position for the colored line (10% past outer circle to match arc)
        val eventLineRadius = outerRadius - eventLineWidth * 0.4f
        val eventLineRect = Rect(
            center.x - eventLineRadius,
            center.y - eventLineRadius,
            center.x + eventLineRadius,
            center.y + eventLineRadius,
        )

        // Vertical line bounds: match timeline band height (inner to outer edge of arc)
        val verticalLineInner = arcRadius - arcStrokeWidth / 2f
        val verticalLineOuter = arcRadius + arcStrokeWidth / 2f
        val verticalLineWidth = arcStrokeWidth * 0.32f

        // Touch area geometry (must match findEventHit)
        val inwardTouchExpansion = arcStrokeWidth * 3.8f
        val outwardTouchExpansion = arcStrokeWidth * 1.2f
        val touchRMin = eventArcRadius - eventStrokeWidth / 2f - inwardTouchExpansion
        val touchRMax = eventArcRadius + eventStrokeWidth / 2f + outwardTouchExpansion
        val radiusTolerance = arcStrokeWidth * 0.5f
        val inwardPunctualExpansion = arcStrokeWidth * 3.8f
        val punctualAngleTolerance = 6f   // Arc length (width) for punctual event touch — smaller than tasks
        val punctualTouchInner = verticalLineInner - radiusTolerance - inwardPunctualExpansion
        val punctualTouchOuter = verticalLineOuter + radiusTolerance

        // Draw touch-area highlight only for the SELECTED event, in event color with high transparency
        val selectedLayout = selectedEventRef?.let { selected ->
            layout.events.find {
                it.event.id == selected.id &&
                    it.event.startDateTime == selected.startDateTime &&
                    it.event.endDateTime == selected.endDateTime
            }
        }
        selectedLayout?.let { eventLayout ->
            val eventColor = eventLayout.event.color?.let { Color(it) } ?: EventColor
            val highlightColor = eventColor.copy(alpha = 0.4f)
            val isPunctual = eventLayout.startMinutes == eventLayout.endMinutes
            if (isPunctual) {
                val midAngle = (eventLayout.startAngle + eventLayout.endAngle) / 2f
                val touchCenterR = (punctualTouchInner + punctualTouchOuter) / 2f
                val touchThickness = punctualTouchOuter - punctualTouchInner
                val touchRect = Rect(
                    center.x - touchCenterR,
                    center.y - touchCenterR,
                    center.x + touchCenterR,
                    center.y + touchCenterR,
                )
                drawArc(
                    color = highlightColor,
                    startAngle = midAngle - punctualAngleTolerance,
                    sweepAngle = 2f * punctualAngleTolerance,
                    useCenter = false,
                    topLeft = touchRect.topLeft,
                    size = Size(touchRect.width, touchRect.height),
                    style = Stroke(width = touchThickness, cap = StrokeCap.Butt),
                )
            } else {
                val touchThickness = touchRMax - touchRMin
                val touchCenterR = (touchRMin + touchRMax) / 2f
                val touchRect = Rect(
                    center.x - touchCenterR,
                    center.y - touchCenterR,
                    center.x + touchCenterR,
                    center.y + touchCenterR,
                )
                val fullSweep = sweepForArc(eventLayout.startAngle, eventLayout.endAngle)
                drawArc(
                    color = highlightColor,
                    startAngle = eventLayout.startAngle,
                    sweepAngle = fullSweep,
                    useCenter = false,
                    topLeft = touchRect.topLeft,
                    size = Size(touchRect.width, touchRect.height),
                    style = Stroke(width = touchThickness, cap = StrokeCap.Butt),
                )
            }
        }

        // Events and tasks drawn last (see end of block) so calendar sits on top

        val today = ZonedDateTime.ofInstant(now, zoneId).toLocalDate()
        val isToday = selectedDate == today

        // Draw digital time as separate component in top half
        drawDigitalTime(center, outerRadius, now, zoneId, juraRegularTypeface, isToday)

        // Clock center offset to the left
        val clockCenter = Offset(center.x - outerRadius * 0.38f, center.y + outerRadius * 0.15f)

        // Draw hour numbers (12, 3, 6, 9) in the inner circle - offset to left
        drawHourNumbers(clockCenter, outerRadius, juraRegularTypeface)

        // Draw date wheel on right (interactive date selector)
        drawDateWheel(center, outerRadius, selectedDate, digitalTimeTypeface, juraTypeface)

        // Draw clock hands (pill-shaped hour hand, thin minute hand) - offset to left
        drawClockHands(clockCenter, outerRadius, now, zoneId)

        // Draw timezone abbreviation below the small clock
        drawTimezoneLabel(clockCenter, outerRadius, zoneId, juraTypeface)

        // Draw 24-hour timeline markers on top of everything
        drawTimelineHourMarkers(center, outerRadius, arcStrokeWidth, juraBoldTypeface, juraTypeface)

        // Draw selected event description below the event
        selectedEventRef?.let { selected ->
            layout.events.find {
                it.event.id == selected.id &&
                    it.event.startDateTime == selected.startDateTime &&
                    it.event.endDateTime == selected.endDateTime
            }?.let { selectedLayout ->
                drawSelectedEventDescription(
                    center = center,
                    outerRadius = outerRadius,
                    arcStrokeWidth = arcStrokeWidth,
                    eventLayout = selectedLayout,
                    zoneId = zoneId,
                    anchorAngle = selectedEventAnchorAngle,
                )
            }
        }

        // Draw calendar events and tasks on top of everything
        layout.events.forEach { eventLayout ->
            val eventColor = eventLayout.event.color?.let {
                Color(it)
            } ?: EventColor

            val isSelected = selectedEventRef?.let { selected ->
                selected.id == eventLayout.event.id &&
                    selected.startDateTime == eventLayout.event.startDateTime &&
                    selected.endDateTime == eventLayout.event.endDateTime
            } == true

            val isPunctual = eventLayout.startMinutes == eventLayout.endMinutes

            val isPast = eventLayout.isPast
            val dimFactor = if (isPast) 0.4f else 1f

            if (isPunctual) {
                val midAngle = (eventLayout.startAngle + eventLayout.endAngle) / 2f
                val innerPos = polarOffset(center, verticalLineInner, midAngle)
                val outerPos = polarOffset(center, verticalLineOuter, midAngle)
                val lineColor = if (isSelected) {
                    eventColor.copy(alpha = 0.95f)
                } else {
                    eventColor.copy(alpha = dimFactor)
                }
                drawLine(
                    color = lineColor,
                    start = innerPos,
                    end = outerPos,
                    strokeWidth = verticalLineWidth,
                    cap = StrokeCap.Round
                )
            } else {
                val fullSweep = sweepForArc(eventLayout.startAngle, eventLayout.endAngle)
                val bgCapExtensionDeg = (eventStrokeWidth / 2f) / eventArcRadius * (180f / Math.PI.toFloat())
                val startAngleCompensated = eventLayout.startAngle + bgCapExtensionDeg
                val sweepAngle = (fullSweep - 2f * bgCapExtensionDeg).coerceAtLeast(1f)
                val lineRadius = outerRadius - eventLineWidth / 2f
                val lineCapExtensionDeg = (eventLineWidth / 2f) / lineRadius * (180f / Math.PI.toFloat())
                val lineStartAngleCompensated = eventLayout.startAngle + lineCapExtensionDeg
                val lineSweep = (fullSweep - 2f * lineCapExtensionDeg).coerceAtLeast(1f)

                val bgArcColor = if (isSelected) {
                    eventColor.copy(alpha = 0.9f)
                } else {
                    Color(0xCC2A2A2A)
                }

                drawArc(
                    color = bgArcColor,
                    startAngle = startAngleCompensated,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = eventArcRect.topLeft,
                    size = Size(eventArcRect.width, eventArcRect.height),
                    style = Stroke(width = eventStrokeWidth, cap = StrokeCap.Round),
                )

                val lineColor = if (isSelected) {
                    Color(
                        red = (eventColor.red * 0.6f).coerceIn(0f, 1f),
                        green = (eventColor.green * 0.6f).coerceIn(0f, 1f),
                        blue = (eventColor.blue * 0.6f).coerceIn(0f, 1f),
                        alpha = 1f
                    )
                } else {
                    eventColor.copy(alpha = dimFactor)
                }

                drawArc(
                    color = lineColor,
                    startAngle = lineStartAngleCompensated,
                    sweepAngle = lineSweep,
                    useCenter = false,
                    topLeft = eventLineRect.topLeft,
                    size = Size(eventLineRect.width, eventLineRect.height),
                    style = Stroke(width = eventLineWidth, cap = StrokeCap.Round),
                )
            }
        }

        layout.tasks.forEach { taskLayout ->
            val innerPos = polarOffset(center, verticalLineInner, taskLayout.angle)
            val outerPos = polarOffset(center, verticalLineOuter, taskLayout.angle)
            val taskColor = if (taskLayout.isPast) Color(0xFF666666) else Color(0xFF888888)
            drawLine(
                color = taskColor,
                start = innerPos,
                end = outerPos,
                strokeWidth = verticalLineWidth,
                cap = StrokeCap.Round
            )
        }

        // Event labels are only drawn when an event is selected (see drawEventDescription)

        // Draw current time indicator ON TOP of everything (red for today, gray for other days)
        val indicatorColor = if (isToday) Color(0xFFFF4444) else Color(0xFF666666)
        val screenRadius = min(size.width, size.height) / 2f
        val indicatorOuterRadius = screenRadius
        val indicatorInnerRadius = arcRadius - arcStrokeWidth * 1.2f
        val indicatorRadians = Math.toRadians(currentTimeAngle.toDouble())
        val indicatorOuter = Offset(
            center.x + indicatorOuterRadius * cos(indicatorRadians).toFloat(),
            center.y + indicatorOuterRadius * sin(indicatorRadians).toFloat()
        )
        val indicatorInner = Offset(
            center.x + indicatorInnerRadius * cos(indicatorRadians).toFloat(),
            center.y + indicatorInnerRadius * sin(indicatorRadians).toFloat()
        )
        val shadowOffset = 2f
        val shadowOffsetX = shadowOffset * cos(indicatorRadians + Math.PI / 2).toFloat()
        val shadowOffsetY = shadowOffset * sin(indicatorRadians + Math.PI / 2).toFloat()
        drawLine(
            color = Color(0x66000000),
            start = Offset(indicatorInner.x + shadowOffsetX, indicatorInner.y + shadowOffsetY),
            end = Offset(indicatorOuter.x + shadowOffsetX, indicatorOuter.y + shadowOffsetY),
            strokeWidth = 2f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = indicatorColor,
            start = indicatorInner,
            end = indicatorOuter,
            strokeWidth = 1.5f,
            cap = StrokeCap.Round
        )
        val tailRadius = arcStrokeWidth * 0.32f
        val dotRadius = tailRadius * 0.45f
        drawCircle(
            color = indicatorColor,
            radius = tailRadius,
            center = indicatorInner,
            style = Stroke(width = arcStrokeWidth * 0.08f, cap = StrokeCap.Round)
        )
        drawCircle(color = Color.Black, radius = dotRadius, center = indicatorInner)

        // Draw countdown for current event on the indicator
        if (isToday && layout != null) {
            val nowMinutes = minutesSinceMidnight(now, zoneId)
            val currentEvent = layout.events.firstOrNull { eventLayout ->
                eventLayout.startMinutes <= nowMinutes && eventLayout.endMinutes > nowMinutes
            }
            currentEvent?.let { eventLayout ->
                val minutesLeft = Duration.between(now, eventLayout.event.endDateTime).toMinutes()
                val text = when {
                    minutesLeft <= 0 -> "0 min"
                    minutesLeft < 1 -> "< 1 min"
                    else -> "$minutesLeft min"
                }
                
                drawCountdownOnIndicator(
                    center = center,
                    outerRadius = outerRadius,
                    currentTimeAngle = currentTimeAngle,
                    text = text,
                    juraTypeface = juraTypeface,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectedEventDescription(
    center: Offset,
    outerRadius: Float,
    arcStrokeWidth: Float,
    eventLayout: EventLayout,
    zoneId: ZoneId,
    anchorAngle: Float?,
) {
    val event = eventLayout.event
    val arcRadius = outerRadius - arcStrokeWidth / 2f

    // Position the text on an inner ring below the event
    val descriptionRadius = arcRadius - arcStrokeWidth * 1.9f

    // Start the description at the same angle as the event starts
    val sweepAngle = sweepForArc(eventLayout.startAngle, eventLayout.endAngle)
    val descriptionStartAngle = eventLayout.startAngle
    val midAngle = (eventLayout.startAngle + eventLayout.endAngle) / 2f

    // Past 6pm (0°=6pm, 90°=midnight, 180°=6am): arc is on right→bottom→left, text renders upside down
    // Flip path direction so text reads correctly in that hemisphere
    val normalizedMid = (midAngle % 360f).let { if (it < 0) it + 360f else it }
    val isPast6pm = normalizedMid in 0f..180f

    drawContext.canvas.nativeCanvas.apply {
        // Title paint - thin and spaced
        val titlePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = outerRadius * 0.09f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            letterSpacing = 0.08f
        }

        // Calculate sweep to fit content, starting from event's start angle
        val titleWidth = titlePaint.measureText(event.title)
        val requiredSweep = ((titleWidth * 1.15f) / (2f * Math.PI.toFloat() * descriptionRadius)) * 360f
        val textSweep = maxOf(requiredSweep, sweepAngle).coerceIn(60f, 220f)
        val adjustedStartAngle = descriptionStartAngle - 13f

        val titlePath = android.graphics.Path()
        val titleArcRect = android.graphics.RectF(
            center.x - descriptionRadius,
            center.y - descriptionRadius,
            center.x + descriptionRadius,
            center.y + descriptionRadius
        )

        if (isPast6pm) {
            // Reverse path direction so text reads right-side up
            titlePath.addArc(titleArcRect, adjustedStartAngle + textSweep + 4f, -(textSweep + 4f))
            drawTextOnPath(event.title, titlePath, 0f, -titlePaint.textSize * 0.35f, titlePaint)
        } else {
            titlePath.addArc(titleArcRect, adjustedStartAngle, textSweep + 4f)
            drawTextOnPath(event.title, titlePath, 0f, titlePaint.textSize * 0.35f, titlePaint)
        }
    }
}

private fun sweepForArc(startAngle: Float, endAngle: Float): Float {
    val sweep = if (endAngle >= startAngle) {
        endAngle - startAngle
    } else {
        (360f - startAngle) + endAngle
    }
    return if (sweep <= 0f) 1f else sweep
}

private fun polarOffset(center: Offset, radius: Float, angleDegrees: Float): Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val x = center.x + radius * cos(radians).toFloat()
    val y = center.y + radius * sin(radians).toFloat()
    return Offset(x, y)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTimelineHourMarkers(
    center: Offset,
    outerRadius: Float,
    arcStrokeWidth: Float,
    mainTypeface: Typeface,
    smallTypeface: Typeface,
) {
    // Position markers at the same radius as tick marks (replacing them)
    val arcRadius = outerRadius - arcStrokeWidth / 2f
    val markerRadius = arcRadius - arcStrokeWidth * 0.8f  // Shifted towards center to avoid event overlap

    // Draw hour markers (just numbers, with small am/pm for 12s)
    // Each marker has: label, canvas angle, and optional small suffix
    data class HourMarker(val label: String, val canvasAngle: Float, val suffix: String? = null, val isMain: Boolean = false)
    val hourMarkers = listOf(
        // Every 2 hours on 24-hour timeline (each hour = 15°)
        HourMarker("12", -90f, "pm", isMain = true),   // noon at top
        HourMarker("2", -60f),                          // 2 PM
        HourMarker("4", -30f),                          // 4 PM
        HourMarker("6", 0f, isMain = true),            // 6 PM at right
        HourMarker("8", 30f),                           // 8 PM
        HourMarker("10", 60f),                          // 10 PM
        HourMarker("12", 90f, "am", isMain = true),    // midnight at bottom
        HourMarker("2", 120f),                          // 2 AM
        HourMarker("4", 150f),                          // 4 AM
        HourMarker("6", 180f, isMain = true),          // 6 AM at left
        HourMarker("8", -150f),                         // 8 AM
        HourMarker("10", -120f),                        // 10 AM
    )

    drawContext.canvas.nativeCanvas.apply {
        // Main markers (12, 6) - larger and whiter
        val mainOutlinePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            textSize = outerRadius * 0.108f
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = mainTypeface
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = outerRadius * 0.009f
        }

        val mainPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#E0E0E0")
            textSize = outerRadius * 0.108f
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = mainTypeface
        }

        // Secondary markers (2, 4, 8, 10) - slightly smaller, whiter
        val secondaryOutlinePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            textSize = outerRadius * 0.075f
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = smallTypeface
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = outerRadius * 0.006f
        }

        val secondaryPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#C0C0C0")
            textSize = outerRadius * 0.075f
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = smallTypeface
        }

        // am/pm suffix paint - slightly bigger and whiter
        val suffixOutlinePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            textSize = outerRadius * 0.058f
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = smallTypeface
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = outerRadius * 0.005f
        }

        val suffixPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#B0B0B0")
            textSize = outerRadius * 0.058f
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = smallTypeface
        }

        hourMarkers.forEach { marker ->
            val radians = Math.toRadians(marker.canvasAngle.toDouble())

            val x = center.x + markerRadius * cos(radians).toFloat()
            val y = center.y + markerRadius * sin(radians).toFloat()

            // Use different paints for main vs secondary markers
            val outlinePaint = if (marker.isMain) mainOutlinePaint else secondaryOutlinePaint
            val paint = if (marker.isMain) mainPaint else secondaryPaint

            // Draw number outline first, then fill
            drawText(marker.label, x, y + paint.textSize * 0.35f, outlinePaint)
            drawText(marker.label, x, y + paint.textSize * 0.35f, paint)

            // Draw small am/pm suffix below the number (only for main 12s)
            marker.suffix?.let { suffix ->
                val suffixY = y + paint.textSize * 1.0f
                drawText(suffix, x, suffixY, suffixOutlinePaint)
                drawText(suffix, x, suffixY, suffixPaint)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClockHands(
    center: Offset,
    outerRadius: Float,
    now: Instant,
    zoneId: ZoneId,
) {
    val zdt = ZonedDateTime.ofInstant(now, zoneId)

    // All hands connect at the center (no gap)
    val handStartRadius = 0f

    // Calculate angles: 0° = 12 o'clock, 90° = 3 o'clock, etc.
    // Convert to canvas coordinates where 0° = 3 o'clock (right), so subtract 90°
    val secondFraction = (zdt.second + zdt.nano / 1_000_000_000f) / 60f
    val minuteFraction = (zdt.minute + zdt.second / 60f) / 60f
    val hourFraction = ((zdt.hour % 12) + zdt.minute / 60f + zdt.second / 3600f) / 12f

    // Canvas angles (0° = right/3 o'clock position)
    val secondCanvasAngle = secondFraction * 360f - 90f  // -90 to make 0 sec point up
    val minuteCanvasAngle = minuteFraction * 360f - 90f  // -90 to make 0 min point up
    val hourCanvasAngle = hourFraction * 360f - 90f      // -90 to make 12:00 point up

    val secondLength = outerRadius * 0.20f
    val minuteLength = outerRadius * 0.18f
    val hourLength = outerRadius * 0.12f
    val hourHandWidth = outerRadius * 0.022f
    val minuteColor = Color(0xFF888888)  // Gray color for minute hand
    val secondColor = Color(0xFFFF6B6B)  // Red color for seconds hand

    // Calculate hour hand endpoints - starts after the gap
    val hourEndRadians = Math.toRadians(hourCanvasAngle.toDouble())
    val hourStart = Offset(
        center.x + handStartRadius * cos(hourEndRadians).toFloat(),
        center.y + handStartRadius * sin(hourEndRadians).toFloat()
    )
    val hourEnd = Offset(
        center.x + hourLength * cos(hourEndRadians).toFloat(),
        center.y + hourLength * sin(hourEndRadians).toFloat()
    )

    // Draw white pill-shaped hour hand using a thick line with round caps
    // First draw the dark outline
    drawLine(
        color = Color(0xFF1A1A1A),
        start = hourStart,
        end = hourEnd,
        strokeWidth = hourHandWidth + 2f,
        cap = StrokeCap.Round,
    )
    // Then draw the white fill
    drawLine(
        color = Color.White,
        start = hourStart,
        end = hourEnd,
        strokeWidth = hourHandWidth,
        cap = StrokeCap.Round,
    )

    // Calculate minute hand - starts after the gap
    val minuteRadians = Math.toRadians(minuteCanvasAngle.toDouble())
    val minuteStart = Offset(
        center.x + handStartRadius * cos(minuteRadians).toFloat(),
        center.y + handStartRadius * sin(minuteRadians).toFloat()
    )
    val minuteEnd = Offset(
        center.x + minuteLength * cos(minuteRadians).toFloat(),
        center.y + minuteLength * sin(minuteRadians).toFloat()
    )

    // Draw minute hand with light blue color
    val minuteHandWidth = outerRadius * 0.014f
    drawLine(
        color = minuteColor,
        start = minuteStart,
        end = minuteEnd,
        strokeWidth = minuteHandWidth,
        cap = StrokeCap.Round,
    )
    // Draw dark inner to create outline effect
    drawLine(
        color = Color(0xFF1A1A1A),
        start = minuteStart,
        end = minuteEnd,
        strokeWidth = minuteHandWidth - outerRadius * 0.012f,
        cap = StrokeCap.Round,
    )

    // Calculate seconds hand - starts after the gap
    val secondRadians = Math.toRadians(secondCanvasAngle.toDouble())
    val secondStart = Offset(
        center.x + handStartRadius * cos(secondRadians).toFloat(),
        center.y + handStartRadius * sin(secondRadians).toFloat()
    )
    val secondEnd = Offset(
        center.x + secondLength * cos(secondRadians).toFloat(),
        center.y + secondLength * sin(secondRadians).toFloat()
    )

    // Draw thin red seconds hand
    drawLine(
        color = secondColor,
        start = secondStart,
        end = secondEnd,
        strokeWidth = outerRadius * 0.006f,
        cap = StrokeCap.Round,
    )

    // Draw center dot (white with black inner)
    val centerDotRadius = outerRadius * 0.018f
    val innerDotRadius = outerRadius * 0.008f
    drawCircle(
        color = Color.White,
        radius = centerDotRadius,
        center = center,
    )
    drawCircle(
        color = Color.Black,
        radius = innerDotRadius,
        center = center,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTimezoneLabel(
    center: Offset,
    outerRadius: Float,
    zoneId: ZoneId,
    typeface: Typeface,
) {
    // Get timezone abbreviation (e.g., "PST", "EST", "BRT")
    val zdt = ZonedDateTime.now(zoneId)
    val tzAbbreviation = zdt.zone.getDisplayName(
        java.time.format.TextStyle.SHORT,
        Locale.getDefault()
    )

    drawContext.canvas.nativeCanvas.apply {
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = outerRadius * 0.06f
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = Typeface.create(typeface, Typeface.BOLD)
        }

        // Position inside the circle in the bottom half
        val labelY = center.y + outerRadius * 0.12f

        drawText(tzAbbreviation, center.x, labelY, paint)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDigitalTime(
    center: Offset,
    outerRadius: Float,
    now: Instant,
    zoneId: ZoneId,
    typeface: Typeface,
    isToday: Boolean,
) {
    val zdt = ZonedDateTime.ofInstant(now, zoneId)
    val timeString = "%02d:%02d".format(zdt.hour, zdt.minute)

    drawContext.canvas.nativeCanvas.apply {
        val textSize = outerRadius * 0.38f
        val paint = Paint().apply {
            isAntiAlias = true
            this.textSize = textSize
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = typeface
            letterSpacing = 0.05f
        }

        // Position in top half of circle
        val timeY = center.y - outerRadius * 0.32f

        // Light gradient: whiter at top, darker white at bottom
        if (isToday) {
            val gradTop = timeY - textSize * 0.9f
            val gradBottom = timeY + textSize * 0.3f
            paint.shader = LinearGradient(
                center.x, gradTop, center.x, gradBottom,
                intArrayOf(
                    android.graphics.Color.parseColor("#FFFFFF"),
                    android.graphics.Color.parseColor("#606060")
                ),
                null,
                Shader.TileMode.CLAMP
            )
        } else {
            paint.color = android.graphics.Color.parseColor("#666666")
        }

        // Stretch vertically (save/scale/restore on native canvas)
        save()
        translate(center.x, timeY)
        scale(1f, 1.25f)
        translate(-center.x, -timeY)
        drawText(timeString, center.x, timeY, paint)
        restore()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHourNumbers(
    center: Offset,
    outerRadius: Float,
    typeface: Typeface,
) {
    // Position hour numbers inside the inner circle
    val numberRadius = outerRadius * 0.14f  // Inside the tick marks
    val tickOuterRadius = outerRadius * 0.24f  // Outer edge of tick marks

    // Draw clock tick marks
    val tickColor = Color(0xFF444444)
    val tickColorLight = Color(0xFF333333)

    // Draw 60 tick marks (like a clock)
    for (i in 0 until 60) {
        val angle = (i / 60f) * 360f - 90f  // Start from top
        val radians = Math.toRadians(angle.toDouble())

        // Determine tick length based on position
        val isMainHour = i % 15 == 0  // 12, 3, 6, 9 positions
        val isHour = i % 5 == 0       // Hour positions

        val tickLength = when {
            isMainHour -> outerRadius * 0.06f  // Longest for 12, 3, 6, 9
            isHour -> outerRadius * 0.04f      // Medium for other hours
            else -> outerRadius * 0.02f        // Short for minutes
        }

        val tickWidth = when {
            isMainHour -> 2.5f
            isHour -> 1.8f
            else -> 1f
        }

        val color = if (isMainHour || isHour) tickColor else tickColorLight

        val innerRadius = tickOuterRadius - tickLength

        val outerX = center.x + tickOuterRadius * cos(radians).toFloat()
        val outerY = center.y + tickOuterRadius * sin(radians).toFloat()
        val innerX = center.x + innerRadius * cos(radians).toFloat()
        val innerY = center.y + innerRadius * sin(radians).toFloat()

        drawLine(
            color = color,
            start = Offset(innerX, innerY),
            end = Offset(outerX, outerY),
            strokeWidth = tickWidth,
            cap = StrokeCap.Round
        )
    }

    // Hour numbers: 12 at top, 3 at right, 6 at bottom, 9 at left
    val hourNumbers = listOf(
        Triple("12", -90f, 0f),   // 12 at top
        Triple("3", 0f, 0f),      // 3 at right
        Triple("6", 90f, 0f),     // 6 at bottom
        Triple("9", 180f, 0f)     // 9 at left
    )

    drawContext.canvas.nativeCanvas.apply {
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#666666")
            textSize = outerRadius * 0.08f
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = typeface
        }

        hourNumbers.forEach { (number, angle, _) ->
            val radians = Math.toRadians(angle.toDouble())
            val x = center.x + numberRadius * cos(radians).toFloat()
            val y = center.y + numberRadius * sin(radians).toFloat() + paint.textSize * 0.35f

            drawText(number, x, y, paint)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDateWheel(
    center: Offset,
    outerRadius: Float,
    selectedDate: LocalDate,
    nunitoTypeface: Typeface,
    juraTypeface: Typeface,
) {
    // Position mirrored from small clock (clock is at -0.38, this at +0.38)
    val wheelCenter = Offset(center.x + outerRadius * 0.38f, center.y + outerRadius * 0.15f)
    val wheelRadius = outerRadius * 0.24f  // Component boundary radius

    // Draw background circle
    drawCircle(
        color = Color(0xFF1A1A1A),
        radius = wheelRadius,
        center = wheelCenter,
    )

    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val weekFields = WeekFields.of(Locale.getDefault())
    val slotCount = 7

    // Get today's date for progress calculation
    val today = LocalDate.now()
    val todayWeekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

    // Week start for the selected date (for displaying numbers)
    val selectedWeekStart = selectedDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

    // Calculate TODAY's position in the current week (for styling)
    val todaySlot = (0 until slotCount).firstOrNull { todayWeekStart.plusDays(it.toLong()) == today } ?: 0

    // Calculate selected day position
    val selectedSlot = (0 until slotCount).firstOrNull { selectedWeekStart.plusDays(it.toLong()) == selectedDate } ?: 0

    // Check if viewing current week (for styling)
    val isCurrentWeek = selectedWeekStart == todayWeekStart

    // Show only 3 days: previous, selected, next (handling week boundaries)
    val centerRadius = wheelRadius * 0.50f       // Selected day: central, at top
    val neighborRadius = wheelRadius * 0.70f     // Neighbors: on middle line, padded from edge

    // Determine which days to show (up to 3, centered on selected)
    val isFirstDayOfWeek = selectedSlot == 0
    val isLastDayOfWeek = selectedSlot == slotCount - 1

    // Build list of (slot, displayPosition) pairs
    // displayPosition: 0 = left, 1 = center, 2 = right
    val daysToShow = mutableListOf<Pair<Int, Int>>()

    if (!isFirstDayOfWeek) {
        // Show previous day on the left
        daysToShow.add(Pair(selectedSlot - 1, 0))
    }
    // Selected day in the center
    daysToShow.add(Pair(selectedSlot, 1))
    if (!isLastDayOfWeek) {
        // Show next day on the right
        daysToShow.add(Pair(selectedSlot + 1, 2))
    }

    // 3 display positions: center at top, neighbors on horizontal midline (middle line)
    // 180° = left, 270° = top (center), 0° = right
    val displayAngles = listOf(180f, 270f, 0f)  // left, center, right
    val displayRadii = listOf(neighborRadius, centerRadius, neighborRadius)

    drawContext.canvas.nativeCanvas.apply {
        val paint = Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }

        for ((slot, displayPos) in daysToShow) {            val date = selectedWeekStart.plusDays(slot.toLong())
            val dayNum = date.dayOfMonth.toString()
            val isSelected = (slot == selectedSlot)
            val isToday = (date == today)
            val isPastToday = isCurrentWeek && slot < todaySlot
            val angle = displayAngles[displayPos]
            val radius = displayRadii[displayPos]
            val pos = polarOffset(wheelCenter, radius, angle)

            when {
                isSelected -> {
                    // Selected day: white and bold, large
                    paint.color = android.graphics.Color.WHITE
                    paint.typeface = Typeface.create(juraTypeface, Typeface.BOLD)
                    paint.textSize = wheelRadius * 0.60f
                }
                isToday -> {
                    // Today (if not selected): bright
                    paint.color = android.graphics.Color.parseColor("#4FC3F7")
                    paint.typeface = Typeface.create(juraTypeface, Typeface.BOLD)
                    paint.textSize = wheelRadius * 0.42f
                }
                isPastToday -> {
                    // Past days in current week: brighter
                    paint.color = android.graphics.Color.parseColor("#AAAAAA")
                    paint.typeface = juraTypeface
                    paint.textSize = wheelRadius * 0.38f
                }
                else -> {
                    // Future days or other weeks: dimmer
                    paint.color = android.graphics.Color.parseColor("#666666")
                    paint.typeface = juraTypeface
                    paint.textSize = wheelRadius * 0.38f
                }
            }

            // Draw all day numbers horizontally
            drawText(dayNum, pos.x, pos.y + paint.textSize * 0.35f, paint)

            // Draw day abbreviation below the selected day (horizontal)
            if (isSelected) {
                val dayAbbrev = date.dayOfWeek.getDisplayName(
                    java.time.format.TextStyle.SHORT,
                    Locale.getDefault()
                ).uppercase()
                paint.color = android.graphics.Color.parseColor("#AAAAAA")
                paint.typeface = nunitoTypeface
                paint.textSize = wheelRadius * 0.28f

                val abbrevRadius = centerRadius - wheelRadius * 0.40f
                val abbrevPos = polarOffset(wheelCenter, abbrevRadius, angle)
                drawText(dayAbbrev, abbrevPos.x, abbrevPos.y + paint.textSize * 0.35f, paint)
            }

        }

        // "week N" in the gap area (bottom-right) - horizontal text
        val weekOfYear = weekFields.weekOfWeekBasedYear().getFrom(selectedDate)
        val weekText = "week $weekOfYear"
        paint.color = android.graphics.Color.WHITE
        paint.typeface = Typeface.create(nunitoTypeface, Typeface.BOLD)
        paint.textSize = wheelRadius * 0.20f

        val weekRadius = wheelRadius * 0.70f
        val weekBaseAngle = 90f  // Exactly at bottom (270° in standard math = 90° in canvas)
        val gapCenter = polarOffset(wheelCenter, weekRadius, weekBaseAngle)
        drawText(weekText, gapCenter.x, gapCenter.y + paint.textSize * 0.35f, paint)
    }
}

/** Map tap angle to display position: -1=left (previous day), 0=center (selected), 1=right (next day), null=outside arc */
private fun angleToDateWheelPosition(angleDegrees: Float): Int? {
    val arcStartAngle = 135f
    val arcSweepAngle = 270f
    val normalizedAngle = (angleDegrees + 360f) % 360f

    // Check if angle is within the arc (with some tolerance)
    val arcEnd = arcStartAngle + arcSweepAngle
    val tolerance = 20f
    val inArc = normalizedAngle >= (arcStartAngle - tolerance) && normalizedAngle <= (arcEnd + tolerance)
    if (!inArc) return null

    // 3 display positions at 25%, 50%, 75% of arc
    val leftAngle = arcStartAngle + arcSweepAngle * 0.25f   // ~202.5°
    val centerAngle = arcStartAngle + arcSweepAngle * 0.5f  // ~270°
    val rightAngle = arcStartAngle + arcSweepAngle * 0.75f  // ~337.5°

    // Find closest position
    val distToLeft = abs(normalizedAngle - leftAngle)
    val distToCenter = abs(normalizedAngle - centerAngle)
    val distToRight = abs(normalizedAngle - rightAngle)

    return when {
        distToLeft <= distToCenter && distToLeft <= distToRight -> -1  // Left = previous day
        distToRight <= distToCenter && distToRight <= distToLeft -> 1   // Right = next day
        else -> 0  // Center = current selection (no change)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEventLabel(
    center: Offset,
    outerRadius: Float,
    eventLayout: EventLayout,
) {
    val event = eventLayout.event
    val midAngle = (eventLayout.startAngle + eventLayout.endAngle) / 2f
    val labelRadius = outerRadius * 0.72f

    // Use event's Google Calendar color if available, otherwise fallback to gray
    val labelColor = event.color ?: android.graphics.Color.parseColor("#A4A4A5")

    drawContext.canvas.nativeCanvas.apply {
        val paint = Paint().apply {
            isAntiAlias = true
            color = labelColor
            textSize = outerRadius * 0.08f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }

        // Calculate position for curved text along arc
        val textPath = android.graphics.Path()
        val arcRect = android.graphics.RectF(
            center.x - labelRadius,
            center.y - labelRadius,
            center.x + labelRadius,
            center.y + labelRadius
        )

        // Draw text along path
        val sweepAngle = sweepForArc(eventLayout.startAngle, eventLayout.endAngle)
        textPath.addArc(arcRect, eventLayout.startAngle, sweepAngle)

        // Measure text to center it on the arc
        val textWidth = paint.measureText(event.title)
        val pathLength = (sweepAngle / 360f) * (2 * Math.PI.toFloat() * labelRadius)
        val hOffset = (pathLength - textWidth) / 2f

        if (hOffset > 0) {
            drawTextOnPath(event.title, textPath, hOffset, paint.textSize * 0.35f, paint)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCountdownOnIndicator(
    center: Offset,
    outerRadius: Float,
    currentTimeAngle: Float,
    text: String,
    juraTypeface: Typeface,
) {
    // Position the text on top of the pin's tail circle (at inner end of indicator)
    val arcStrokeWidth = outerRadius * 0.08f
    val arcRadius = outerRadius - arcStrokeWidth / 2f
    val indicatorInnerRadius = arcRadius - arcStrokeWidth * 1.2f
    val tailRadius = arcStrokeWidth * 0.32f
    // Position text slightly inward from the tail circle (toward center)
    val textRadius = indicatorInnerRadius - tailRadius - outerRadius * 0.04f

    drawContext.canvas.nativeCanvas.apply {
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#FFFFFF")
            textSize = outerRadius * 0.055f
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = juraTypeface
        }

        // Position on the indicator line at the inner tail location
        val indicatorRadians = Math.toRadians(currentTimeAngle.toDouble())
        val textX = center.x + textRadius * cos(indicatorRadians).toFloat()
        val textY = center.y + textRadius * sin(indicatorRadians).toFloat()

        // Normalize angle to 0-360 range
        val normalizedAngle = ((currentTimeAngle % 360f) + 360f) % 360f

        // Calculate rotation: text should be perpendicular to the radius
        // For top half (270° to 90° clockwise), text reads left-to-right
        // For bottom half (90° to 270° clockwise), flip text to read right-to-left (upside down + 180°)
        val rotationDegrees = if (normalizedAngle > 90f && normalizedAngle < 270f) {
            // Bottom half: rotate 180° more so text doesn't appear upside down
            currentTimeAngle + 90f + 180f
        } else {
            // Top half: normal tangent orientation
            currentTimeAngle + 90f
        }

        // Rotate canvas to draw text tangent to circle (perpendicular to radius)
        save()

        // Translate to text position
        translate(textX, textY)

        // Rotate to make text tangent
        rotate(rotationDegrees, 0f, 0f)

        // Draw text centered at origin (which is now at textX, textY after translation)
        val metrics = paint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val textOffset = -textHeight / 2f - metrics.ascent

        drawText(text, 0f, textOffset, paint)

        restore()
    }
}

private fun findEventHit(
    events: List<EventLayout>,
    radius: Float,
    angle: Float,
    canvasSize: Size,
): EventLayout? {
    if (events.isEmpty()) return null
    val outerRadius = min(canvasSize.width, canvasSize.height) / 2f
    val arcStrokeWidth = outerRadius * 0.08f
    val eventStrokeWidth = arcStrokeWidth * 1.5f
    // Match the event arc drawing positions (arc extends 10% past boundary)
    val eventArcRadius = outerRadius - eventStrokeWidth * 0.4f
    // Touch area: expand inward and outward for easier tapping
    val inwardTouchExpansion = arcStrokeWidth * 3.8f
    val outwardTouchExpansion = arcStrokeWidth * 1.2f
    val rMin = eventArcRadius - eventStrokeWidth / 2f - inwardTouchExpansion
    val rMax = eventArcRadius + eventStrokeWidth / 2f + outwardTouchExpansion

    val arcRadius = outerRadius - arcStrokeWidth / 2f
    val verticalLineInner = arcRadius - arcStrokeWidth / 2f
    val verticalLineOuter = arcRadius + arcStrokeWidth / 2f
    val radiusTolerance = arcStrokeWidth * 0.5f
    // Expand punctual event touch toward center only
    val inwardPunctualExpansion = arcStrokeWidth * 3.8f
    val punctualAngleTolerance = 6f   // Arc length for punctual event touch (reduced)
    val angleTolerance = 12f          // Used for tasks

    for (event in events) {
        val isPunctual = event.startMinutes == event.endMinutes
        if (isPunctual) {
            val midAngle = (event.startAngle + event.endAngle) / 2f
            if (radius in (verticalLineInner - radiusTolerance - inwardPunctualExpansion)..(verticalLineOuter + radiusTolerance) &&
                abs(angleDistance(midAngle, angle)) <= punctualAngleTolerance
            ) {
                return event
            }
        } else if (radius in rMin..rMax && isAngleWithinArc(angle, event.startAngle, event.endAngle)) {
            return event
        }
    }
    return null
}

private fun findTaskHit(
    tasks: List<TaskLayout>,
    radius: Float,
    angle: Float,
    canvasSize: Size,
): TaskLayout? {
    if (tasks.isEmpty()) return null
    val outerRadius = min(canvasSize.width, canvasSize.height) / 2f * 0.94f
    val arcStrokeWidth = outerRadius * 0.08f
    val arcRadius = outerRadius - arcStrokeWidth / 2f
    val verticalLineInner = arcRadius - arcStrokeWidth / 2f
    val verticalLineOuter = arcRadius + arcStrokeWidth / 2f
    val radiusTolerance = arcStrokeWidth * 0.5f
    val angleTolerance = 12f
    return tasks.firstOrNull { task ->
        radius in (verticalLineInner - radiusTolerance)..(verticalLineOuter + radiusTolerance) &&
            abs(angleDistance(task.angle, angle)) <= angleTolerance
    }
}

private fun findSleepHit(
    sleep: SleepData,
    zoneId: ZoneId,
    radius: Float,
    angle: Float,
    canvasSize: Size,
): Boolean {
    val outerRadius = min(canvasSize.width, canvasSize.height) / 2f * 0.94f
    val arcStrokeWidth = outerRadius * 0.08f
    val sleepStrokeWidth = arcStrokeWidth * 0.6f
    val sleepArcRadius = outerRadius - sleepStrokeWidth / 2f
    val rMin = sleepArcRadius - sleepStrokeWidth / 2f
    val rMax = sleepArcRadius + sleepStrokeWidth / 2f
    if (radius !in rMin..rMax) return false
    val startAngle = angleForMinutes(minutesSinceMidnight(sleep.startTime, zoneId))
    val endAngle = angleForMinutes(minutesSinceMidnight(sleep.endTime, zoneId))
    return isAngleWithinArc(angle, startAngle, endAngle)
}

private fun angleDistance(a: Float, b: Float): Float {
    val diff = abs(a - b) % 360f
    return if (diff > 180f) 360f - diff else diff
}

@Composable
private fun TimelineItemDetailOverlay(
    item: TimelinePopupItem,
    now: Instant,
    anchorAngle: Float,
    onDismiss: () -> Unit,
    onRotaryScrollDelta: (Float) -> Unit,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val overlayFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        Log.w("RotaryTest", "overlay opened - rotate crown and check RotaryTest tag")
        Log.w("TimelineScreen", "Rotary: overlay shown, requesting focus...")
        delay(50)
        overlayFocusRequester.requestFocus()
        Log.w("TimelineScreen", "Rotary: overlay focus requested (rotate crown to change event)")
    }
    val title: String
    val timeRangeText: String
    val calendarLabel: String?
    val arcColor: Color
    val icon: ImageVector
    when (item) {
        is TimelinePopupItem.Event -> {
            val e = item.layout.event
            val startTime = ZonedDateTime.ofInstant(e.startDateTime, zoneId)
            val endTime = ZonedDateTime.ofInstant(e.endDateTime, zoneId)
            title = e.title
            timeRangeText = "%02d:%02d - %02d:%02d".format(
                startTime.hour, startTime.minute, endTime.hour, endTime.minute
            )
            calendarLabel = e.calendarName ?: e.calendarId?.takeIf { it.isNotBlank() }
            arcColor = e.color?.let { Color(it) } ?: EventColor
            icon = Icons.Outlined.CalendarMonth
        }
        is TimelinePopupItem.Task -> {
            val t = item.layout.task
            val time = ZonedDateTime.ofInstant(t.dateTime, zoneId)
            title = t.title
            timeRangeText = "%02d:%02d".format(time.hour, time.minute)
            calendarLabel = null
            arcColor = Color(0xFF888888)
            icon = Icons.Outlined.Checklist
        }
    }

    // Tap outside to dismiss; rotary scroll changes event when overlay has focus
    // Use onPreRotaryScrollEvent so we get events before any child; focusable first so we can receive
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(overlayFocusRequester)
            .focusable()
            .onPreRotaryScrollEvent { event ->
                val px = event.verticalScrollPixels
                Log.w("RotaryTest", "pixels=$px")
                Log.w("TimelineScreen", "Rotary: overlay received pixels=$px")
                onRotaryScrollDelta(px)
                true
            }
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
        contentAlignment = Alignment.Center,
    ) {
        val circleShape = androidx.compose.foundation.shape.CircleShape
        val popupSize = 100.dp
        val shadowRadius = 16.dp
        Box(
            modifier = Modifier.size(popupSize + shadowRadius * 2),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerPx = Offset(size.width / 2f, size.height / 2f)
                val radiusPx = (popupSize / 2).toPx() + shadowRadius.toPx()
                val popupRadiusPx = (popupSize / 2).toPx()
                val innerFrac = (popupRadiusPx / radiusPx).coerceIn(0.5f, 0.9f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 1f),
                        innerFrac to Color.Black.copy(alpha = 0.95f),
                        ((innerFrac + 1f) / 2f) to Color.Black.copy(alpha = 0.5f),
                        1f to Color.Transparent,
                        center = centerPx,
                        radius = radiusPx,
                    ),
                    radius = radiusPx,
                    center = centerPx,
                )
            }
            Box(
                modifier = Modifier
                    .size(popupSize)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .background(color = Color(0xFF222222), shape = circleShape)
                    .border(2.dp, Color(0xFF0A0A0A), circleShape),
                contentAlignment = Alignment.Center,
            ) {
                val arcCenterAngle = anchorAngle
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 2.dp.toPx()
                    val arcRect = Rect(Offset.Zero, Size(size.width, size.height))
                    drawArc(
                        color = arcColor,
                        startAngle = arcCenterAngle - 22f,
                        sweepAngle = 44f,
                        useCenter = false,
                        topLeft = arcRect.topLeft,
                        size = Size(arcRect.width, arcRect.height),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val iconColor = Color(0xFFAAAAAA)
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = iconColor,
                    )
                    calendarLabel?.let { label ->
                        Text(
                            text = label,
                            color = Color(0xFF888888),
                            fontSize = 9.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = timeRangeText,
                        color = Color.White,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = title,
                        color = Color(0xFFCCCCCC),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepDetailOverlay(
    sleep: SleepData,
    onDismiss: () -> Unit,
) {
    val durationText = buildString {
        val h = sleep.durationMinutes / 60
        val m = sleep.durationMinutes % 60
        append("${h}h ${m}m")
        sleep.qualityScore?.let { q -> append(" • $q%") }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                .background(
                    color = Color(0xE6222222),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
        ) {
            Text(
                text = durationText,
                color = Color.White,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TaskDetailOverlay(
    task: Task,
    now: Instant,
    onDismiss: () -> Unit,
    onAdjustMinutes: (Int) -> Unit,
    onComplete: () -> Unit,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val time = ZonedDateTime.ofInstant(task.dateTime, zoneId)
    val minutesUntil = ((task.dateTime.epochSecond - now.epochSecond) / 60).toInt()

    // Tap outside to dismiss
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Compact rounded popup at top
        Box(
            modifier = Modifier
                .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                .background(
                    color = Color(0xE6222222),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .pointerInput(Unit) {
                    // Consume taps on the popup so they don't dismiss
                    detectTapGestures { }
                }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = task.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    text = "%02d:%02d".format(time.hour, time.minute),
                    color = Color(0xFF9AA5B1),
                    fontSize = 10.sp,
                )
                if (minutesUntil > 0) {
                    Text(
                        text = "em $minutesUntil min",
                        color = Color(0xFFF0C14B),
                        fontSize = 9.sp,
                    )
                }
                if (task.completed) {
                    Text(
                        text = "✓ concluído",
                        color = Color(0xFF9BE15D),
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}
