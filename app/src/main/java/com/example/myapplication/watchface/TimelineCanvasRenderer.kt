package com.example.myapplication.watchface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.example.myapplication.R
import com.example.myapplication.data.model.DailySnapshot
import com.example.myapplication.data.model.Event
import com.example.myapplication.data.model.SleepData
import com.example.myapplication.data.model.Task
import com.example.myapplication.data.repository.SnapshotRepository
import com.example.myapplication.data.util.IntervalMinutes
import com.example.myapplication.data.util.angleForInstantPrecise
import com.example.myapplication.data.util.angleForMinutes
import com.example.myapplication.data.util.angleFromTouch
import com.example.myapplication.data.util.assignLayers
import com.example.myapplication.data.util.backToBackMarkers
import com.example.myapplication.data.util.isAngleWithinArc
import com.example.myapplication.data.util.minutesSinceMidnight
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val TAG = "TimelineRenderer"

class TimelineCanvasRenderer(
    private val context: Context,
    private val repository: SnapshotRepository,
) {
    // Fonts
    private val juraRegular: Typeface
    private val juraMedium: Typeface
    private val juraSemibold: Typeface
    private val nunitoMedium: Typeface

    // Reusable paint object
    private val paint = Paint().apply { isAntiAlias = true }

    // Tap state
    private var selectedEvent: EventLayout? = null
    private var lastLayout: TimelineLayout? = null
    private var lastBoundsWidth: Int = 0
    private var lastBoundsHeight: Int = 0

    // Task list scroll state
    private var taskListScrollIndex: Int = 0

    // Date picker state
    private var selectedDayOffset: Int = 0
    private var showDatePicker: Boolean = false

    init {
        Log.d(TAG, "Renderer initialized")
        juraRegular = ResourcesCompat.getFont(context, R.font.jura_regular)
            ?: Typeface.create("sans-serif", Typeface.NORMAL)
        juraMedium = ResourcesCompat.getFont(context, R.font.jura_medium)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        juraSemibold = ResourcesCompat.getFont(context, R.font.jura_semibold)
            ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
        nunitoMedium = ResourcesCompat.getFont(context, R.font.nunito_medium)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, isAmbient: Boolean = false) {
        val now = zonedDateTime.toInstant()
        val zoneId = zonedDateTime.zone
        val actualToday = zonedDateTime.toLocalDate()
        val today = actualToday.plusDays(selectedDayOffset.toLong())
        val isToday = selectedDayOffset == 0
        val snapshot = repository.snapshot.value

        Log.d(TAG, "render: snapshot=${snapshot != null}, events=${snapshot?.events?.size ?: 0}, tasks=${snapshot?.tasks?.size ?: 0}, sleep=${snapshot?.sleep != null}")

        // Black background
        canvas.drawColor(Color.BLACK)

        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val outerRadius = min(bounds.width(), bounds.height()) / 2f
        val arcStrokeWidth = outerRadius * 0.08f
        val arcRadius = outerRadius - arcStrokeWidth / 2f

        if (isAmbient) {
            drawDigitalTime(canvas, centerX, centerY, outerRadius, now, zoneId, isToday = true, isAmbient = true)
            drawTimelineHourMarkers(canvas, centerX, centerY, outerRadius, arcStrokeWidth, isAmbient = true)
            return
        }

        // Build layout from snapshot
        val layout = if (snapshot != null) {
            val filtered = filterSnapshotByDate(snapshot, today, zoneId)
            Log.d(TAG, "filtered: events=${filtered.events.size}, tasks=${filtered.tasks.size}, date=$today, snapshotDate=${snapshot.dateLocal}")
            val built = buildTimelineLayout(filtered, zoneId, now)
            Log.d(TAG, "layout: events=${built.events.size}, tasks=${built.tasks.size}, allDay=${built.allDayCount}")
            built
        } else {
            Log.d(TAG, "snapshot is null - no data yet")
            null
        }

        // Cache for hit detection
        lastLayout = layout
        lastBoundsWidth = bounds.width()
        lastBoundsHeight = bounds.height()

        val currentTimeAngle = angleForMinutes(minutesSinceMidnight(now, zoneId))

        // 1. Timeline lines
        drawTimelineLines(canvas, centerX, centerY, outerRadius, arcRadius, arcStrokeWidth)

        // 2. Sleep arc
        if (snapshot?.sleep != null && snapshot.sleep.date == actualToday) {
            drawSleepArc(canvas, centerX, centerY, outerRadius, arcStrokeWidth, snapshot.sleep, zoneId)
        }

        // 3. Digital time
        drawDigitalTime(canvas, centerX, centerY, outerRadius, now, zoneId, isToday = isToday, isAmbient = false, displayDate = today)

        // 4. Events
        if (layout != null) {
            drawEvents(canvas, centerX, centerY, outerRadius, arcStrokeWidth, arcRadius, layout)
        }

        // 9. Tasks
        if (layout != null) {
            drawTasks(canvas, centerX, centerY, outerRadius, arcStrokeWidth, arcRadius, layout)
        }

        // 10. Hour markers (on top of events)
        drawTimelineHourMarkers(canvas, centerX, centerY, outerRadius, arcStrokeWidth, isAmbient = false)

        // 10b. Selected event highlight + description
        selectedEvent?.let { sel ->
            drawSelectedHighlight(canvas, centerX, centerY, outerRadius, arcStrokeWidth, sel)
            drawSelectedEventDescription(canvas, centerX, centerY, outerRadius, arcStrokeWidth, sel, zoneId)
        }

        // 11. Current time indicator + countdown (only when viewing today)
        if (isToday) {
            drawCurrentTimeIndicator(canvas, centerX, centerY, outerRadius, arcRadius, arcStrokeWidth, currentTimeAngle)

            // 12. Countdown: active event remaining OR time to next event
            if (layout != null) {
                val nowMinutes = minutesSinceMidnight(now, zoneId)

                val currentEvent = layout.events.firstOrNull { el ->
                    el.startMinutes <= nowMinutes && el.endMinutes > nowMinutes
                }

                val countdownText = if (currentEvent != null) {
                    val minutesLeft = Duration.between(now, currentEvent.event.endDateTime).toMinutes()
                    when {
                        minutesLeft <= 0 -> "0 min"
                        minutesLeft < 1 -> "< 1 min"
                        else -> "$minutesLeft min"
                    }
                } else {
                    val nextEvent = layout.events
                        .filter { it.startMinutes > nowMinutes }
                        .minByOrNull { it.startMinutes }
                    nextEvent?.let { eventLayout ->
                        val minutesUntil = Duration.between(now, eventLayout.event.startDateTime).toMinutes()
                        when {
                            minutesUntil <= 0 -> "now"
                            minutesUntil < 1 -> "< 1 min"
                            minutesUntil < 60 -> "$minutesUntil min"
                            else -> {
                                val h = minutesUntil / 60
                                val m = minutesUntil % 60
                                if (m == 0L) "${h}h" else "${h}h${m}"
                            }
                        }
                    }
                }

                countdownText?.let {
                    drawCountdownOnIndicator(canvas, centerX, centerY, outerRadius, currentTimeAngle, it)
                }
            }
        }

        // 13. Task list in the bottom half
        if (layout != null && layout.tasks.isNotEmpty()) {
            drawTaskList(canvas, centerX, centerY, outerRadius, layout.tasks)
        }

        // 14. Center popup for selected event (drawn last, on top of everything)
        selectedEvent?.let { sel ->
            drawEventPopup(canvas, centerX, centerY, outerRadius, sel, zoneId, layout?.events ?: emptyList())
        }

        // 15. Date picker popup (drawn last, on top of everything)
        if (showDatePicker) {
            drawDatePickerPopup(canvas, centerX, centerY, outerRadius, actualToday)
        }
    }

    // ── Timeline gradient lines ─────────────────────────────────────────

    private fun drawTimelineLines(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        arcRadius: Float, arcStrokeWidth: Float,
    ) {
        val lineCount = 96
        val lineLength = outerRadius * 0.018f
        val lineColor = 0xFF333333.toInt()

        paint.reset()
        paint.isAntiAlias = true
        paint.color = lineColor
        paint.strokeWidth = 1f
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE

        for (i in 0 until lineCount) {
            val angle = (i / lineCount.toFloat()) * 360f - 90f
            val radians = Math.toRadians(angle.toDouble())
            val lineOuterRadius = arcRadius + lineLength / 2f
            val lineInnerRadius = arcRadius - lineLength / 2f

            val innerX = cx + lineInnerRadius * cos(radians).toFloat()
            val innerY = cy + lineInnerRadius * sin(radians).toFloat()
            val outerX = cx + lineOuterRadius * cos(radians).toFloat()
            val outerY = cy + lineOuterRadius * sin(radians).toFloat()

            canvas.drawLine(innerX, innerY, outerX, outerY, paint)
        }
    }

    // ── Sleep arc ───────────────────────────────────────────────────────

    private fun drawSleepArc(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        arcStrokeWidth: Float, sleep: SleepData, zoneId: ZoneId,
    ) {
        val sleepStrokeWidth = arcStrokeWidth * 0.6f
        val sleepArcRadius = outerRadius - sleepStrokeWidth / 2f
        val rect = RectF(
            cx - sleepArcRadius, cy - sleepArcRadius,
            cx + sleepArcRadius, cy + sleepArcRadius,
        )
        val startAngle = angleForMinutes(minutesSinceMidnight(sleep.startTime, zoneId))
        val endAngle = angleForMinutes(minutesSinceMidnight(sleep.endTime, zoneId))
        val sweep = sweepForArc(startAngle, endAngle)

        // Fill
        paint.reset()
        paint.isAntiAlias = true
        paint.color = 0x664B3B8C.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sleepStrokeWidth
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(rect, startAngle, sweep, false, paint)

        // Outline
        paint.color = 0xFF6B5B95.toInt()
        paint.strokeWidth = sleepStrokeWidth * 0.35f
        canvas.drawArc(rect, startAngle, sweep, false, paint)
    }

    // ── Events ──────────────────────────────────────────────────────────

    private fun drawEvents(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        arcStrokeWidth: Float, arcRadius: Float, layout: TimelineLayout,
    ) {
        val eventStrokeWidth = arcStrokeWidth * 1.5f
        val eventLineWidth = arcStrokeWidth * 0.58f
        val eventArcRadius = outerRadius - eventStrokeWidth * 0.4f
        val eventLineRadius = outerRadius - eventLineWidth * 0.4f
        val verticalLineInner = arcRadius - arcStrokeWidth / 2f
        val verticalLineOuter = arcRadius + arcStrokeWidth / 2f
        val verticalLineWidth = arcStrokeWidth * 0.32f

        val eventArcRect = RectF(
            cx - eventArcRadius, cy - eventArcRadius,
            cx + eventArcRadius, cy + eventArcRadius,
        )
        val eventLineRect = RectF(
            cx - eventLineRadius, cy - eventLineRadius,
            cx + eventLineRadius, cy + eventLineRadius,
        )

        val defaultEventColor = 0xFFA4A4A5.toInt()

        layout.events.forEach { eventLayout ->
            val eventColor = eventLayout.event.color ?: defaultEventColor
            val isPunctual = eventLayout.startMinutes == eventLayout.endMinutes
            val dimFactor = if (eventLayout.isPast) 0.4f else 1f

            if (isPunctual) {
                val midAngle = (eventLayout.startAngle + eventLayout.endAngle) / 2f
                val (innerX, innerY) = polarXY(cx, cy, verticalLineInner, midAngle)
                val (outerX, outerY) = polarXY(cx, cy, verticalLineOuter, midAngle)

                paint.reset()
                paint.isAntiAlias = true
                paint.color = applyAlpha(eventColor, dimFactor)
                paint.strokeWidth = verticalLineWidth
                paint.strokeCap = Paint.Cap.ROUND
                paint.style = Paint.Style.STROKE
                canvas.drawLine(innerX, innerY, outerX, outerY, paint)
            } else {
                val fullSweep = sweepForArc(eventLayout.startAngle, eventLayout.endAngle)

                // Background arc
                val bgCapExtensionDeg = (eventStrokeWidth / 2f) / eventArcRadius * (180f / Math.PI.toFloat())
                val startAngleCompensated = eventLayout.startAngle + bgCapExtensionDeg
                val sweepAngle = (fullSweep - 2f * bgCapExtensionDeg).coerceAtLeast(1f)

                paint.reset()
                paint.isAntiAlias = true
                paint.color = 0xCC2A2A2A.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = eventStrokeWidth
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(eventArcRect, startAngleCompensated, sweepAngle, false, paint)

                // Colored line arc
                val lineRadius = outerRadius - eventLineWidth / 2f
                val lineCapExtensionDeg = (eventLineWidth / 2f) / lineRadius * (180f / Math.PI.toFloat())
                val lineStartAngleCompensated = eventLayout.startAngle + lineCapExtensionDeg
                val lineSweep = (fullSweep - 2f * lineCapExtensionDeg).coerceAtLeast(1f)

                paint.color = applyAlpha(eventColor, dimFactor)
                paint.strokeWidth = eventLineWidth
                canvas.drawArc(eventLineRect, lineStartAngleCompensated, lineSweep, false, paint)
            }
        }
    }

    // ── Tasks ───────────────────────────────────────────────────────────

    private fun drawTasks(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        arcStrokeWidth: Float, arcRadius: Float, layout: TimelineLayout,
    ) {
        val verticalLineInner = arcRadius - arcStrokeWidth / 2f
        val verticalLineOuter = arcRadius + arcStrokeWidth / 2f
        val verticalLineWidth = arcStrokeWidth * 0.32f

        layout.tasks.forEach { taskLayout ->
            val (innerX, innerY) = polarXY(cx, cy, verticalLineInner, taskLayout.angle)
            val (outerX, outerY) = polarXY(cx, cy, verticalLineOuter, taskLayout.angle)
            val taskColor = if (taskLayout.isPast) 0xFF666666.toInt() else 0xFF888888.toInt()

            paint.reset()
            paint.isAntiAlias = true
            paint.color = taskColor
            paint.strokeWidth = verticalLineWidth
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = Paint.Style.STROKE
            canvas.drawLine(innerX, innerY, outerX, outerY, paint)
        }
    }

    // ── Current time indicator (red pin) ────────────────────────────────

    private fun drawCurrentTimeIndicator(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        arcRadius: Float, arcStrokeWidth: Float, currentTimeAngle: Float,
    ) {
        val indicatorColor = 0xFFFF8C00.toInt() // orange
        val screenRadius = outerRadius
        val indicatorInnerRadius = arcRadius - arcStrokeWidth * 1.2f
        val indicatorRadians = Math.toRadians(currentTimeAngle.toDouble())

        val outerX = cx + screenRadius * cos(indicatorRadians).toFloat()
        val outerY = cy + screenRadius * sin(indicatorRadians).toFloat()
        val innerX = cx + indicatorInnerRadius * cos(indicatorRadians).toFloat()
        val innerY = cy + indicatorInnerRadius * sin(indicatorRadians).toFloat()

        // Extend the line a tiny bit beyond the screen edge
        val extensionFactor = 1.03f
        val extOuterX = cx + screenRadius * extensionFactor * cos(indicatorRadians).toFloat()
        val extOuterY = cy + screenRadius * extensionFactor * sin(indicatorRadians).toFloat()

        // Shadow
        val shadowOffset = 2f
        val shadowOffsetX = shadowOffset * cos(indicatorRadians + Math.PI / 2).toFloat()
        val shadowOffsetY = shadowOffset * sin(indicatorRadians + Math.PI / 2).toFloat()

        paint.reset()
        paint.isAntiAlias = true
        paint.color = 0x66000000.toInt()
        paint.strokeWidth = 2.8f
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        canvas.drawLine(
            innerX + shadowOffsetX, innerY + shadowOffsetY,
            extOuterX + shadowOffsetX, extOuterY + shadowOffsetY, paint
        )

        // Main line
        paint.color = indicatorColor
        paint.strokeWidth = 2.2f
        canvas.drawLine(innerX, innerY, extOuterX, extOuterY, paint)

        // Solid triangle at the tail
        val tailSize = arcStrokeWidth * 0.50f
        val perpRadians = indicatorRadians + Math.PI / 2.0
        // Tip points inward (toward center)
        val tipX = innerX + tailSize * 1.2f * cos(indicatorRadians).toFloat()
        val tipY = innerY + tailSize * 1.2f * sin(indicatorRadians).toFloat()
        val baseLeftX = innerX - tailSize * 0.6f * cos(perpRadians).toFloat()
        val baseLeftY = innerY - tailSize * 0.6f * sin(perpRadians).toFloat()
        val baseRightX = innerX + tailSize * 0.6f * cos(perpRadians).toFloat()
        val baseRightY = innerY + tailSize * 0.6f * sin(perpRadians).toFloat()

        val trianglePath = android.graphics.Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseLeftX, baseLeftY)
            lineTo(baseRightX, baseRightY)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = indicatorColor
        canvas.drawPath(trianglePath, paint)
    }

    // ── Digital time ────────────────────────────────────────────────────

    private fun drawDigitalTime(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        now: Instant, zoneId: ZoneId, isToday: Boolean, isAmbient: Boolean,
        displayDate: LocalDate? = null,
    ) {
        val zdt = ZonedDateTime.ofInstant(now, zoneId)
        val timeString = "%02d:%02d".format(zdt.hour, zdt.minute)

        val textSize = outerRadius * 0.43f
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = textSize
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = juraRegular
        paint.letterSpacing = 0.05f

        val timeY = cy - outerRadius * 0.14f

        if (isAmbient) {
            paint.color = Color.WHITE
        } else if (isToday) {
            val gradTop = timeY - textSize * 0.9f
            val gradBottom = timeY + textSize * 0.3f
            paint.shader = LinearGradient(
                cx, gradTop, cx, gradBottom,
                intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#606060")),
                null, Shader.TileMode.CLAMP
            )
        } else {
            paint.color = Color.parseColor("#666666")
        }

        canvas.save()
        canvas.translate(cx, timeY)
        canvas.scale(1f, 1.25f)
        canvas.translate(-cx, -timeY)
        canvas.drawText(timeString, cx, timeY, paint)
        canvas.restore()

        paint.shader = null

        // Date label below the time
        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern(
            "EEE. MMM. d", java.util.Locale.ENGLISH
        )
        val dateToShow = displayDate ?: zdt.toLocalDate()
        val dateString = dateToShow.format(dateFormatter)
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = outerRadius * 0.105f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = juraRegular
        // Accent color (cyan) when viewing a non-today date
        paint.color = if (isToday) 0xFF999999.toInt() else 0xFF4FC3F7.toInt()
        canvas.drawText(dateString, cx, timeY + outerRadius * 0.12f, paint)
    }

    // ── Hour numbers (small clock) ──────────────────────────────────────

    private fun drawHourNumbers(canvas: Canvas, cx: Float, cy: Float, outerRadius: Float) {
        val numberRadius = outerRadius * 0.14f
        val tickOuterRadius = outerRadius * 0.24f

        // 60 tick marks
        for (i in 0 until 60) {
            val angle = (i / 60f) * 360f - 90f
            val radians = Math.toRadians(angle.toDouble())

            val isMainHour = i % 15 == 0
            val isHour = i % 5 == 0

            val tickLength = when {
                isMainHour -> outerRadius * 0.06f
                isHour -> outerRadius * 0.04f
                else -> outerRadius * 0.02f
            }
            val tickWidth = when {
                isMainHour -> 2.5f
                isHour -> 1.8f
                else -> 1f
            }
            val color = if (isMainHour || isHour) 0xFF444444.toInt() else 0xFF333333.toInt()

            val innerRadius = tickOuterRadius - tickLength
            val oX = cx + tickOuterRadius * cos(radians).toFloat()
            val oY = cy + tickOuterRadius * sin(radians).toFloat()
            val iX = cx + innerRadius * cos(radians).toFloat()
            val iY = cy + innerRadius * sin(radians).toFloat()

            paint.reset()
            paint.isAntiAlias = true
            paint.color = color
            paint.strokeWidth = tickWidth
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = Paint.Style.STROKE
            canvas.drawLine(iX, iY, oX, oY, paint)
        }

        // Hour numbers: 12, 3, 6, 9
        val hourNumbers = listOf(
            Pair("12", -90f),
            Pair("3", 0f),
            Pair("6", 90f),
            Pair("9", 180f),
        )
        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.parseColor("#666666")
        paint.textSize = outerRadius * 0.09f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = juraRegular

        hourNumbers.forEach { (number, angle) ->
            val radians = Math.toRadians(angle.toDouble())
            val x = cx + numberRadius * cos(radians).toFloat()
            val y = cy + numberRadius * sin(radians).toFloat() + paint.textSize * 0.35f
            canvas.drawText(number, x, y, paint)
        }
    }

    // ── Date wheel ──────────────────────────────────────────────────────

    private fun drawDateWheel(canvas: Canvas, cx: Float, cy: Float, outerRadius: Float, selectedDate: LocalDate) {
        val wheelCenterX = cx + outerRadius * 0.38f
        val wheelCenterY = cy + outerRadius * 0.15f
        val wheelRadius = outerRadius * 0.24f

        // Background circle
        paint.reset()
        paint.isAntiAlias = true
        paint.color = 0xFF1A1A1A.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(wheelCenterX, wheelCenterY, wheelRadius, paint)

        val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val weekFields = WeekFields.of(Locale.getDefault())
        val slotCount = 7

        val today = LocalDate.now()
        val todayWeekStart = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val selectedWeekStart = selectedDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

        val todaySlot = (0 until slotCount).firstOrNull { todayWeekStart.plusDays(it.toLong()) == today } ?: 0
        val selectedSlot = (0 until slotCount).firstOrNull { selectedWeekStart.plusDays(it.toLong()) == selectedDate } ?: 0
        val isCurrentWeek = selectedWeekStart == todayWeekStart

        val centerRadius = wheelRadius * 0.50f
        val neighborRadius = wheelRadius * 0.70f

        val isFirstDayOfWeek = selectedSlot == 0
        val isLastDayOfWeek = selectedSlot == slotCount - 1

        val daysToShow = mutableListOf<Pair<Int, Int>>()
        if (!isFirstDayOfWeek) daysToShow.add(Pair(selectedSlot - 1, 0))
        daysToShow.add(Pair(selectedSlot, 1))
        if (!isLastDayOfWeek) daysToShow.add(Pair(selectedSlot + 1, 2))

        val displayAngles = floatArrayOf(180f, 270f, 0f)
        val displayRadii = floatArrayOf(neighborRadius, centerRadius, neighborRadius)

        paint.reset()
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.CENTER

        for ((slot, displayPos) in daysToShow) {
            val date = selectedWeekStart.plusDays(slot.toLong())
            val dayNum = date.dayOfMonth.toString()
            val isSelected = (slot == selectedSlot)
            val isToday = (date == today)
            val isPastToday = isCurrentWeek && slot < todaySlot
            val angle = displayAngles[displayPos]
            val radius = displayRadii[displayPos]
            val (posX, posY) = polarXY(wheelCenterX, wheelCenterY, radius, angle)

            when {
                isSelected -> {
                    paint.color = Color.WHITE
                    paint.typeface = Typeface.create(juraMedium, Typeface.BOLD)
                    paint.textSize = wheelRadius * 0.67f
                }
                isToday -> {
                    paint.color = Color.parseColor("#4FC3F7")
                    paint.typeface = Typeface.create(juraMedium, Typeface.BOLD)
                    paint.textSize = wheelRadius * 0.47f
                }
                isPastToday -> {
                    paint.color = Color.parseColor("#AAAAAA")
                    paint.typeface = juraMedium
                    paint.textSize = wheelRadius * 0.43f
                }
                else -> {
                    paint.color = Color.parseColor("#666666")
                    paint.typeface = juraMedium
                    paint.textSize = wheelRadius * 0.43f
                }
            }

            canvas.drawText(dayNum, posX, posY + paint.textSize * 0.35f, paint)

            // Day abbreviation below selected
            if (isSelected) {
                val dayAbbrev = date.dayOfWeek.getDisplayName(
                    java.time.format.TextStyle.SHORT, Locale.getDefault()
                ).uppercase()
                paint.color = Color.parseColor("#AAAAAA")
                paint.typeface = nunitoMedium
                paint.textSize = wheelRadius * 0.31f

                val abbrevRadius = centerRadius - wheelRadius * 0.40f
                val (abX, abY) = polarXY(wheelCenterX, wheelCenterY, abbrevRadius, angle)
                canvas.drawText(dayAbbrev, abX, abY + paint.textSize * 0.35f, paint)
            }
        }

        // "week N" label at bottom
        val weekOfYear = weekFields.weekOfWeekBasedYear().getFrom(selectedDate)
        val weekText = "week $weekOfYear"
        paint.color = Color.WHITE
        paint.typeface = Typeface.create(nunitoMedium, Typeface.BOLD)
        paint.textSize = wheelRadius * 0.22f

        val weekRadius = wheelRadius * 0.70f
        val (gapX, gapY) = polarXY(wheelCenterX, wheelCenterY, weekRadius, 90f)
        canvas.drawText(weekText, gapX, gapY + paint.textSize * 0.35f, paint)
    }

    // ── Clock hands ─────────────────────────────────────────────────────

    private fun drawClockHands(canvas: Canvas, cx: Float, cy: Float, outerRadius: Float, now: Instant, zoneId: ZoneId) {
        val zdt = ZonedDateTime.ofInstant(now, zoneId)

        val secondFraction = (zdt.second + zdt.nano / 1_000_000_000f) / 60f
        val minuteFraction = (zdt.minute + zdt.second / 60f) / 60f
        val hourFraction = ((zdt.hour % 12) + zdt.minute / 60f + zdt.second / 3600f) / 12f

        val secondCanvasAngle = secondFraction * 360f - 90f
        val minuteCanvasAngle = minuteFraction * 360f - 90f
        val hourCanvasAngle = hourFraction * 360f - 90f

        val secondLength = outerRadius * 0.20f
        val minuteLength = outerRadius * 0.18f
        val hourLength = outerRadius * 0.12f
        val hourHandWidth = outerRadius * 0.022f

        // Hour hand
        val hourRadians = Math.toRadians(hourCanvasAngle.toDouble())
        val hourEndX = cx + hourLength * cos(hourRadians).toFloat()
        val hourEndY = cy + hourLength * sin(hourRadians).toFloat()

        // Dark outline
        paint.reset()
        paint.isAntiAlias = true
        paint.color = 0xFF1A1A1A.toInt()
        paint.strokeWidth = hourHandWidth + 2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        canvas.drawLine(cx, cy, hourEndX, hourEndY, paint)

        // White fill
        paint.color = Color.WHITE
        paint.strokeWidth = hourHandWidth
        canvas.drawLine(cx, cy, hourEndX, hourEndY, paint)

        // Minute hand
        val minuteRadians = Math.toRadians(minuteCanvasAngle.toDouble())
        val minuteEndX = cx + minuteLength * cos(minuteRadians).toFloat()
        val minuteEndY = cy + minuteLength * sin(minuteRadians).toFloat()
        val minuteHandWidth = outerRadius * 0.014f

        paint.color = 0xFF888888.toInt()
        paint.strokeWidth = minuteHandWidth
        canvas.drawLine(cx, cy, minuteEndX, minuteEndY, paint)

        // Dark inner for outline effect
        paint.color = 0xFF1A1A1A.toInt()
        paint.strokeWidth = (minuteHandWidth - outerRadius * 0.012f).coerceAtLeast(0.5f)
        canvas.drawLine(cx, cy, minuteEndX, minuteEndY, paint)

        // Second hand
        val secondRadians = Math.toRadians(secondCanvasAngle.toDouble())
        val secondEndX = cx + secondLength * cos(secondRadians).toFloat()
        val secondEndY = cy + secondLength * sin(secondRadians).toFloat()

        paint.color = 0xFFFF6B6B.toInt()
        paint.strokeWidth = outerRadius * 0.006f
        canvas.drawLine(cx, cy, secondEndX, secondEndY, paint)

        // Center dot
        val centerDotRadius = outerRadius * 0.018f
        val innerDotRadius = outerRadius * 0.008f
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, centerDotRadius, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(cx, cy, innerDotRadius, paint)
    }

    // ── Timezone label ──────────────────────────────────────────────────

    private fun drawTimezoneLabel(canvas: Canvas, cx: Float, cy: Float, outerRadius: Float, zoneId: ZoneId) {
        val zdt = ZonedDateTime.now(zoneId)
        val tzAbbreviation = zdt.zone.getDisplayName(
            java.time.format.TextStyle.SHORT, Locale.getDefault()
        )

        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.WHITE
        paint.textSize = outerRadius * 0.067f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(juraMedium, Typeface.BOLD)

        val labelY = cy + outerRadius * 0.12f
        canvas.drawText(tzAbbreviation, cx, labelY, paint)
    }

    // ── Timeline hour markers ───────────────────────────────────────────

    private fun drawTimelineHourMarkers(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        arcStrokeWidth: Float, isAmbient: Boolean,
    ) {
        val arcRadius = outerRadius - arcStrokeWidth / 2f
        val markerRadius = arcRadius - arcStrokeWidth * 0.8f

        data class HourMarker(val label: String, val canvasAngle: Float, val suffix: String? = null, val isMain: Boolean = false)
        val hourMarkers = listOf(
            HourMarker("12", -90f, "pm", isMain = true),
            HourMarker("2", -60f),
            HourMarker("4", -30f),
            HourMarker("6", 0f, isMain = true),
            HourMarker("8", 30f),
            HourMarker("10", 60f),
            HourMarker("12", 90f, "am", isMain = true),
            HourMarker("2", 120f),
            HourMarker("4", 150f),
            HourMarker("6", 180f, isMain = true),
            HourMarker("8", -150f),
            HourMarker("10", -120f),
        )

        // Paint configurations
        val mainOutlinePaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = outerRadius * 0.121f
            textAlign = Paint.Align.CENTER
            typeface = juraSemibold
            style = Paint.Style.STROKE
            strokeWidth = outerRadius * 0.009f
        }
        val mainPaint = Paint().apply {
            isAntiAlias = true
            color = if (isAmbient) Color.GRAY else Color.parseColor("#E0E0E0")
            textSize = outerRadius * 0.121f
            textAlign = Paint.Align.CENTER
            typeface = juraSemibold
        }
        val secondaryOutlinePaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = outerRadius * 0.084f
            textAlign = Paint.Align.CENTER
            typeface = juraMedium
            style = Paint.Style.STROKE
            strokeWidth = outerRadius * 0.006f
        }
        val secondaryPaint = Paint().apply {
            isAntiAlias = true
            color = if (isAmbient) Color.DKGRAY else Color.parseColor("#C0C0C0")
            textSize = outerRadius * 0.084f
            textAlign = Paint.Align.CENTER
            typeface = juraMedium
        }
        val suffixOutlinePaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = outerRadius * 0.065f
            textAlign = Paint.Align.CENTER
            typeface = juraMedium
            style = Paint.Style.STROKE
            strokeWidth = outerRadius * 0.005f
        }
        val suffixPaint = Paint().apply {
            isAntiAlias = true
            color = if (isAmbient) Color.DKGRAY else Color.parseColor("#B0B0B0")
            textSize = outerRadius * 0.065f
            textAlign = Paint.Align.CENTER
            typeface = juraMedium
        }

        hourMarkers.forEach { marker ->
            val radians = Math.toRadians(marker.canvasAngle.toDouble())
            val x = cx + markerRadius * cos(radians).toFloat()
            val y = cy + markerRadius * sin(radians).toFloat()

            val outlinePaint = if (marker.isMain) mainOutlinePaint else secondaryOutlinePaint
            val fillPaint = if (marker.isMain) mainPaint else secondaryPaint

            canvas.drawText(marker.label, x, y + fillPaint.textSize * 0.35f, outlinePaint)
            canvas.drawText(marker.label, x, y + fillPaint.textSize * 0.35f, fillPaint)

            marker.suffix?.let { suffix ->
                val suffixY = y + fillPaint.textSize * 1.0f
                canvas.drawText(suffix, x, suffixY, suffixOutlinePaint)
                canvas.drawText(suffix, x, suffixY, suffixPaint)
            }
        }
    }

    // ── Countdown on indicator ──────────────────────────────────────────

    private fun drawCountdownOnIndicator(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        currentTimeAngle: Float, text: String,
    ) {
        val arcStrokeWidth = outerRadius * 0.08f
        val arcRadius = outerRadius - arcStrokeWidth / 2f
        val indicatorInnerRadius = arcRadius - arcStrokeWidth * 1.2f
        val tailRadius = arcStrokeWidth * 0.32f
        val textRadius = indicatorInnerRadius - tailRadius - outerRadius * 0.04f

        paint.reset()
        paint.isAntiAlias = true
        paint.color = 0xFFFF8C00.toInt() // orange, same as arrow
        paint.textSize = outerRadius * 0.08f
        paint.typeface = juraMedium

        // Measure text width to compute the arc sweep
        val textWidth = paint.measureText(text)
        val arcCircumference = (2f * Math.PI * textRadius).toFloat()
        val sweepDeg = (textWidth / arcCircumference) * 360f

        // Center the text arc on the indicator angle
        val startAngle = currentTimeAngle - sweepDeg / 2f

        // Flip text when in the bottom half of the circle (90°-270°) so it stays readable
        val normalizedAngle = ((currentTimeAngle % 360f) + 360f) % 360f
        val flipped = normalizedAngle > 90f && normalizedAngle < 270f

        val rect = RectF(cx - textRadius, cy - textRadius, cx + textRadius, cy + textRadius)
        val path = android.graphics.Path()
        if (flipped) {
            path.addArc(rect, startAngle + sweepDeg, -sweepDeg)
        } else {
            path.addArc(rect, startAngle, sweepDeg)
        }

        paint.textAlign = Paint.Align.CENTER
        val vOffset = if (flipped) -paint.textSize * 0.15f else paint.textSize * 0.35f

        canvas.drawTextOnPath(text, path, 0f, vOffset, paint)
    }

    // ── Calendar icon (drawn with Canvas primitives) ──────────────────

    private fun drawCalendarIcon(
        canvas: Canvas, cx: Float, cy: Float, size: Float,
    ) {
        val half = size / 2f
        val cornerRadius = size * 0.15f
        val headerHeight = size * 0.32f
        val strokeW = size * 0.07f
        val outlineColor = 0xFFAAAAAA.toInt()

        val iconPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = outlineColor
        }

        // Body outline (rounded rect)
        val body = RectF(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(body, cornerRadius, cornerRadius, iconPaint)

        // Header line (horizontal divider)
        canvas.drawLine(cx - half, cy - half + headerHeight, cx + half, cy - half + headerHeight, iconPaint)

        // Two small "rings" on top
        iconPaint.strokeWidth = size * 0.09f
        iconPaint.strokeCap = Paint.Cap.ROUND
        val ringY0 = cy - half - size * 0.06f
        val ringY1 = cy - half + size * 0.10f
        val ringOffsetX = half * 0.45f
        canvas.drawLine(cx - ringOffsetX, ringY0, cx - ringOffsetX, ringY1, iconPaint)
        canvas.drawLine(cx + ringOffsetX, ringY0, cx + ringOffsetX, ringY1, iconPaint)

        // Grid dots (3x2) outline
        iconPaint.style = Paint.Style.FILL
        val dotRadius = size * 0.055f
        val gridTop = cy - half + headerHeight + size * 0.16f
        val gridRowSpacing = size * 0.22f
        val gridColSpacing = size * 0.26f
        for (row in 0..1) {
            for (col in -1..1) {
                canvas.drawCircle(
                    cx + col * gridColSpacing,
                    gridTop + row * gridRowSpacing,
                    dotRadius, iconPaint
                )
            }
        }
    }

    // ── Event popup (center of screen) ────────────────────────────────

    private fun drawEventPopup(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        eventLayout: EventLayout, zoneId: ZoneId, allEvents: List<EventLayout>,
    ) {
        val event = eventLayout.event
        val popupRadius = outerRadius * 0.52f
        val shadowRadius = popupRadius + outerRadius * 0.22f

        // Shadow gradient (radial: solid black center -> transparent edge)
        val shadowPaint = Paint().apply {
            isAntiAlias = true
            shader = android.graphics.RadialGradient(
                cx, cy, shadowRadius,
                intArrayOf(
                    0xFF000000.toInt(),
                    0xF0000000.toInt(),
                    0x80000000.toInt(),
                    0x00000000.toInt(),
                ),
                floatArrayOf(0f, popupRadius / shadowRadius, (popupRadius + shadowRadius) / (2f * shadowRadius), 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, shadowRadius, shadowPaint)

        // Popup background circle
        paint.reset()
        paint.isAntiAlias = true
        paint.color = 0xFF222222.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, popupRadius, paint)

        // Border
        paint.color = 0xFF0A0A0A.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, popupRadius, paint)

        // Colored arc indicator on edge of popup
        val eventColor = event.color ?: 0xFFA4A4A5.toInt()
        val anchorAngle = (eventLayout.startAngle + eventLayout.endAngle) / 2f
        val arcRect = RectF(cx - popupRadius, cy - popupRadius, cx + popupRadius, cy + popupRadius)
        paint.color = eventColor
        paint.strokeWidth = 2.5f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(arcRect, anchorAngle - 22f, 44f, false, paint)

        // Prev/Next neighbor arcs
        val sorted = allEvents.sortedBy { it.startMinutes }
        val currentIdx = sorted.indexOfFirst {
            it.event.id == event.id && it.event.startDateTime == event.startDateTime
        }
        val prevEvent = if (currentIdx > 0) sorted[currentIdx - 1] else null
        val nextEvent = if (currentIdx in 0 until sorted.size - 1) sorted[currentIdx + 1] else null

        val neighborSweep = 32f
        val gap = 5f
        val arrowSize = outerRadius * 0.03f

        // Prev arc (clockwise / right of main arc = less angular position)
        val touchBandWidth = popupRadius * 0.38f
        val touchInnerR = popupRadius - touchBandWidth / 2f
        val touchOuterR = popupRadius + touchBandWidth / 2f
        val touchRect = RectF(cx - touchOuterR, cy - touchOuterR, cx + touchOuterR, cy + touchOuterR)
        val touchInnerRect = RectF(cx - touchInnerR, cy - touchInnerR, cx + touchInnerR, cy + touchInnerR)

        prevEvent?.let { prev ->
            val prevColor = prev.event.color ?: 0xFFA4A4A5.toInt()
            val prevStart = anchorAngle - 22f - gap - neighborSweep

            // Blue touch zone background
            paint.style = Paint.Style.FILL
            paint.color = 0x332196F3.toInt()
            val bgPath = android.graphics.Path().apply {
                arcTo(touchRect, prevStart, neighborSweep, true)
                arcTo(touchInnerRect, prevStart + neighborSweep, -neighborSweep)
                close()
            }
            canvas.drawPath(bgPath, paint)

            paint.color = prevColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(arcRect, prevStart, neighborSweep, false, paint)

            // Arrow at the tail (clockwise end = prevStart)
            val arrowAngleRad = Math.toRadians(prevStart.toDouble())
            val arrowCx = cx + popupRadius * cos(arrowAngleRad).toFloat()
            val arrowCy = cy + popupRadius * sin(arrowAngleRad).toFloat()
            // Arrow points clockwise (toward less angle)
            val tangentRad = arrowAngleRad - Math.PI / 2.0
            val perpRad = arrowAngleRad
            val path = android.graphics.Path().apply {
                moveTo(
                    arrowCx + arrowSize * cos(tangentRad).toFloat(),
                    arrowCy + arrowSize * sin(tangentRad).toFloat()
                )
                lineTo(
                    arrowCx - arrowSize * cos(tangentRad).toFloat() + arrowSize * 0.5f * cos(perpRad).toFloat(),
                    arrowCy - arrowSize * sin(tangentRad).toFloat() + arrowSize * 0.5f * sin(perpRad).toFloat()
                )
                lineTo(
                    arrowCx - arrowSize * cos(tangentRad).toFloat() - arrowSize * 0.5f * cos(perpRad).toFloat(),
                    arrowCy - arrowSize * sin(tangentRad).toFloat() - arrowSize * 0.5f * sin(perpRad).toFloat()
                )
                close()
            }
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
        }

        // Next arc (counter-clockwise / left of main arc = more angular position)
        nextEvent?.let { next ->
            val nextColor = next.event.color ?: 0xFFA4A4A5.toInt()
            val nextStart = anchorAngle + 22f + gap

            // Blue touch zone background
            paint.style = Paint.Style.FILL
            paint.color = 0x332196F3.toInt()
            val bgPath = android.graphics.Path().apply {
                arcTo(touchRect, nextStart, neighborSweep, true)
                arcTo(touchInnerRect, nextStart + neighborSweep, -neighborSweep)
                close()
            }
            canvas.drawPath(bgPath, paint)

            paint.color = nextColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(arcRect, nextStart, neighborSweep, false, paint)

            // Arrow at the tail (counter-clockwise end = nextStart + neighborSweep)
            val arrowAngleRad = Math.toRadians((nextStart + neighborSweep).toDouble())
            val arrowCx = cx + popupRadius * cos(arrowAngleRad).toFloat()
            val arrowCy = cy + popupRadius * sin(arrowAngleRad).toFloat()
            // Arrow points counter-clockwise (toward more angle)
            val tangentRad = arrowAngleRad + Math.PI / 2.0
            val perpRad = arrowAngleRad
            val path = android.graphics.Path().apply {
                moveTo(
                    arrowCx + arrowSize * cos(tangentRad).toFloat(),
                    arrowCy + arrowSize * sin(tangentRad).toFloat()
                )
                lineTo(
                    arrowCx - arrowSize * cos(tangentRad).toFloat() + arrowSize * 0.5f * cos(perpRad).toFloat(),
                    arrowCy - arrowSize * sin(tangentRad).toFloat() + arrowSize * 0.5f * sin(perpRad).toFloat()
                )
                lineTo(
                    arrowCx - arrowSize * cos(tangentRad).toFloat() - arrowSize * 0.5f * cos(perpRad).toFloat(),
                    arrowCy - arrowSize * sin(tangentRad).toFloat() - arrowSize * 0.5f * sin(perpRad).toFloat()
                )
                close()
            }
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
        }

        // Calendar icon at the top of the popup
        val iconSize = outerRadius * 0.09f
        val iconTop = cy - popupRadius * 0.65f
        drawCalendarIcon(canvas, cx, iconTop, iconSize)

        // Text content
        val startTime = ZonedDateTime.ofInstant(event.startDateTime, zoneId)
        val endTime = ZonedDateTime.ofInstant(event.endDateTime, zoneId)
        val timeText = "%02d:%02d - %02d:%02d".format(
            startTime.hour, startTime.minute, endTime.hour, endTime.minute
        )
        val calendarLabel = event.calendarName ?: event.calendarId?.takeIf { it.isNotBlank() }

        val textPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = nunitoMedium
        }

        // Calendar label directly below the icon
        calendarLabel?.let {
            textPaint.color = 0xFF888888.toInt()
            textPaint.textSize = outerRadius * 0.09f
            val labelY = iconTop + iconSize * 0.8f + outerRadius * 0.09f
            canvas.drawText(it, cx, labelY, textPaint)
        }

        // Time + title grouped together in the center
        val groupSpacing = outerRadius * 0.08f
        val timeFontSize = outerRadius * 0.11f
        val titleFontSize = outerRadius * 0.10f
        val groupHeight = timeFontSize + groupSpacing + titleFontSize
        val groupTop = cy - groupHeight / 2f + outerRadius * 0.06f

        // Time
        textPaint.color = Color.WHITE
        textPaint.textSize = timeFontSize
        canvas.drawText(timeText, cx, groupTop + timeFontSize * 0.8f, textPaint)

        // Title
        textPaint.color = 0xFFCCCCCC.toInt()
        textPaint.textSize = titleFontSize
        canvas.drawText(event.title, cx, groupTop + timeFontSize + groupSpacing + titleFontSize * 0.8f, textPaint)
    }

    // ── Date picker popup ─────────────────────────────────────────────

    private fun drawDatePickerPopup(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        actualToday: LocalDate,
    ) {
        val popupRadius = outerRadius * 0.60f
        val shadowRadius = popupRadius + outerRadius * 0.22f

        // Shadow gradient
        val shadowPaint = Paint().apply {
            isAntiAlias = true
            shader = android.graphics.RadialGradient(
                cx, cy, shadowRadius,
                intArrayOf(
                    0xFF000000.toInt(),
                    0xF0000000.toInt(),
                    0x80000000.toInt(),
                    0x00000000.toInt(),
                ),
                floatArrayOf(0f, popupRadius / shadowRadius, (popupRadius + shadowRadius) / (2f * shadowRadius), 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, shadowRadius, shadowPaint)

        // Background circle
        paint.reset()
        paint.isAntiAlias = true
        paint.color = 0xFF222222.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, popupRadius, paint)

        // Border
        paint.color = 0xFF0A0A0A.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, popupRadius, paint)

        // "This week" label at top
        paint.reset()
        paint.isAntiAlias = true
        paint.color = 0xFF888888.toInt()
        paint.textSize = outerRadius * 0.08f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = nunitoMedium
        canvas.drawText("This week", cx, cy - popupRadius * 0.55f, paint)

        // Calculate the Monday of the current week
        val firstDayOfWeek = java.time.DayOfWeek.MONDAY
        val weekStart = actualToday.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val selectedDate = actualToday.plusDays(selectedDayOffset.toLong())

        // 7 days along a bottom arc from 210° to 330°
        val arcStartDeg = 210f
        val arcEndDeg = 330f
        val arcSpan = arcEndDeg - arcStartDeg
        val daySpacing = arcSpan / 6f // 6 gaps for 7 days
        val dayRadius = popupRadius * 0.82f

        val dayAbbreviations = listOf("M", "T", "W", "T", "F", "S", "S")

        for (i in 0 until 7) {
            val date = weekStart.plusDays(i.toLong())
            val angleDeg = arcStartDeg + i * daySpacing
            val (dx, dy) = polarXY(cx, cy, dayRadius, angleDeg)

            val isThisToday = date == actualToday
            val isSelected = date == selectedDate
            val dayOffset = ChronoUnit.DAYS.between(actualToday, date).toInt()

            // Background indicator for today or selected
            if (isThisToday) {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = 0xFF4FC3F7.toInt()
                paint.style = Paint.Style.FILL
                canvas.drawCircle(dx, dy + outerRadius * 0.01f, outerRadius * 0.08f, paint)
            } else if (isSelected && !isThisToday) {
                paint.reset()
                paint.isAntiAlias = true
                paint.color = 0xFF4FC3F7.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawCircle(dx, dy + outerRadius * 0.01f, outerRadius * 0.08f, paint)
            }

            // Day number
            paint.reset()
            paint.isAntiAlias = true
            paint.textSize = outerRadius * 0.10f
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = juraSemibold
            paint.color = when {
                isThisToday -> 0xFF1A1A1A.toInt() // dark on cyan bg
                isSelected -> 0xFF4FC3F7.toInt()
                else -> 0xFFCCCCCC.toInt()
            }
            canvas.drawText("${date.dayOfMonth}", dx, dy + outerRadius * 0.04f, paint)

            // Day abbreviation above
            paint.textSize = outerRadius * 0.07f
            paint.typeface = nunitoMedium
            paint.color = when {
                isThisToday -> 0xFF1A1A1A.toInt()
                isSelected -> 0xFF4FC3F7.toInt()
                else -> 0xFF888888.toInt()
            }
            canvas.drawText(dayAbbreviations[i], dx, dy - outerRadius * 0.06f, paint)
        }
    }

    // ── Task list (bottom half) ─────────────────────────────────────────

    private fun drawTaskList(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        tasks: List<TaskLayout>,
    ) {
        val sortedTasks = tasks.sortedBy { it.task.completed }
        val visibleCount = 4
        val maxScroll = (sortedTasks.size - visibleCount).coerceAtLeast(0)
        taskListScrollIndex = taskListScrollIndex.coerceIn(0, maxScroll)

        val startIdx = taskListScrollIndex
        val endIdx = (startIdx + visibleCount).coerceAtMost(sortedTasks.size)
        val visibleTasks = sortedTasks.subList(startIdx, endIdx)

        val listTop = cy + outerRadius * 0.18f
        val rowHeight = outerRadius * 0.12f
        val textSize = outerRadius * 0.075f
        val checkSize = outerRadius * 0.04f

        val textPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            typeface = nunitoMedium
            this.textSize = textSize
        }

        for ((i, taskLayout) in visibleTasks.withIndex()) {
            val task = taskLayout.task
            val y = listTop + i * rowHeight

            // Checkbox circle
            paint.reset()
            paint.isAntiAlias = true
            paint.style = if (task.completed) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = if (task.completed) 0xFF4CAF50.toInt() else 0xFF666666.toInt()
            val checkX = cx - outerRadius * 0.30f
            canvas.drawCircle(checkX, y + textSize * 0.3f, checkSize, paint)

            // Checkmark inside if completed
            if (task.completed) {
                paint.style = Paint.Style.STROKE
                paint.color = Color.WHITE
                paint.strokeWidth = 1.5f
                paint.strokeCap = Paint.Cap.ROUND
                val s = checkSize * 0.5f
                canvas.drawLine(
                    checkX - s * 0.3f, y + textSize * 0.3f,
                    checkX, y + textSize * 0.3f + s * 0.4f, paint
                )
                canvas.drawLine(
                    checkX, y + textSize * 0.3f + s * 0.4f,
                    checkX + s * 0.5f, y + textSize * 0.3f - s * 0.3f, paint
                )
            }

            // Task title
            val titleX = checkX + checkSize + outerRadius * 0.04f
            val maxWidth = outerRadius * 0.55f
            textPaint.color = if (task.completed) 0xFF666666.toInt() else 0xFFDDDDDD.toInt()
            if (task.completed) {
                textPaint.flags = textPaint.flags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                textPaint.flags = textPaint.flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            // Truncate text if too wide
            val title = if (textPaint.measureText(task.title) > maxWidth) {
                var truncated = task.title
                while (truncated.isNotEmpty() && textPaint.measureText("$truncated...") > maxWidth) {
                    truncated = truncated.dropLast(1)
                }
                "$truncated..."
            } else {
                task.title
            }
            canvas.drawText(title, titleX, y + textSize * 0.7f, textPaint)
        }

        // Scroll indicators if there are more tasks
        if (sortedTasks.size > visibleCount) {
            paint.reset()
            paint.isAntiAlias = true
            paint.color = 0xFF888888.toInt()
            paint.style = Paint.Style.FILL

            if (taskListScrollIndex > 0) {
                // Up arrow
                val arrowY = listTop - rowHeight * 0.3f
                val path = android.graphics.Path().apply {
                    moveTo(cx, arrowY - outerRadius * 0.02f)
                    lineTo(cx - outerRadius * 0.03f, arrowY + outerRadius * 0.02f)
                    lineTo(cx + outerRadius * 0.03f, arrowY + outerRadius * 0.02f)
                    close()
                }
                canvas.drawPath(path, paint)
            }

            if (taskListScrollIndex < maxScroll) {
                // Down arrow
                val arrowY = listTop + visibleCount * rowHeight + rowHeight * 0.1f
                val path = android.graphics.Path().apply {
                    moveTo(cx, arrowY + outerRadius * 0.02f)
                    lineTo(cx - outerRadius * 0.03f, arrowY - outerRadius * 0.02f)
                    lineTo(cx + outerRadius * 0.03f, arrowY - outerRadius * 0.02f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    // ── Data processing ─────────────────────────────────────────────────

    private fun filterSnapshotByDate(
        snapshot: DailySnapshot, targetDate: LocalDate, zoneId: ZoneId,
    ): DailySnapshot {
        val filteredEvents = snapshot.events.filter { event ->
            val eventDate = ZonedDateTime.ofInstant(event.startDateTime, zoneId).toLocalDate()
            eventDate == targetDate || event.allDay
        }
        val filteredTasks = snapshot.tasks.filter { task ->
            val taskDate = ZonedDateTime.ofInstant(task.dateTime, zoneId).toLocalDate()
            taskDate == targetDate
        }
        return snapshot.copy(events = filteredEvents, tasks = filteredTasks)
    }

    private fun buildTimelineLayout(
        snapshot: DailySnapshot, zoneId: ZoneId, now: Instant,
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

    // ── Utility functions ───────────────────────────────────────────────

    private fun sweepForArc(startAngle: Float, endAngle: Float): Float {
        val sweep = if (endAngle >= startAngle) endAngle - startAngle else (360f - startAngle) + endAngle
        return if (sweep <= 0f) 1f else sweep
    }

    private fun polarXY(cx: Float, cy: Float, radius: Float, angleDegrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(angleDegrees.toDouble())
        return Pair(
            cx + radius * cos(radians).toFloat(),
            cy + radius * sin(radians).toFloat(),
        )
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun destroy() {
        Log.d(TAG, "Renderer destroyed")
    }

    // ── Tap handling ────────────────────────────────────────────────────

    fun onTap(x: Int, y: Int) {
        val layout = lastLayout
        val cx = lastBoundsWidth / 2f
        val cy = lastBoundsHeight / 2f
        val outerRadius = min(lastBoundsWidth, lastBoundsHeight) / 2f
        val dx = x - cx
        val dy = y - cy
        val radius = kotlin.math.hypot(dx, dy).toFloat()
        val angle = angleFromTouch(dx, dy)

        // Date picker popup interaction
        if (showDatePicker) {
            val popupRadius = outerRadius * 0.60f

            // Tap outside popup closes it
            if (radius > popupRadius) {
                showDatePicker = false
                Log.d(TAG, "Tap: date picker closed (outside)")
                return
            }

            // Check if tap is on one of the 7 day slots
            val actualToday = LocalDate.now()
            val weekStart = actualToday.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val dayRadius = popupRadius * 0.82f
            val arcStartDeg = 210f
            val arcEndDeg = 330f
            val daySpacing = (arcEndDeg - arcStartDeg) / 6f
            val hitRadius = outerRadius * 0.10f

            for (i in 0 until 7) {
                val angleDeg = arcStartDeg + i * daySpacing
                val (dayX, dayY) = polarXY(cx, cy, dayRadius, angleDeg)
                val distToDayCenter = kotlin.math.hypot((x - dayX).toDouble(), (y - dayY).toDouble()).toFloat()
                if (distToDayCenter <= hitRadius) {
                    val date = weekStart.plusDays(i.toLong())
                    selectedDayOffset = ChronoUnit.DAYS.between(actualToday, date).toInt()
                    showDatePicker = false
                    selectedEvent = null
                    taskListScrollIndex = 0
                    Log.d(TAG, "Tap: date picker selected day offset=$selectedDayOffset date=$date")
                    return
                }
            }

            // Tap inside popup but not on a day - just close it
            showDatePicker = false
            Log.d(TAG, "Tap: date picker closed (inside, no day hit)")
            return
        }

        // Check tap on date label to open date picker
        val timeY = cy - outerRadius * 0.14f
        val dateLabelY = timeY + outerRadius * 0.12f
        val dateLabelHalfWidth = outerRadius * 0.15f
        val dateLabelHalfHeight = outerRadius * 0.04f
        if (x >= cx - dateLabelHalfWidth && x <= cx + dateLabelHalfWidth &&
            y >= dateLabelY - dateLabelHalfHeight - outerRadius * 0.04f &&
            y <= dateLabelY + dateLabelHalfHeight
        ) {
            showDatePicker = true
            selectedEvent = null
            Log.d(TAG, "Tap: date label tapped, opening date picker")
            return
        }

        if (layout == null) return

        // If popup is visible, check for prev/next arc taps
        val sel = selectedEvent
        if (sel != null) {
            val popupRadius = outerRadius * 0.52f
            if (radius <= popupRadius * 1.15f && radius >= popupRadius * 0.7f) {
                val anchorAngle = (sel.startAngle + sel.endAngle) / 2f
                val tapAngle = angle
                val gap = 5f
                val neighborSweep = 32f

                // Prev arc region: anchorAngle - 22 - gap - neighborSweep to anchorAngle - 22 - gap
                val prevStart = anchorAngle - 22f - gap - neighborSweep
                val prevEnd = anchorAngle - 22f - gap
                // Next arc region: anchorAngle + 22 + gap to anchorAngle + 22 + gap + neighborSweep
                val nextStart = anchorAngle + 22f + gap
                val nextEnd = anchorAngle + 22f + gap + neighborSweep

                val sorted = layout.events.sortedBy { it.startMinutes }
                val currentIdx = sorted.indexOfFirst {
                    it.event.id == sel.event.id && it.event.startDateTime == sel.event.startDateTime
                }

                if (angleInArcRange(tapAngle, prevStart, prevEnd) && currentIdx > 0) {
                    selectedEvent = sorted[currentIdx - 1]
                    Log.d(TAG, "Tap: prev event '${selectedEvent?.event?.title}'")
                    return
                }
                if (angleInArcRange(tapAngle, nextStart, nextEnd) && currentIdx in 0 until sorted.size - 1) {
                    selectedEvent = sorted[currentIdx + 1]
                    Log.d(TAG, "Tap: next event '${selectedEvent?.event?.title}'")
                    return
                }
            }
        }

        val hitEvent = findEventHit(layout.events, radius, angle)
        if (hitEvent != null) {
            val isSame = selectedEvent?.let {
                it.event.id == hitEvent.event.id &&
                    it.event.startDateTime == hitEvent.event.startDateTime
            } == true
            selectedEvent = if (isSame) null else hitEvent
            Log.d(TAG, "Tap: event '${hitEvent.event.title}' selected=${!isSame}")
        } else if (dy > 0 && layout.tasks.isNotEmpty()) {
            // Tap in bottom half: scroll task list
            val maxScroll = (layout.tasks.size - 4).coerceAtLeast(0)
            taskListScrollIndex = (taskListScrollIndex + 1).coerceAtMost(maxScroll)
            if (taskListScrollIndex > maxScroll) taskListScrollIndex = 0
            Log.d(TAG, "Tap: task list scroll to $taskListScrollIndex")
        } else {
            selectedEvent = null
            Log.d(TAG, "Tap: no hit, deselected")
        }
    }

    private fun angleInArcRange(angle: Float, start: Float, end: Float): Boolean {
        val normAngle = ((angle % 360f) + 360f) % 360f
        val normStart = ((start % 360f) + 360f) % 360f
        val normEnd = ((end % 360f) + 360f) % 360f
        return if (normStart <= normEnd) {
            normAngle in normStart..normEnd
        } else {
            normAngle >= normStart || normAngle <= normEnd
        }
    }

    private fun findEventHit(events: List<EventLayout>, radius: Float, angle: Float): EventLayout? {
        if (events.isEmpty()) return null
        val outerRadius = min(lastBoundsWidth, lastBoundsHeight) / 2f
        val arcStrokeWidth = outerRadius * 0.08f
        val eventStrokeWidth = arcStrokeWidth * 1.5f
        val eventArcRadius = outerRadius - eventStrokeWidth * 0.4f
        val inwardTouchExpansion = arcStrokeWidth * 3.8f
        val outwardTouchExpansion = arcStrokeWidth * 1.2f
        val rMin = eventArcRadius - eventStrokeWidth / 2f - inwardTouchExpansion
        val rMax = eventArcRadius + eventStrokeWidth / 2f + outwardTouchExpansion

        val arcRadius = outerRadius - arcStrokeWidth / 2f
        val verticalLineInner = arcRadius - arcStrokeWidth / 2f
        val verticalLineOuter = arcRadius + arcStrokeWidth / 2f
        val radiusTolerance = arcStrokeWidth * 0.5f
        val inwardPunctualExpansion = arcStrokeWidth * 3.8f
        val punctualAngleTolerance = 6f

        for (event in events) {
            val isPunctual = event.startMinutes == event.endMinutes
            if (isPunctual) {
                val midAngle = (event.startAngle + event.endAngle) / 2f
                if (radius in (verticalLineInner - radiusTolerance - inwardPunctualExpansion)..(verticalLineOuter + radiusTolerance) &&
                    angleDistance(midAngle, angle) <= punctualAngleTolerance
                ) return event
            } else if (radius in rMin..rMax && isAngleWithinArc(angle, event.startAngle, event.endAngle)) {
                return event
            }
        }
        return null
    }

    private fun angleDistance(a: Float, b: Float): Float {
        val diff = abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    // ── Selected event highlight ────────────────────────────────────────

    private fun drawSelectedHighlight(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        arcStrokeWidth: Float, eventLayout: EventLayout,
    ) {
        val eventColor = eventLayout.event.color ?: 0xFFA4A4A5.toInt()
        val highlightColor = applyAlpha(eventColor, 0.4f)
        val isPunctual = eventLayout.startMinutes == eventLayout.endMinutes

        val eventStrokeWidth = arcStrokeWidth * 1.5f
        val eventArcRadius = outerRadius - eventStrokeWidth * 0.4f
        val arcRadius = outerRadius - arcStrokeWidth / 2f
        val inwardTouchExpansion = arcStrokeWidth * 3.8f
        val outwardTouchExpansion = arcStrokeWidth * 1.2f
        val rMin = eventArcRadius - eventStrokeWidth / 2f - inwardTouchExpansion
        val rMax = eventArcRadius + eventStrokeWidth / 2f + outwardTouchExpansion

        paint.reset()
        paint.isAntiAlias = true
        paint.color = highlightColor
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT

        if (isPunctual) {
            val verticalLineInner = arcRadius - arcStrokeWidth / 2f
            val verticalLineOuter = arcRadius + arcStrokeWidth / 2f
            val radiusTolerance = arcStrokeWidth * 0.5f
            val inwardPunctualExpansion = arcStrokeWidth * 3.8f
            val punctualTouchInner = verticalLineInner - radiusTolerance - inwardPunctualExpansion
            val punctualTouchOuter = verticalLineOuter + radiusTolerance
            val touchCenterR = (punctualTouchInner + punctualTouchOuter) / 2f
            val touchThickness = punctualTouchOuter - punctualTouchInner
            val midAngle = (eventLayout.startAngle + eventLayout.endAngle) / 2f
            val rect = RectF(cx - touchCenterR, cy - touchCenterR, cx + touchCenterR, cy + touchCenterR)
            paint.strokeWidth = touchThickness
            canvas.drawArc(rect, midAngle - 6f, 12f, false, paint)
        } else {
            val touchThickness = rMax - rMin
            val touchCenterR = (rMin + rMax) / 2f
            val rect = RectF(cx - touchCenterR, cy - touchCenterR, cx + touchCenterR, cy + touchCenterR)
            val fullSweep = sweepForArc(eventLayout.startAngle, eventLayout.endAngle)
            paint.strokeWidth = touchThickness
            canvas.drawArc(rect, eventLayout.startAngle, fullSweep, false, paint)
        }
    }

    // ── Selected event description (curved text) ────────────────────────

    private fun drawSelectedEventDescription(
        canvas: Canvas, cx: Float, cy: Float, outerRadius: Float,
        arcStrokeWidth: Float, eventLayout: EventLayout, zoneId: ZoneId,
    ) {
        val event = eventLayout.event
        val arcRadius = outerRadius - arcStrokeWidth / 2f
        val descriptionRadius = arcRadius - arcStrokeWidth * 1.9f

        val sweepAngle = sweepForArc(eventLayout.startAngle, eventLayout.endAngle)
        val midAngle = (eventLayout.startAngle + eventLayout.endAngle) / 2f
        val normalizedMid = ((midAngle % 360f) + 360f) % 360f
        val isPast6pm = normalizedMid in 0f..180f

        val titlePaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = outerRadius * 0.10f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            letterSpacing = 0.08f
        }

        val titleWidth = titlePaint.measureText(event.title)
        val requiredSweep = ((titleWidth * 1.15f) / (2f * Math.PI.toFloat() * descriptionRadius)) * 360f
        val textSweep = maxOf(requiredSweep, sweepAngle).coerceIn(60f, 220f)
        val adjustedStartAngle = eventLayout.startAngle - 13f

        val titlePath = Path()
        val titleArcRect = RectF(
            cx - descriptionRadius, cy - descriptionRadius,
            cx + descriptionRadius, cy + descriptionRadius,
        )

        if (isPast6pm) {
            titlePath.addArc(titleArcRect, adjustedStartAngle + textSweep + 4f, -(textSweep + 4f))
            canvas.drawTextOnPath(event.title, titlePath, 0f, -titlePaint.textSize * 0.35f, titlePaint)
        } else {
            titlePath.addArc(titleArcRect, adjustedStartAngle, textSweep + 4f)
            canvas.drawTextOnPath(event.title, titlePath, 0f, titlePaint.textSize * 0.35f, titlePaint)
        }
    }

    // ── Data classes (local to renderer) ────────────────────────────────

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

    private data class TaskLayout(
        val task: Task,
        val angle: Float,
        val isPast: Boolean,
    )
}
