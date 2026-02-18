package com.example.myapplication.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.example.myapplication.data.repository.DataLayerCalendarRepository
import com.example.myapplication.data.repository.CalendarRepository
import com.example.myapplication.data.repository.MergedSnapshotRepository
import com.example.myapplication.data.repository.SnapshotRepository
import com.example.myapplication.data.repository.SleepRepository
import com.example.myapplication.data.repository.SunTimesRepository
import com.example.myapplication.presentation.theme.MyApplicationTheme
import com.example.myapplication.presentation.timeline.TimelineScreen
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var calendarRepository: DataLayerCalendarRepository
    private lateinit var sleepRepository: SleepRepository
    private lateinit var sunTimesRepository: SunTimesRepository
    private lateinit var repository: MergedSnapshotRepository
    private var hasCalendarPermission = mutableStateOf(false)
    private var hasLocationPermission = false
    
    // Sync status feedback
    private val syncStatus = mutableStateOf<String?>(null)
    private val lastSyncTime = mutableStateOf<String?>(null)

    private val requestCalendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCalendarPermission.value = isGranted
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasLocationPermission = isGranted
        if (isGranted) {
            fetchLocationAndUpdateSunTimes()
        } else {
            // Use default sun times if location not granted
            repository.useDefaultSunTimes()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        calendarRepository = DataLayerCalendarRepository(applicationContext) { eventCount, lastSync ->
            // Callback when data is received from phone
            syncStatus.value = "✓ Synced $eventCount events"
            lastSyncTime.value = lastSync
            
            // Clear status after 3 seconds
            lifecycleScope.launch {
                delay(3000)
                syncStatus.value = null
            }
        }
        sleepRepository = SleepRepository(applicationContext)
        sunTimesRepository = SunTimesRepository(applicationContext)
        repository = MergedSnapshotRepository(
            calendarRepository = calendarRepository,
            sleepRepository = sleepRepository,
            sunTimesRepository = sunTimesRepository,
            scope = lifecycleScope,
        )

        // Calendar permission not needed - data comes from phone via Data Layer
        hasCalendarPermission.value = true

        hasLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasLocationPermission) {
            fetchLocationAndUpdateSunTimes()
        } else {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        setContent {
            WearApp(
                repository = repository,
                hasCalendarPermission = hasCalendarPermission.value,
                syncStatus = syncStatus.value,
                lastSyncTime = lastSyncTime.value,
                onRequestCalendarPermission = {
                    requestCalendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocationAndUpdateSunTimes() {
        if (!hasLocationPermission) {
            repository.useDefaultSunTimes()
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val cancellationToken = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            cancellationToken.token
        ).addOnSuccessListener { location: Location? ->
            lifecycleScope.launch {
                if (location != null) {
                    repository.refreshSunTimes(location.latitude, location.longitude)
                } else {
                    // Fallback: try last known location
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLocation: Location? ->
                        lifecycleScope.launch {
                            if (lastLocation != null) {
                                repository.refreshSunTimes(lastLocation.latitude, lastLocation.longitude)
                            } else {
                                // No location available, use defaults
                                repository.useDefaultSunTimes()
                            }
                        }
                    }.addOnFailureListener {
                        repository.useDefaultSunTimes()
                    }
                }
            }
        }.addOnFailureListener {
            repository.useDefaultSunTimes()
        }
    }
}

@Composable
fun WearApp(
    repository: SnapshotRepository,
    hasCalendarPermission: Boolean,
    syncStatus: String?,
    lastSyncTime: String?,
    onRequestCalendarPermission: () -> Unit,
) {
    MyApplicationTheme {
        if (hasCalendarPermission) {
            LaunchedEffect(Unit) {
                while (true) {
                    repository.refresh()
                    delay(60_000)
                }
            }
            
            Box(modifier = Modifier.fillMaxSize()) {
                TimelineScreen(repository = repository)
                
                // Sync status overlay
                if (syncStatus != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.8f))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = syncStatus,
                                color = Color.Green,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                            if (lastSyncTime != null) {
                                Text(
                                    text = "at $lastSyncTime",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Permissão de calendário necessária.\nToque para conceder.",
                    color = Color.White,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                )
            }
            LaunchedEffect(Unit) {
                delay(1000)
                onRequestCalendarPermission()
            }
        }
    }
}

