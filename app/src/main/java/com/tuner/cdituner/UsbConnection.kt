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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import android.util.Log
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

  private val _timingMap = MutableStateFlow<List<TimingPoint>?>(null)
  val timingMap = _timingMap.asStateFlow()

  private val _timingMapStatus = MutableStateFlow<String?>(null)
  val timingMapStatus = _timingMapStatus.asStateFlow()

  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)
  private var readingJob: Job? = null
  
  // Flag to pause CDI communication without killing the connection
  @Volatile
  private var pauseCdiCommunication = false
  
  // Mutex to prevent parallel sendMessage calls
  private val sendMessageMutex = Mutex()

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
      try {
        while (isActive) {

          delay(100)

          // Skip sending/reading when paused for timing map operations
          if (pauseCdiCommunication) {
            delay(1000)
            continue
          }
          // below code with sendMessage() function usage didn't work. Worth to revisit another time
//          else {
//            var response = sendMessage(CdiMessageProcessing.CDI_MESSAGE,CdiTimingMapProtocol.STATUS_PAGE_SIZE)
//            while (response[0] != 0x03.toByte()) {
//              response = sendMessage(CdiMessageProcessing.CDI_MESSAGE,CdiTimingMapProtocol.STATUS_PAGE_SIZE)
//            }
//            packetCount = CdiMessageProcessing.processMessage(response, 0, packetCount, _receivedData, _connectionStatus)
//          }

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
        }
      } catch (e: IOException) {
        _connectionStatus.value = "Connection lost: ${e.message}"
        disconnect()
      }
    }
  }

  /**
   * Reads the ignition timing map from CDI.
   * This pauses the normal data monitoring during the read operation.
   * 
   * Protocol:
   * 1. Send initial request
   * 2. Read page 0, send acknowledgment
   * 3. Read page 1, send acknowledgment
   * 4. Parse and return timing map data
   */
  fun readTimingMap() {
    scope.launch {
      // Avoid sending new messages if writing operation is still ongoing
      while (pauseCdiCommunication) {
        delay(100)
        continue
      }
      // Pause normal data monitoring (keeps connection alive)
      pauseCdiCommunication = true
      Log.d("UsbConnection", "Reading a timing map")
      
      // Clear cached timing map to ensure StateFlow emits the new value
      // (StateFlow uses structural equality, so identical data wouldn't be re-emitted)
      _timingMap.value = null
      _timingMapStatus.value = "Reading timing map..."

      try {
        val timingMapBytes = ByteArray(CdiTimingMapProtocol.USEFUL_DATA_SIZE * CdiTimingMapProtocol.PAGES_TO_READ)

        var requestMessage = CdiTimingMapProtocol.READ_TIMING_MAP_REQUEST // Request message content will get updated in the loop below
        
        // Read pages
        for (pageNum in 0 until CdiTimingMapProtocol.PAGES_TO_READ) {

          _timingMapStatus.value = "Reading page ${pageNum + 1}/${CdiTimingMapProtocol.PAGES_TO_READ}..."
          Log.d("UsbConnection", "Reading page ${pageNum + 1}/${CdiTimingMapProtocol.PAGES_TO_READ}...")


          // Read page response
          // Retry send request for ignition table if received message doesn't match the pattern
          var pageBuffer = sendMessage(requestMessage)
          while (pageBuffer[0] != 0x02.toByte() || pageBuffer[1] != 0x07.toByte()) {
            Log.d("UsbConnection", "Wrong response: ${pageBuffer.joinToString(" ") { "%02X".format(it) }}")
            pageBuffer = sendMessage(requestMessage)
          }

          Log.d("UsbConnection", "This reading should be correct. pageBuffer: ${pageBuffer.joinToString(" ") { "%02X".format(it) }}")
          // Load page without header (first 4 bytes) and footer (last 2 bytes) to timing map array
          System.arraycopy(pageBuffer, 4, timingMapBytes, pageNum * CdiTimingMapProtocol.USEFUL_DATA_SIZE, CdiTimingMapProtocol.USEFUL_DATA_SIZE)

          // Set a message request to retrieve next page
          requestMessage = CdiTimingMapProtocol.createAcknowledgeMessage(pageBuffer)
          Log.d("UsbConnection", "Pages content so far: ${timingMapBytes.joinToString(" ") { "%02X".format(it) }}")
        }

        // Parse the timing map
        val timingMap = CdiTimingMapProtocol.parseTimingMap(timingMapBytes)
        if (timingMap != null) {
          _timingMap.value = timingMap
          _timingMapStatus.value = "Timing map loaded (${timingMap.size} points)"
        } else {
          _timingMapStatus.value = "Failed to parse timing map"
        }
        
      } catch (e: IOException) {
        _timingMapStatus.value = "Error reading timing map: ${e.message}"
      } finally {
        // Resume normal CDI communication
        pauseCdiCommunication = false
      }
    }
  }

  /**
   * Writes the ignition timing map to CDI.
   * This pauses the normal data monitoring during the write operation.
   *
   * Protocol:
   * 1. Send write init message
   * 2. Wait for CDI ready response
   * 3. Send page 0, wait for echo
   * 4. Send page 1, wait for echo
   * 5. Send end of transmission, wait for confirmation
   *
   * @param timingMap List of 16 TimingPoints to write
   */
  fun writeTimingMap(timingMap: List<TimingPoint>) {
    scope.launch {
      // Avoid sending new messages if reading operation is still ongoing
      while (pauseCdiCommunication) {
        delay(100)
        continue
      }
      // Pause normal data monitoring (keeps connection alive)
      pauseCdiCommunication = true
      
      _timingMapStatus.value = "Writing timing map..."

      try {
        // Step 1: Send write init message
        _timingMapStatus.value = "Initializing write..."
        var response = sendMessage(CdiTimingMapProtocol.WRITE_TIMING_MAP_REQUEST) // we should verify response here
        
        // Step 2: Convert timing map to page data
        val (page0Data, page1Data) = CdiTimingMapProtocol.timingMapToPageData(timingMap)
        
        // Step 3: Send page 0
        _timingMapStatus.value = "Writing page 1/2..."
        sendMessage(CdiTimingMapProtocol.createPageWriteMessage(0, page0Data))

        // Step 4: Send page 1
        _timingMapStatus.value = "Writing page 2/2..."
        sendMessage(CdiTimingMapProtocol.createPageWriteMessage(1, page1Data))
        
        // Step 5: Send end of transmission
        _timingMapStatus.value = "Saving to CDI..."
        sendMessage(CdiTimingMapProtocol.END_OF_TRANSMISSION)
        sendMessage(CdiTimingMapProtocol.COMPATIBILITY_MESSAGE)
        sendMessage(CdiTimingMapProtocol.END_OF_TRANSMISSION_COMPATIBILITY_MESSAGE)
        
        // Success!
        _timingMap.value = timingMap
        _timingMapStatus.value = "Timing map saved successfully!"
        
      } catch (e: IOException) {
        _timingMapStatus.value = "Error writing timing map: ${e.message}"
      } finally {
        // Resume normal CDI communication
        pauseCdiCommunication = false
      }
    }
  }



  private suspend fun sendMessage(message: ByteArray, responseSize: Int = CdiTimingMapProtocol.TIMING_PAGE_SIZE): ByteArray {
    // Mutex ensures only one sendMessage can run at a time
    // This prevents message/response mismatch when multiple coroutines try to communicate
    return sendMessageMutex.withLock {
      // Send message and read a response
      serialPort?.write(message, 1000)
      Log.d("UsbConnection", "Sent a message: ${message.joinToString(" ") { "%02X".format(it) }}")

      // Wait for CDI ready response
      delay(100)

      val response = readFullPage(responseSize)
      Log.d("UsbConnection", "CDI response: ${response.joinToString(" ") { "%02X".format(it) }}")

      response
    }
  }

  /**
   * Reads a full 64-byte page from the serial port.
   * Handles partial reads by retrying until complete or timeout.
   */
  private suspend fun readFullPage(responseSize: Int): ByteArray {
    val pageBuffer = ByteArray(responseSize)
    var totalBytesRead = 0
    var attempts = 0
    val maxAttempts = 10
    
    while (totalBytesRead < responseSize && attempts < maxAttempts) {
      val chunk = ByteArray(responseSize)
      val bytesRead = serialPort?.read(chunk, 500) ?: 0
      
      if (bytesRead > 0) {
        Log.d("UsbConnection", "Response bytes so far after sending a message: ${chunk.joinToString(" ") { "%02X".format(it) }}")
        Log.d("UsbConnection", "Number of response bytes so far: $bytesRead")
        try {
          System.arraycopy(chunk, 0, pageBuffer, totalBytesRead, bytesRead)
        } catch (e: ArrayIndexOutOfBoundsException) { // If the response doesn't fit in 64 bytes then it's incorrect and we have to retry
          Log.d("UsbConnection", "Incorrect response. Bytes received so far: ${pageBuffer.joinToString(" ") { "%02X".format(it) }}")
          Log.d("UsbConnection", "Incorrect response. Bytes received in the last (failing) loop: ${chunk.joinToString(" ") { "%02X".format(it) }}")
          _timingMapStatus.value = "CDI not ready to accept timing map. Retrying"
          return ByteArray(0)
        }
        totalBytesRead += bytesRead
      }
      
      attempts++
      if (totalBytesRead < responseSize) {
        delay(100)
      }
    }
    
    return pageBuffer
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