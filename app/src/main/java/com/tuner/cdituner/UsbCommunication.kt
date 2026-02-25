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

class UsbCommunication : Service() {

  private val binder = UsbBinder()
  private lateinit var usbManager: UsbManager
  private var serialPort: UsbSerialPort? = null
  private var usbDeviceConnection: UsbDeviceConnection? = null
  private var cdiProtocol: CdiProtocol? = null
  private var usbIoHandler: UsbIoHandler? = null

  // Use CdiProtocol's data flow, but keep our own connection status for USB-specific messages
  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus = _connectionStatus.asStateFlow()
  
  // Expose the CdiProtocol's receivedData flow
  val receivedData get() = cdiProtocol?.receivedData ?: MutableStateFlow<CdiDataDisplay?>(null).asStateFlow()

  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)

  private val usbReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (ACTION_USB_PERMISSION == intent.action) {
        synchronized(this) {
          val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
          if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            device?.apply { connectToDevice(this) }
          } else {
            _connectionStatus.value = "Permission denied for device $device"
          }
        }
      }
    }
  }

  /**
   * USB-specific implementation of CdiIoHandler
   */
  private inner class UsbIoHandler(private val port: UsbSerialPort) : CdiIoHandler {
    override suspend fun write(data: ByteArray) {
      withContext(Dispatchers.IO) {
        port.write(data, 500)
      }
    }

    override suspend fun read(buffer: ByteArray, timeout: Int): Int {
      return withContext(Dispatchers.IO) {
        port.read(buffer, timeout) ?: 0
      }
    }

    override fun isConnected(): Boolean {
      return serialPort != null && usbDeviceConnection != null
    }

    override fun close() {
      try {
        serialPort?.close()
      } catch (e: IOException) { /* Ignore */ }
      usbDeviceConnection?.close()
      serialPort = null
      usbDeviceConnection = null
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

  fun findAndConnect() {
    val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    if (availableDrivers.isEmpty()) {
      _connectionStatus.value = "No serial devices found"
      return
    }

    val driver = availableDrivers[0]
    val device = driver.device
    if (usbManager.hasPermission(device)) {
      connectToDevice(device)
    } else {
      val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
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

    serialPort?.let { port ->
      try {
        port.open(usbDeviceConnection)
        port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.dtr = true
        port.rts = true
        
        // Create IO handler and CdiProtocol
        usbIoHandler = UsbIoHandler(port)
        cdiProtocol = CdiProtocol(usbIoHandler!!, scope)
        
        // Subscribe to CdiProtocol's connection status
        scope.launch {
          cdiProtocol?.connectionStatus?.collect { status ->
            _connectionStatus.value = status
          }
        }
        
        _connectionStatus.value = "Connected, initializing..."
        
        // Start CDI initialization
        scope.launch {
          cdiProtocol?.initializeCdi()
        }
      } catch (e: IOException) {
        _connectionStatus.value = "Error opening port: ${e.message}"
        disconnect()
      }
    }
  }

  private fun disconnect() {
    cdiProtocol?.disconnect()
    cdiProtocol = null
    usbIoHandler = null
    _connectionStatus.value = "Disconnected"
  }

  inner class UsbBinder : Binder() {
    fun getService(): UsbCommunication = this@UsbCommunication
  }

  companion object {
    const val ACTION_USB_PERMISSION = "com.tuner.cdituner.USB_PERMISSION"
  }
}