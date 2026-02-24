package com.tuner.cdituner

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class ConnectionService : Service() {
  companion object {
    private const val TAG = "ConnectionService"
    const val ACTION_USB_PERMISSION = "com.tuner.cdituner.USB_PERMISSION"

    // Bluetooth constants
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val TARGET_DEVICE_NAME = "TA083O6304"
    const val DEVICE_PASSWORD = "5697"

    // CDI Protocol constants
    private val MESSAGE_TO_CDI = byteArrayOf(0x01, 0xAB.toByte(), 0xAC.toByte(), 0xA1.toByte())
    private const val PACKET_START = 0x03.toByte()
    private const val PACKET_END = 0xA9.toByte()
    private const val PACKET_SIZE = 22
  }

  enum class ConnectionType {
    USB, BLUETOOTH, NONE
  }

  enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
  }

  private val binder = ConnectionBinder()
  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)

  // Connection state
  private val _connectionType = MutableStateFlow(ConnectionType.NONE)
  val connectionType: StateFlow<ConnectionType> = _connectionType

  private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
  val connectionState: StateFlow<ConnectionState> = _connectionState

  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus: StateFlow<String> = _connectionStatus

  private val _receivedData = MutableStateFlow<CdiData?>(null)
  val receivedData: StateFlow<CdiData?> = _receivedData

  // USB specific
  private lateinit var usbManager: UsbManager
  private var usbDeviceConnection: UsbDeviceConnection? = null
  private var serialPort: UsbSerialPort? = null

  // Bluetooth specific
  private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
  private var bluetoothSocket: BluetoothSocket? = null
  private var bluetoothInputStream: InputStream? = null
  private var bluetoothOutputStream: OutputStream? = null

  // Common
  private var readingJob: Job? = null
  private var packetCount = 0

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

          if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            device?.let { connectToUsbDevice(it) }
          } else {
            _connectionStatus.value = "USB permission denied"
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

  // ===== USB Connection Methods =====

  fun findAndConnectUsb() {
    disconnect() // Disconnect any existing connection

    val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    if (availableDrivers.isEmpty()) {
      _connectionStatus.value = "No USB serial devices found"
      return
    }

    val driver = availableDrivers[0]
    val device = driver.device
    if (usbManager.hasPermission(device)) {
      connectToUsbDevice(device)
    } else {
      val permissionIntent = PendingIntent.getBroadcast(
        this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
      )
      usbManager.requestPermission(device, permissionIntent)
    }
  }

  private fun connectToUsbDevice(device: UsbDevice) {
    _connectionType.value = ConnectionType.USB
    _connectionState.value = ConnectionState.CONNECTING
    _connectionStatus.value = "Connecting to USB device..."

    usbDeviceConnection = usbManager.openDevice(device)
    if (usbDeviceConnection == null) {
      _connectionStatus.value = "Failed to open USB device - check USB permissions"
      disconnect()
      return
    }

    val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).find {
      it.device.vendorId == device.vendorId && it.device.productId == device.productId
    }

    if (driver == null) {
      _connectionStatus.value = "USB driver not found"
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
        _connectionStatus.value = "USB Connected, initializing..."
        initializeCdi()
      } catch (e: IOException) {
        _connectionStatus.value = "Error opening USB port: ${e.message}"
        disconnect()
      }
    }
  }

  // ===== Bluetooth Connection Methods =====

  fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null

  fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

  @SuppressLint("MissingPermission")
  fun findTargetBluetoothDevice(): BluetoothDevice? {
    if (!hasBluetoothPermissions()) return null

    return bluetoothAdapter?.bondedDevices?.find { device ->
      device.name == TARGET_DEVICE_NAME
    }
  }

  @SuppressLint("MissingPermission")
  fun connectToBluetooth() {
    disconnect() // Disconnect any existing connection

    scope.launch {
      val targetDevice = findTargetBluetoothDevice()
      if (targetDevice != null) {
        connectToBluetoothDevice(targetDevice)
      } else {
        _connectionStatus.value = "Bluetooth device $TARGET_DEVICE_NAME not found"
        _connectionState.value = ConnectionState.ERROR
      }
    }
  }

  @SuppressLint("MissingPermission")
  private suspend fun connectToBluetoothDevice(device: BluetoothDevice) = withContext(Dispatchers.IO) {
    if (!hasBluetoothPermissions()) {
      _connectionState.value = ConnectionState.ERROR
      _connectionStatus.value = "Missing Bluetooth permissions"
      return@withContext
    }

    _connectionType.value = ConnectionType.BLUETOOTH
    _connectionState.value = ConnectionState.CONNECTING
    _connectionStatus.value = "Connecting to ${device.name}..."

    try {
      bluetoothAdapter?.cancelDiscovery()
      bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
      bluetoothSocket?.connect()

      bluetoothInputStream = bluetoothSocket?.inputStream
      bluetoothOutputStream = bluetoothSocket?.outputStream

      _connectionStatus.value = "Bluetooth Connected, initializing..."
      initializeCdi()
    } catch (e: IOException) {
      Log.e(TAG, "Bluetooth connection failed", e)
      _connectionState.value = ConnectionState.ERROR
      _connectionStatus.value = "Bluetooth connection failed: ${e.message}"
      disconnect()
    }
  }

  private fun hasBluetoothPermissions(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.BLUETOOTH_CONNECT
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.BLUETOOTH
      ) == PackageManager.PERMISSION_GRANTED
    }
  }

  // ===== Common CDI Protocol Methods =====

  private fun initializeCdi() {
    readingJob = scope.launch {
      try {
        var response_length = 0

        while (response_length == 0) {
          writeData(MESSAGE_TO_CDI)
          delay(100)

          val response = ByteArray(64)
          response_length = readData(response)
//          _connectionStatus.value = "Init #$i, got $len bytes"
        }

        _connectionState.value = ConnectionState.CONNECTED
        _connectionStatus.value = "Initialized, starting monitor"
        readCdiMessages()
      } catch (e: IOException) {
        _connectionStatus.value = "Error during init: ${e.message}"
        disconnect()
      }
    }
  }

  private fun readCdiMessages() {
    readingJob = scope.launch {
      packetCount = 0
      val buffer = ByteArray(256)
      var bufferPos = 0

      while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
        try {
          // Send request
          writeData(MESSAGE_TO_CDI)
          delay(100)

          // Read response
          val tempBuffer = ByteArray(64)
          val numBytesRead = readData(tempBuffer)

          if (numBytesRead > 0) {
            // Add to buffer
            System.arraycopy(tempBuffer, 0, buffer, bufferPos, numBytesRead)
            bufferPos += numBytesRead

            // Look for valid packet
            if (bufferPos >= PACKET_SIZE) {
              var startIdx = -1
              for (i in 0 until bufferPos - PACKET_SIZE + 1) {
                if (buffer[i] == PACKET_START && buffer[i + PACKET_SIZE - 1] == PACKET_END) {
                  startIdx = i
                  break
                }
              }

              if (startIdx >= 0) {
                val data = buffer.sliceArray(startIdx until startIdx + PACKET_SIZE)
                val decoded = decodeCdiPacket(data)
                if (decoded != null) {
                  _receivedData.value = decoded
                  packetCount++
                  val connectionTypeStr = when (_connectionType.value) {
                    ConnectionType.USB -> "USB"
                    ConnectionType.BLUETOOTH -> "BT"
                    else -> ""
                  }
                  _connectionStatus.value = "$connectionTypeStr Connected - Packets: $packetCount"
                }

                // Remove processed data from buffer
                val remaining = bufferPos - (startIdx + PACKET_SIZE)
                if (remaining > 0) {
                  System.arraycopy(buffer, startIdx + PACKET_SIZE, buffer, 0, remaining)
                }
                bufferPos = remaining
              } else if (bufferPos > 100) {
                // Buffer overflow protection
                bufferPos = 0
              }
            }
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
    if (data.size != PACKET_SIZE || data[0] != PACKET_START || data[PACKET_SIZE - 1] != PACKET_END) {
      return null
    }

    val rpm = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
    val batteryVoltage = (data[7].toInt() and 0xFF) / 10.0f
    val statusByte = data[8].toInt() and 0xFF
    val timingByte = data[9].toInt() and 0xFF

    return CdiData(rpm, batteryVoltage, statusByte, timingByte)
  }

  private fun writeData(data: ByteArray) {
    when (_connectionType.value) {
      ConnectionType.USB -> serialPort?.write(data, 500)
      ConnectionType.BLUETOOTH -> {
        bluetoothOutputStream?.write(data)
        bluetoothOutputStream?.flush()
      }
      else -> throw IOException("No active connection")
    }
  }

  private fun readData(buffer: ByteArray): Int {
    return when (_connectionType.value) {
      ConnectionType.USB -> serialPort?.read(buffer, 500) ?: 0
      ConnectionType.BLUETOOTH -> bluetoothInputStream?.read(buffer) ?: 0
      else -> 0
    }
  }

  fun disconnect() {
    readingJob?.cancel()
    readingJob = null

    // Close USB connection
    serialPort?.let {
      try {
        it.close()
      } catch (e: IOException) { /* Ignore */ }
    }
    usbDeviceConnection?.close()
    serialPort = null
    usbDeviceConnection = null

    // Close Bluetooth connection
    try {
      bluetoothInputStream?.close()
      bluetoothOutputStream?.close()
      bluetoothSocket?.close()
    } catch (e: IOException) { /* Ignore */ }
    bluetoothInputStream = null
    bluetoothOutputStream = null
    bluetoothSocket = null

    // Reset state
    _connectionType.value = ConnectionType.NONE
    _connectionState.value = ConnectionState.DISCONNECTED
    _connectionStatus.value = "Disconnected"
    _receivedData.value = null
  }

  inner class ConnectionBinder : Binder() {
    fun getService(): ConnectionService = this@ConnectionService
  }
}