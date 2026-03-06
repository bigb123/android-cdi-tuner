package com.tuner.cdituner

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class UsbConnection : Service() {

  private val binder = UsbBinder()
  private lateinit var usbManager: UsbManager
  private var serialPort: UsbSerialPort? = null
  private var usbDeviceConnection: UsbDeviceConnection? = null

  private val _receivedData = MutableStateFlow<CdiReceivedMessageDecoder?>(null)
  val receivedData = _receivedData.asStateFlow()

  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus = _connectionStatus.asStateFlow()

  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)
  private var readingJob: Job? = null

  // Callback to resume coroutine when permission result arrives
  private var permissionContinuation: ((Boolean) -> Unit)? = null

  private val usbReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (ACTION_USB_PERMISSION == intent.action) {
        synchronized(this) {
          val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
          } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
          }
          val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
          
          // Resume the waiting coroutine with the result
          permissionContinuation?.invoke(granted)
          permissionContinuation = null
          
          if (granted) {
            device?.apply { connectToDevice(this) }
          } else {
            _connectionStatus.value = "Permission denied for device $device"
          }
        }
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
    val filter = IntentFilter(ACTION_USB_PERMISSION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("UnspecifiedRegisterReceiverFlag")
      registerReceiver(usbReceiver, filter)
    }
  }

  override fun onBind(intent: Intent): IBinder = binder

  override fun onDestroy() {
    super.onDestroy()
    disconnect()
    unregisterReceiver(usbReceiver)
    job.cancel()
  }

  /**
   * Finds and connects to a USB serial device.
   * This is a suspend function that will WAIT for user permission if needed.
   *
   * @return 0 = success (connected or permission granted),
   *         1 = no devices found
   */
  suspend fun findAndConnect(): Int {
    val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    if (availableDrivers.isEmpty()) {
      _connectionStatus.value = "No serial devices found. Retrying in 1 second"
      return 1
    }

    val driver = availableDrivers[0]
    val device = driver.device

    if ( ! usbManager.hasPermission(device)) {
      // Request permission and WAIT for user response
      requestPermissionAndWait(device)
    }

    // We evaluate permissions again after request. If user approved USB access let's connect
    if (usbManager.hasPermission(device)) {
      connectToDevice(device)
    } else {
      _connectionStatus.value = "Permission not granted"
    }
    return 0
  }

  /**
   * Suspends until user grants or denies USB permission.
   * This "freezes" the calling coroutine (not the UI) until user responds.
   */
  private suspend fun requestPermissionAndWait(device: UsbDevice): Boolean {
    return suspendCoroutine { continuation ->
      // Store the continuation callback
      permissionContinuation = { granted ->
        continuation.resume(granted)
      }
      
      // Request permission - this shows the dialog
      val permissionIntent = PendingIntent.getBroadcast(
        this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
      )
      usbManager.requestPermission(device, permissionIntent)
    }
  }

  private fun connectToDevice(device: UsbDevice) {
    usbDeviceConnection = usbManager.openDevice(device)
    val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).find {
      it.device.vendorId == device.vendorId && it.device.productId == device.productId
    }

    if (driver == null) {
      _connectionStatus.value = "Driver not found"
      disconnect()
      return
    }
    serialPort = driver.ports[0]

    serialPort?.let {
      try {
        it.open(usbDeviceConnection)
        it.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        it.dtr = true
        it.rts = true
        _connectionStatus.value = "Connected, initializing..."
        startDataMonitor()
      } catch (e: IOException) {
        _connectionStatus.value = "Error opening port: ${e.message}"
        disconnect()
      }
    }
  }

  private fun startDataMonitor() {
    readingJob = scope.launch {
      var packetCount = 0
      while (isActive) {
        try {
          serialPort?.write(CdiMessageProcessing.CDI_MESSAGE, 500)
          delay(100)

          // Read response
          val buffer = ByteArray(64)
          val numBytesRead = serialPort?.read(buffer, 500) ?: 0

          if (numBytesRead >= 22) {
            var startIdx = CdiMessageProcessing.extractMessageFromBytes(numBytesRead, buffer)

            if (startIdx >= 0) {
              packetCount = CdiMessageProcessing.processMessage(buffer, startIdx, packetCount, _receivedData, _connectionStatus)
            }
          } else if (numBytesRead > 0) {
            _connectionStatus.value = "Connected - Partial data: $numBytesRead bytes"
          }

          delay(100)
        } catch (e: IOException) {
          _connectionStatus.value = "Connection lost: ${e.message}"
          disconnect()
          break
        }
      }
    }
  }

  fun disconnect() {
    readingJob?.cancel()
    readingJob = null
    serialPort?.let {
      try {
        it.close()
      } catch (e: IOException) { /* Ignore */ }
    }
    usbDeviceConnection?.close()
    serialPort = null
    usbDeviceConnection = null
    _connectionStatus.value = "Disconnected"
  }

  inner class UsbBinder : Binder() {
    fun getService(): UsbConnection = this@UsbConnection
  }

  companion object {
    const val ACTION_USB_PERMISSION = "com.tuner.cdituner.USB_PERMISSION"
  }
}