package com.example.myapplication.data.repository

import android.content.Context
import com.example.myapplication.data.model.SleepData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
/**
 * Fetches sleep session data from Health Connect (Samsung Health syncs here).
 * Exposes the most recent sleep session for the last 24–48 hours.
 */
class SleepRepository(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val _sleepData = MutableStateFlow<SleepData?>(null)
    val sleepData: StateFlow<SleepData?> = _sleepData

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            try {
                val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
                val status = androidx.health.connect.client.HealthConnectClient.getSdkStatus(context)
                if (status != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
                    _sleepData.value = null
                    return@withContext
                }
                val end = Instant.now()
                val start = end.minus(48, ChronoUnit.HOURS)
                val request = androidx.health.connect.client.request.ReadRecordsRequest(
                    androidx.health.connect.client.records.SleepSessionRecord::class,
                    timeRangeFilter = androidx.health.connect.client.time.TimeRangeFilter.between(
                        startTime = start,
                        endTime = end,
                    ),
                    pageSize = 5,
                )
                val response = client.readRecords(request)
                @Suppress("UNCHECKED_CAST")
                val records = response.records as List<androidx.health.connect.client.records.SleepSessionRecord>
                if (records.isEmpty()) {
                    _sleepData.value = null
                    return@withContext
                }
                // Use the most recent session (last in list, or max endTime)
                val session = records.maxByOrNull { it.endTime } ?: records.last()
                val startInstant = session.startTime
                val endInstant = session.endTime
                val durationMinutes = ChronoUnit.MINUTES.between(startInstant, endInstant).toInt()
                    .coerceAtLeast(0)
                val date = LocalDate.ofInstant(endInstant, zoneId)
                val quality = computeQualityFromStages(session)
                _sleepData.value = SleepData(
                    date = date,
                    startTime = startInstant,
                    endTime = endInstant,
                    durationMinutes = durationMinutes,
                    qualityScore = quality,
                )
            } catch (e: SecurityException) {
                _sleepData.value = null
            } catch (e: Exception) {
                _sleepData.value = null
            }
        }
    }

    /**
     * Quality 0–100 from stage distribution: (deep%*1.5 + rem%*1.2 + light%*0.8) / 3.5 * 100.
     */
    private fun computeQualityFromStages(
        session: androidx.health.connect.client.records.SleepSessionRecord,
    ): Int? {
        val stages = session.stages ?: return null
        if (stages.isEmpty()) return null
        var totalMs = 0L
        var deepMs = 0L
        var remMs = 0L
        var lightMs = 0L
        for (stage in stages) {
            val duration = java.time.Duration.between(stage.startTime, stage.endTime).toMillis()
            totalMs += duration
            // Stage type int: 2=AWAKE, 3=SLEEPING, 4=LIGHT, 5=DEEP, 6=REM (Health Connect)
            when (stage.stage) {
                5 -> deepMs += duration   // DEEP
                6 -> remMs += duration    // REM
                3, 4 -> lightMs += duration  // SLEEPING / LIGHT
                else -> { /* awake/unknown not counted */ }
            }
        }
        if (totalMs <= 0) return null
        val deepPct = deepMs.toDouble() / totalMs
        val remPct = remMs.toDouble() / totalMs
        val lightPct = lightMs.toDouble() / totalMs
        val score = ((deepPct * 1.5 + remPct * 1.2 + lightPct * 0.8) / 3.5 * 100).toInt()
        return score.coerceIn(0, 100)
    }
}
