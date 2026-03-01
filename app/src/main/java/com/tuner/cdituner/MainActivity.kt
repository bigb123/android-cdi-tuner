package com.tuner.cdituner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

  private lateinit var connectionManager: ConnectionManager
  private var deviceInfo by mutableStateOf<String?>(null)

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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Initialize ConnectionManager
    connectionManager = ConnectionManager(this)

    handleIntent(intent)

    setContent {
      MaterialTheme {
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

  private fun handleIntent(intent: Intent) {
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
      }
    }
  }

  @Composable
  fun MainScreen() {
    val connectionType by connectionManager.connectionType.collectAsState()
    val connectionStatus by connectionManager.connectionStatus.collectAsState()
    val cdiData by connectionManager.receivedData.collectAsState()

    Column(
      modifier = Modifier.fillMaxSize()
    ) {
      // Connection Controls
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
      ) {
        Column(
          modifier = Modifier.padding(16.dp)
        ) {
          Text(
            text = "Connection Settings",
            style = MaterialTheme.typography.headlineSmall
          )

          Spacer(modifier = Modifier.height(8.dp))

          // Connection status
          Text(
            text = "Status: $connectionStatus",
            style = MaterialTheme.typography.bodyMedium,
            color = when {
              connectionStatus.contains("Connected") -> MaterialTheme.colorScheme.primary
              connectionStatus.contains("Error") || connectionStatus.contains("failed") -> MaterialTheme.colorScheme.error
              else -> MaterialTheme.colorScheme.onSurface
            }
          )

          deviceInfo?.let {
            Text(
              text = it,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          Spacer(modifier = Modifier.height(16.dp))

          // Connection buttons
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            // USB Connect Button
            Button(
              onClick = { connectionManager.connectUsb() },
              modifier = Modifier.weight(1f),
              enabled = connectionType != ConnectionManager.ConnectionType.USB
            ) {
              Text("USB")
            }

            // Bluetooth Connect Button
            Button(
              onClick = {
                if (checkBluetoothPermissions()) {
                  connectionManager.showBluetoothDeviceSelector()
                } else {
                  requestBluetoothPermissions()
                }
              },
              modifier = Modifier.weight(1f),
              enabled = connectionType != ConnectionManager.ConnectionType.BLUETOOTH
            ) {
              Text("Bluetooth")
            }

            // Disconnect Button
            if (connectionType != ConnectionManager.ConnectionType.NONE) {
              Button(
                onClick = { connectionManager.disconnect() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.error
                )
              ) {
                Text("Disconnect")
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // CDI Data Display - Full width without card
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
      ) {
        // Terminal View - Full width
        if (cdiData != null) {
          TerminalView(
            cdiReceivedMessageDecoder = cdiData,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = when (connectionType) {
                ConnectionManager.ConnectionType.NONE -> "Not connected"
                else -> "Waiting for data..."
              },
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant
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

  override fun onDestroy() {
    super.onDestroy()
    connectionManager.cleanup()
  }
}
