package com.example.myapplication.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.shredzone.commons.suncalc.SunTimes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Calculates sunrise and sunset times using commons-suncalc library.
 * Works offline without network requests.
 * Sun times are computed dynamically for any date using stored or approximate coordinates.
 */
class SunTimesRepository(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    data class SunTimesData(
        val sunriseMinutes: Int,  // Minutes since midnight
        val sunsetMinutes: Int,   // Minutes since midnight
    )

    private val _sunTimes = MutableStateFlow<SunTimesData?>(null)
    val sunTimes: StateFlow<SunTimesData?> = _sunTimes

    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null

    /**
     * Refresh sun times for the given location and store coordinates for per-date computation.
     */
    suspend fun refresh(latitude: Double, longitude: Double) {
        withContext(Dispatchers.IO) {
            lastLatitude = latitude
            lastLongitude = longitude
            val today = LocalDate.now(zoneId)
            _sunTimes.value = computeForDate(today)
        }
    }

    /**
     * Use approximate coordinates from timezone when location is unavailable.
     * Still computes dynamically (seasonal variation) instead of hardcoded 6am/6pm.
     */
    fun useDefaults() {
        val (lat, lon) = approximateCoordinatesForZone(zoneId)
        lastLatitude = lat
        lastLongitude = lon
        val today = LocalDate.now(zoneId)
        _sunTimes.value = computeForDate(today)
    }

    /**
     * Compute sunrise/sunset for any date using stored or approximate coordinates.
     */
    fun computeForDate(date: LocalDate): SunTimesData {
        val lat = lastLatitude ?: approximateCoordinatesForZone(zoneId).first
        val lon = lastLongitude ?: approximateCoordinatesForZone(zoneId).second
        return computeSunTimesForDate(date, lat, lon, zoneId)
    }

    companion object {
        /**
         * Pure computation of sun times for a date at given coordinates.
         */
        fun computeSunTimesForDate(
            date: LocalDate,
            latitude: Double,
            longitude: Double,
            zoneId: ZoneId,
        ): SunTimesData {
            return try {
                val times = SunTimes.compute()
                    .on(date)
                    .at(latitude, longitude)
                    .execute()
                val sunriseMinutes = times.rise?.let { lt ->
                    val local = lt.withZoneSameInstant(zoneId).toLocalTime()
                    local.hour * 60 + local.minute
                } ?: (6 * 60)
                val sunsetMinutes = times.set?.let { lt ->
                    val local = lt.withZoneSameInstant(zoneId).toLocalTime()
                    local.hour * 60 + local.minute
                } ?: (18 * 60)
                SunTimesData(sunriseMinutes = sunriseMinutes, sunsetMinutes = sunsetMinutes)
            } catch (e: Exception) {
                SunTimesData(sunriseMinutes = 6 * 60, sunsetMinutes = 18 * 60)
            }
        }

        /** Approximate (lat, lon) for common timezones when location is unavailable. */
        private fun approximateCoordinatesForZone(zoneId: ZoneId): Pair<Double, Double> {
            val zoneMap = mapOf(
                "America/New_York" to Pair(40.7, -74.0),
                "America/Los_Angeles" to Pair(34.0, -118.0),
                "America/Chicago" to Pair(41.9, -87.6),
                "America/Denver" to Pair(39.7, -105.0),
                "America/Sao_Paulo" to Pair(-23.5, -46.6),
                "America/Buenos_Aires" to Pair(-34.6, -58.4),
                "Europe/London" to Pair(51.5, -0.1),
                "Europe/Paris" to Pair(48.9, 2.3),
                "Europe/Berlin" to Pair(52.5, 13.4),
                "Asia/Tokyo" to Pair(35.7, 139.7),
                "Asia/Shanghai" to Pair(31.2, 121.5),
                "Asia/Kolkata" to Pair(28.6, 77.2),
                "Australia/Sydney" to Pair(-33.9, 151.2),
            )
            return zoneMap[zoneId.id] ?: run {
                val offsetSeconds = zoneId.rules.getOffset(Instant.now()).totalSeconds
                val lon = (offsetSeconds / 3600.0) * 15.0
                Pair(40.0, lon.coerceIn(-180.0, 180.0))  // Temperate latitude, approximate longitude
            }
        }
    }
}
