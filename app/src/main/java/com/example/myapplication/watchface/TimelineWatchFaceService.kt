package com.example.myapplication.watchface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import android.view.SurfaceHolder
import androidx.wear.watchface.*
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import com.example.myapplication.data.repository.DataLayerCalendarRepository
import com.example.myapplication.data.repository.MergedSnapshotRepository
import com.example.myapplication.data.repository.SleepRepository
import com.example.myapplication.data.repository.SunTimesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

private const val TAG = "TimelineWatchFace"

class TimelineWatchFaceService : WatchFaceService() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TimelineWatchFaceService created")
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        Log.d(TAG, "Creating watch face")

        val renderer = TimelineWatchFaceRenderer(
            context = applicationContext,
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            currentUserStyleRepository = currentUserStyleRepository
        )

        Log.d(TAG, "Watch face created successfully")
        return WatchFace(
            watchFaceType = WatchFaceType.DIGITAL,
            renderer = renderer
        ).setTapListener(object : WatchFace.TapListener {
            override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
                if (tapType == TapType.UP) {
                    renderer.canvasRenderer.onTap(tapEvent.xPos, tapEvent.yPos)
                }
            }
        })
    }

    override fun createUserStyleSchema(): UserStyleSchema {
        return UserStyleSchema(emptyList())
    }

    override fun createComplicationSlotsManager(
        currentUserStyleRepository: CurrentUserStyleRepository
    ): ComplicationSlotsManager {
        return ComplicationSlotsManager(emptyList(), currentUserStyleRepository)
    }
}

class TimelineWatchFaceRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    currentUserStyleRepository: CurrentUserStyleRepository
) : Renderer.CanvasRenderer2<TimelineWatchFaceRenderer.TimelineSharedAssets>(
    surfaceHolder = surfaceHolder,
    currentUserStyleRepository = currentUserStyleRepository,
    watchState = watchState,
    canvasType = CanvasType.HARDWARE,
    interactiveDrawModeUpdateDelayMillis = 16L,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false
) {
    class TimelineSharedAssets : SharedAssets {
        override fun onDestroy() {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val calendarRepository = DataLayerCalendarRepository(context) { _, _ -> }
    private val sleepRepository = SleepRepository(context)
    private val sunTimesRepository = SunTimesRepository(context)
    private val repository = MergedSnapshotRepository(
        calendarRepository = calendarRepository,
        sleepRepository = sleepRepository,
        sunTimesRepository = sunTimesRepository,
        scope = scope
    )

    val canvasRenderer = TimelineCanvasRenderer(context, repository)

    init {
        // Keep the snapshot flow hot by collecting it (WhileSubscribed needs a subscriber)
        scope.launch {
            repository.snapshot.collect { snap ->
                Log.d(TAG, "Snapshot updated: ${snap != null}, events=${snap?.events?.size ?: 0}")
            }
        }

        scope.launch {
            Log.d(TAG, "Init: fetching data...")
            repository.useDefaultSunTimes()
            repository.refresh()
            Log.d(TAG, "Init: refresh done")

            // Periodic refresh every minute
            while (true) {
                kotlinx.coroutines.delay(60_000)
                repository.refresh()
            }
        }
    }

    override suspend fun createSharedAssets(): TimelineSharedAssets {
        return TimelineSharedAssets()
    }

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: TimelineSharedAssets
    ) {
        val isAmbient = renderParameters.drawMode == DrawMode.AMBIENT
        canvasRenderer.render(canvas, bounds, zonedDateTime, isAmbient)
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: TimelineSharedAssets
    ) {
        // No highlight layer needed
    }

    override fun onDestroy() {
        super.onDestroy()
        canvasRenderer.destroy()
        scope.cancel()
    }
}
