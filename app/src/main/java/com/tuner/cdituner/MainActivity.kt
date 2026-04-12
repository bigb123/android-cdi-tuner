package com.tuner.cdituner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tuner.cdituner.ui.theme.CDITunerTheme

class MainActivity : ComponentActivity() {

  private lateinit var connectionManager: ConnectionManager
  private lateinit var speedProvider: SpeedProvider
  private lateinit var dataLogger: DataLogger
  private lateinit var connectionPreferences: ConnectionPreferences
  private var deviceInfo by mutableStateOf<String?>(null)
  
  // Battery saver state (GPS disabled when true)
  private var batterySaverEnabled by mutableStateOf(false)
  
  // Track if the app was launched by USB attachment (to skip auto-connect from preferences)
  private var launchedByUsb = false

  // Bluetooth permission launcher for Android 12+
  private val bluetoothPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    val allGranted = permissions.values.all { it }
    if (allGranted) {
      // Permissions granted, show device selector
      connectionManager.showBluetoothDeviceSelector()
    }
  }

  // Location permission launcher for GPS speedometer
  private val locationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    if (isGranted) {
      // Permission granted, start GPS updates
      speedProvider.startLocationUpdates()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Keep screen on while app is running (essential for motorcycle gauge display)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Initialize ConnectionManager
    connectionManager = ConnectionManager(this)
    
    // Initialize SpeedProvider for GPS speedometer
    speedProvider = SpeedProvider(this)
    
    // Initialize DataLogger for time-series logging
    dataLogger = DataLogger(this)
    dataLogger.loadFromFile() // Load previous session if available
    
    // Initialize preferences and load battery saver state
    connectionPreferences = ConnectionPreferences(this)
    batterySaverEnabled = connectionPreferences.isBatterySaverEnabled()

    // Check if launched by USB attachment
    launchedByUsb = handleIntent(intent)
    
    // If NOT launched by USB, auto-connect based on saved preferences
    if (!launchedByUsb) {
      connectionManager.autoConnectFromPreferences()
    }
    
    // Start GPS updates if permission is already granted AND battery saver is off
    if (!batterySaverEnabled && speedProvider.hasLocationPermission()) {
      speedProvider.startLocationUpdates()
    }

    setContent {
      CDITunerTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          MainScreen()
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  /**
   * Handle incoming intent.
   * Returns true if the intent was a USB device attachment (app was auto-started by USB).
   */
  private fun handleIntent(intent: Intent): Boolean {
    if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
      val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
      }
      device?.let {
        deviceInfo = "USB Device Detected - VID: ${it.vendorId}, PID: ${it.productId}"
        // Auto-connect to USB when device is attached
        connectionManager.connectUsb()
        return true
      }
    }
    return false
  }

  @Composable
  fun MainScreen() {
    val connectionType by connectionManager.connectionType.collectAsState()
    val connectionStatus by connectionManager.connectionStatus.collectAsState()
    val cdiData by connectionManager.receivedData.collectAsState()
    val timingMap by connectionManager.timingMap.collectAsState()
    val timingMapStatus by connectionManager.timingMapStatus.collectAsState()
    
    // GPS speed data
    val speedKmh by speedProvider.speedKmh.collectAsState()
    val hasGpsFix by speedProvider.hasGpsFix.collectAsState()
    
    // Data logger state
    val logDataPoints by dataLogger.dataPoints.collectAsState()
    val isRecording by dataLogger.isRecording.collectAsState()
    
    // Tab state - 0 = Gauges, 1 = Timing, 2 = Logging
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Connection settings menu state
    var showConnectionMenu by remember { mutableStateOf(false) }
    
    // Request location permission when Gauges tab is selected (only if battery saver is off)
    LaunchedEffect(selectedTab, batterySaverEnabled) {
      if (selectedTab == 0 && !batterySaverEnabled && !speedProvider.hasLocationPermission()) {
        requestLocationPermission()
      }
    }
    
    // Trigger timing map read when Timing tab is selected
    LaunchedEffect(selectedTab) {
      if (selectedTab == 1) {
        connectionManager.readTimingMapIfNeeded()
      }
    }
    
    // Log data when recording and new CDI data arrives
    LaunchedEffect(cdiData, speedKmh, isRecording) {
      val data = cdiData // Local copy for smart cast
      if (isRecording && data != null) {
        dataLogger.addDataPoint(
          rpm = data.rpm,
          speedKmh = speedKmh,
          timingAngle = data.timingAngle
        )
      }
    }

    Column(
      modifier = Modifier.fillMaxSize()
    ) {
      // Top bar with connection status and settings menu
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Connection status indicator (compact)
        Text(
          text = connectionStatus,
          style = MaterialTheme.typography.bodySmall,
          color = when {
            connectionStatus.contains("Connected") -> MaterialTheme.colorScheme.primary
            connectionStatus.contains("Error") || connectionStatus.contains("failed") -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          }
        )
        
        // Settings menu button
        Box {
          IconButton(onClick = { showConnectionMenu = true }) {
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "Connection Settings"
            )
          }
          
          // Dropdown menu for connection settings
          DropdownMenu(
            expanded = showConnectionMenu,
            onDismissRequest = { showConnectionMenu = false }
          ) {
            // USB Connect option
            DropdownMenuItem(
              text = {
                Text(
                  if (connectionType == ConnectionManager.ConnectionType.USB) "✓ USB (Connected)" else "USB"
                )
              },
              onClick = {
                connectionManager.connectUsb()
                showConnectionMenu = false
              },
              enabled = connectionType != ConnectionManager.ConnectionType.USB
            )
            
            // Bluetooth Connect option
            DropdownMenuItem(
              text = {
                Text(
                  if (connectionType == ConnectionManager.ConnectionType.BLUETOOTH) "✓ Bluetooth (Connected)" else "Bluetooth"
                )
              },
              onClick = {
                if (checkBluetoothPermissions()) {
                  connectionManager.showBluetoothDeviceSelector()
                } else {
                  requestBluetoothPermissions()
                }
                showConnectionMenu = false
              },
              enabled = connectionType != ConnectionManager.ConnectionType.BLUETOOTH
            )
            
            // Disconnect option (only show when connected)
            if (connectionType != ConnectionManager.ConnectionType.NONE) {
              HorizontalDivider()
              DropdownMenuItem(
                text = { Text("Disconnect", color = MaterialTheme.colorScheme.error) },
                onClick = {
                  connectionManager.disconnect()
                  showConnectionMenu = false
                }
              )
            }
          }
        }
      }

      // Tab Row for switching between Gauges, Timing, and Logging
      TabRow(
        selectedTabIndex = selectedTab,
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary
      ) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = { Text("⏱ Gauges") }
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = { Text("💥 Timing") }
        )
        Tab(
          selected = selectedTab == 2,
          onClick = { selectedTab = 2 },
          text = { Text("📈 Logging") }
        )
      }

      // Content area based on selected tab
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
      ) {
        when (selectedTab) {
          0 -> {
            // Gauges Screen with GPS speed
            GaugesScreen(
              cdiData = cdiData,
              speedKmh = speedKmh,
              hasGpsFix = hasGpsFix,
              batterySaverEnabled = batterySaverEnabled,
              onBatterySaverChanged = { enabled ->
                batterySaverEnabled = enabled
                connectionPreferences.setBatterySaverEnabled(enabled)
                if (enabled) {
                  speedProvider.stopLocationUpdates()
                } else if (speedProvider.hasLocationPermission()) {
                  speedProvider.startLocationUpdates()
                } else {
                  requestLocationPermission()
                }
              },
              modifier = Modifier.fillMaxSize()
            )
          }
          1 -> {
            // Timing Curve Screen
            TimingScreen(
              timingMap = timingMap,
              statusMessage = timingMapStatus,
              currentRpm = cdiData?.rpm,
              onRefresh = { connectionManager.refreshTimingMap() },
              onLockWithChanges = { updatedMap ->
                // Write the updated timing map to CDI when user locks the chart (saves changes)
                connectionManager.writeTimingMap(updatedMap)
              },
              modifier = Modifier.fillMaxSize()
            )
          }
          2 -> {
            // Logging Screen with time-series chart
            LoggingScreen(
              dataPoints = logDataPoints,
              isRecording = isRecording,
              onStartRecording = { dataLogger.startRecording() },
              onStopRecording = { dataLogger.stopRecording() },
              onClearData = { dataLogger.clearData() },
              modifier = Modifier.fillMaxSize()
            )
          }
        }
      }
    }
  }

  private fun checkBluetoothPermissions(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.BLUETOOTH_CONNECT
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      // For Android 11 and below, permissions are granted at install time
      true
    }
  }

  private fun requestBluetoothPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      bluetoothPermissionLauncher.launch(
        arrayOf(
          Manifest.permission.BLUETOOTH_CONNECT,
          Manifest.permission.BLUETOOTH_SCAN
        )
      )
    }
  }

  private fun requestLocationPermission() {
    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
  }

  override fun onDestroy() {
    super.onDestroy()
    connectionManager.cleanup()
    speedProvider.cleanup()
  }
}
