package com.tuner.cdituner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

  private var usbService: UsbService? = null
  private var isServiceBound by mutableStateOf(false)
  private var deviceInfo by mutableStateOf<String?>(null)

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val binder = service as UsbService.UsbBinder
      usbService = binder.getService()
      isServiceBound = true
      usbService?.findAndConnect()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      usbService = null
      isServiceBound = false
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleIntent(intent)
    setContent {
      Column(modifier = Modifier.fillMaxSize()) {
        deviceInfo?.let {
          Text(text = "Detected USB device: $it")
        }
        AppContent(usbService)
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
        deviceInfo = "Vendor ID: ${it.vendorId}, Product ID: ${it.productId}"
      }
    }
  }

  override fun onStart() {
    super.onStart()
    Intent(this, UsbService::class.java).also { intent ->
      bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
  }

  override fun onStop() {
    super.onStop()
    if (isServiceBound) {
      unbindService(serviceConnection)
      isServiceBound = false
    }
  }
}

@Composable
fun AppContent(usbService: UsbService?) {
  val connectionStatus by usbService?.connectionStatus?.collectAsState() ?: remember { mutableStateOf("Service not connected") }
  val cdiData by usbService?.receivedData?.collectAsState() ?: remember { mutableStateOf(null) }

  Column(modifier = Modifier.fillMaxSize()) {
    // Status bar at the top
    Text(
      text = "Status: $connectionStatus",
      modifier = Modifier.padding(8.dp),
      style = MaterialTheme.typography.bodyMedium
    )
    // Terminal view takes the rest of the space
    TerminalView(cdiData, modifier = Modifier.weight(1f))
  }
}
