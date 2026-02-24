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

class UsbService : Service() {

  private val binder = UsbBinder()
  private lateinit var usbManager: UsbManager
  private var serialPort: UsbSerialPort? = null
  private var usbDeviceConnection: UsbDeviceConnection? = null

  private val _receivedData = MutableStateFlow<CdiData?>(null)
  val receivedData = _receivedData.asStateFlow()

  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus = _connectionStatus.asStateFlow()

  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)
  private var readingJob: Job? = null

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

    serialPort?.let {
      try {
        it.open(usbDeviceConnection)
        it.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        it.dtr = true
        it.rts = true
        _connectionStatus.value = "Connected, initializing..."
        initializeCdi()
      } catch (e: IOException) {
        _connectionStatus.value = "Error opening port: ${e.message}"
        disconnect()
      }
    }
  }

  private fun initializeCdi() {
    readingJob = scope.launch {
      val initBytes = byteArrayOf(0x01, 0xAB.toByte(), 0xAC.toByte(), 0xA1.toByte())
      try {
        for (i in 1..2) {
          serialPort?.write(initBytes, 500)
          delay(100)
          val response = ByteArray(64)
          val len = serialPort?.read(response, 500) ?: 0
          _connectionStatus.value = "Init #${i}, got ${len} bytes"
        }
        _connectionStatus.value = "Initialized, starting monitor"
        startDataMonitor()
      } catch (e: IOException) {
        _connectionStatus.value = "Error during init: ${e.message}"
        disconnect()
      }
    }
  }

  private fun startDataMonitor() {
    readingJob = scope.launch {
      val request = byteArrayOf(0x01, 0xAB.toByte(), 0xAC.toByte(), 0xA1.toByte())
      var packetCount = 0
      while (isActive) {
        try {
          // Send request
          serialPort?.write(request, 500)
          delay(100)

          // Read response
          val buffer = ByteArray(64)
          val numBytesRead = serialPort?.read(buffer, 500) ?: 0

          // Look for valid 22-byte packet starting with 0x03
          if (numBytesRead >= 22) {
            // Find start of packet (0x03)
            var startIdx = -1
            for (i in 0 until numBytesRead - 21) {
              if (buffer[i] == 0x03.toByte() && buffer[i + 21] == 0xA9.toByte()) {
                startIdx = i
                break
              }
            }

            if (startIdx >= 0) {
              val data = buffer.sliceArray(startIdx until startIdx + 22)
              val decoded = decodeCdiPacket(data)
              if (decoded != null) {
                _receivedData.value = decoded
                packetCount++
                _connectionStatus.value = "Connected - Packets: $packetCount"
              }
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

  private fun decodeCdiPacket(data: ByteArray): CdiData? {
    if (data.size != 22 || data[0] != 0x03.toByte() || data[21] != 0xA9.toByte()) {
      return null
    }

    val rpm = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
    val batteryVoltage = (data[7].toInt() and 0xFF) / 10.0f
    val statusByte = data[8].toInt() and 0xFF
    val timingByte = data[9].toInt() and 0xFF

    return CdiData(rpm, batteryVoltage, statusByte, timingByte)
  }

  private fun disconnect() {
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
    fun getService(): UsbService = this@UsbService
  }

  companion object {
    const val ACTION_USB_PERMISSION = "com.tuner.cdituner.USB_PERMISSION"
  }
}