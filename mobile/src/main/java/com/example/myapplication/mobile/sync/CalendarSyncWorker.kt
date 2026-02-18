package com.example.myapplication.mobile.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodically syncs calendar from phone to watch (every 30 min when conditions are met).
 */
class CalendarSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        CalendarSyncHelper.syncToWatch(applicationContext)
        return Result.success()
    }
}
