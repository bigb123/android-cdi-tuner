/*
  - Bluetooth connection is much more fragile than USB
  - It will close conneciton if it is flooded with messages
  - a rule of thumb is to wait for a response for 100 ms and retry reading if the message didn't appear correctly
  - it's also a good idea to retry reading if message arrived incomplete (without end marker on last byte)
 */

package com.tuner.cdituner

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class BluetoothConnection : Service() {

  private val binder = BluetoothBinder()
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothSocket: BluetoothSocket? = null
  private var inputStream: InputStream? = null
  private var outputStream: OutputStream? = null
  private var deviceAddress: String? = null

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

  companion object {
    private const val RECONNECT_DELAY_MS = 1000L
  }

  // Standard SPP UUID for Serial Port Profile
  private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

  override fun onCreate() {
    super.onCreate()
    bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    if (bluetoothAdapter == null) {
      _connectionStatus.value = "Bluetooth not supported"
    }
  }

  override fun onBind(intent: Intent): IBinder = binder

  override fun onDestroy() {
    super.onDestroy()
    disconnect()
    job.cancel()
  }

  /**
   * Stores the device address for reconnection.
   * Call startDataMonitor() after this to begin the resilient loop.
   */
  fun connectToDevice(deviceAddress: String) {
    this.deviceAddress = deviceAddress
  }

  /**
   * Opens a Bluetooth socket to the stored device address.
   * Returns true if the connection was established successfully.
   */
  @SuppressLint("MissingPermission")
  private fun openConnection(): Boolean {
    val address = deviceAddress ?: return false
    if (bluetoothAdapter == null) {
      _connectionStatus.value = "Bluetooth not available"
      return false
    }

    try {
      val device: BluetoothDevice = bluetoothAdapter!!.getRemoteDevice(address)
      bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
      bluetoothAdapter?.cancelDiscovery()
      bluetoothSocket?.connect()
      inputStream = bluetoothSocket?.inputStream
      outputStream = bluetoothSocket?.outputStream
      return true
    } catch (e: IOException) {
      closeSocket()
      return false
    } catch (e: SecurityException) {
      _connectionStatus.value = "Permission denied"
      closeSocket()
      return false
    }
  }

  /** Closes the socket and streams without touching readingJob or status. */
  private fun closeSocket() {
    try {
      outputStream?.close()
      inputStream?.close()
      bluetoothSocket?.close()
    } catch (_: IOException) { }
    outputStream = null
    inputStream = null
    bluetoothSocket = null
  }

  /**
   * Resilient CDI communication loop.
   * Connects, reads packets, and automatically reconnects on failure.
   * Keeps retrying until the job is cancelled (via disconnect()).
   */
  fun startDataMonitor() {
    readingJob?.cancel()
    readingJob = scope.launch {
      var packetCount = 0

      // Outer loop: keeps reconnecting when connection drops
      while (isActive) {
        _connectionStatus.value = "Connecting..."

        if (!openConnection()) {
          _connectionStatus.value = "Connection failed. Retrying in ${RECONNECT_DELAY_MS / 1000}s..."
          delay(RECONNECT_DELAY_MS)
          continue
        }

        _connectionStatus.value = "Connected, starting CDI communication..."

        val buffer = ByteArray(256)
        var bufferPos = 0

        // Inner loop: reads CDI packets while connected
        try {
          while (isActive) {

            delay(100)

            // Skip sending/reading when paused for timing map operations
            if (pauseCdiCommunication) {
              delay(RECONNECT_DELAY_MS)
              continue
            }
            else {
//              delay(100)
              packetCount = CdiMessageProcessing.processMessage(sendMessage(CdiMessageProcessing.CDI_MESSAGE, CdiTimingMapProtocol.STATUS_PAGE_SIZE), 0, packetCount, _receivedData, _connectionStatus)
            }

//            outputStream?.write(CdiMessageProcessing.CDI_MESSAGE)
//            outputStream?.flush()

//            val available = inputStream?.available() ?: 0
//            if (available > 0) {
//              val bytesToRead = minOf(available, buffer.size - bufferPos)
//              val numBytesRead = inputStream?.read(buffer, bufferPos, bytesToRead) ?: 0
//              bufferPos += numBytesRead
//
//              if (bufferPos >= 22) {
//                var startIdx = CdiMessageProcessing.extractMessageFromBytes(bufferPos, buffer)
//
//                if (startIdx >= 0) {
//                  packetCount = CdiMessageProcessing.processMessage(buffer, startIdx, packetCount, _receivedData, _connectionStatus)
//                  val remaining = bufferPos - (startIdx + 22)
//                  if (remaining > 0) {
//                    System.arraycopy(buffer, startIdx + 22, buffer, 0, remaining)
//                  }
//                  bufferPos = remaining
//                } else if (bufferPos > 128) {
//                  System.arraycopy(buffer, bufferPos - 64, buffer, 0, 64)
//                  bufferPos = 64
//                }
//              }
//            }
          }
        } catch (e: IOException) {
          // Connection lost — close socket and let the outer loop reconnect
          _connectionStatus.value = "Connection lost. Waiting for device..."
//          closeSocket()
          delay(RECONNECT_DELAY_MS)
        }
      }
    }
  }

  fun disconnect() {
    readingJob?.cancel()
    readingJob = null
    deviceAddress = null
    closeSocket()
    _connectionStatus.value = "Disconnected"
  }

  @SuppressLint("MissingPermission")
  fun getPairedDevices(): List<BluetoothDevice> {
    return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
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
      // Pause normal data monitoring (keeps connection alive)
      pauseCdiCommunication = true
      
      // Clear cached timing map to ensure StateFlow emits the new value
      // (StateFlow uses structural equality, so identical data wouldn't be re-emitted)
      _timingMap.value = null
      _timingMapStatus.value = "Reading timing map..."

      try {
          val timingMapBytes = ByteArray(CdiTimingMapProtocol.USEFUL_DATA_SIZE * CdiTimingMapProtocol.PAGES_TO_READ)

          var requestMessage = CdiTimingMapProtocol.READ_TIMING_MAP_REQUEST // Message content will get updated in the loop
          
          // Read pages
          for (pageNum in 0 until CdiTimingMapProtocol.PAGES_TO_READ) {

            _timingMapStatus.value = "Reading page ${pageNum + 1}/${CdiTimingMapProtocol.PAGES_TO_READ}..."


            // Send read timing map request
//            outputStream?.write(requestMessage)
//            outputStream?.flush()
            var pageBuffer = sendMessage(requestMessage)

            // Read page response
//            val pageBuffer = ByteArray(CdiTimingMapProtocol.TIMING_PAGE_SIZE)
//            var totalNumberOfReadBytes = readBytesWithTimeout(pageBuffer, 500)
//            var attempts = 0
//            val maxAttempts = 10
//
            // First let's try to receive a message in a proper format.
            // Retry send request for ignition table if received message doesn't match the read response pattern
//            while (pageBuffer[0] != 0x02.toByte() || pageBuffer[1] != 0x07.toByte()) {
//              pageBuffer = sendMessage(requestMessage)
////              // Request new reading
////              outputStream?.write(requestMessage)
////              outputStream?.flush()
////              // in the meantime print last reading
////              Log.d("BluetoothConnection", "Incorrect response. pageBuffer: ${pageBuffer.joinToString(" ") { "%02X".format(it) }}")
////              // Retry reading
////              totalNumberOfReadBytes = readBytesWithTimeout(pageBuffer, 500)
////              Log.d("BluetoothConnection", "Bytes read: $totalNumberOfReadBytes")
//            }
//            Log.d("BluetoothConnection", "response after first read: ${pageBuffer.joinToString(" ") { "%02X".format(it) }}")
//            Log.d("BluetoothConnection", "Bytes read: $totalNumberOfReadBytes")
//
//            // Page may arrive incomplete (less than 64 bytes). Retrieve the rest of the message by retrying reading without sending any new request messages to CDI.
//            // We are reading an entire message chunk by chunk. Size of chunk is in 'tempNumberOfBytesRead' in bytes
//            while (totalNumberOfReadBytes < CdiTimingMapProtocol.TIMING_PAGE_SIZE && attempts < maxAttempts) {
//              Log.d("BluetoothConnection", "Incomplete response. Retrieving the rest of the message.")
//              val chunkContent = ByteArray(CdiTimingMapProtocol.TIMING_PAGE_SIZE)
//
//              // Read new chunk of data (in bytes)
//              val chunkSize = readBytesWithTimeout(chunkContent, 500)
//              Log.d("BluetoothConnection", "Read result: ${chunkContent.joinToString(" ") { "%02X".format(it) }}")
//              Log.d("BluetoothConnection", "Number of bytes read in this loop: $chunkSize")
//
//              // Put newly read bytes into the large array
//              System.arraycopy(chunkContent, 0, pageBuffer, totalNumberOfReadBytes, chunkSize)
//
//              // New bytes arrived so we update total number of bytes read
//              totalNumberOfReadBytes += chunkSize
//              Log.d("BluetoothConnection", "Number of bytes read so far: $totalNumberOfReadBytes")
//              Log.d("BluetoothConnection", "Message so far: ${pageBuffer.joinToString(" ") { "%02X".format(it) }}")
//
//              attempts++
//            }

            Log.d("BluetoothConnection", "This reading should be correct. pageBuffer: ${pageBuffer.joinToString(" ") { "%02X".format(it) }}")
            // Load page without header (first 4 bytes) and footer (last 2 bytes) to timing map array
            System.arraycopy(pageBuffer, 4, timingMapBytes, pageNum * CdiTimingMapProtocol.USEFUL_DATA_SIZE, CdiTimingMapProtocol.USEFUL_DATA_SIZE)

            // Set a message request to retrieve next page
            requestMessage = CdiTimingMapProtocol.createAcknowledgeMessage(pageBuffer)
            Log.d("BluetoothConnection", "Pages content so far: ${timingMapBytes.joinToString(" ") { "%02X".format(it) }}")
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
      // Pause normal data monitoring (keeps connection alive)
      pauseCdiCommunication = true
      
      _timingMapStatus.value = "Writing timing map..."

      try {
        // Step 1: Send write init message
        _timingMapStatus.value = "Initializing write..."
        Log.d("BluetoothConnection", "Sending an init write message")
        var response = sendMessage(CdiTimingMapProtocol.WRITE_TIMING_MAP_REQUEST)
//        while (response[0] != 0x02.toByte() || response[1] != 0x01.toByte()) {
//          Log.d("BluetoothConnection", "Init write message - bad response. Retrying")
//          response = sendMessage(CdiTimingMapProtocol.WRITE_TIMING_MAP_REQUEST, CdiTimingMapProtocol.TIMING_PAGE_SIZE)
//        }
        
        // Step 2: Convert timing map to page data
        val (page0Data, page1Data) = CdiTimingMapProtocol.timingMapToPageData(timingMap)
        
        // Step 3: Send page 0
        _timingMapStatus.value = "Writing page 1/2..."
        Log.d("BluetoothConnection", "Sending first page of timing map")
        response = sendMessage(CdiTimingMapProtocol.createPageWriteMessage(0, page0Data))
//        while (response[0] != 0x02.toByte() || response[1] != 0x02.toByte()) {
//          Log.d("BluetoothConnection", "First page of timing map - bad response. Retrying")
//          response = sendMessage(CdiTimingMapProtocol.createPageWriteMessage(0, page0Data), CdiTimingMapProtocol.TIMING_PAGE_SIZE)
//        }

        // Step 4: Send page 1
        _timingMapStatus.value = "Writing page 2/2..."
        Log.d("BluetoothConnection", "Sending a second page of timing map")
        response = sendMessage(CdiTimingMapProtocol.createPageWriteMessage(1, page1Data))
//        while (response[0] != 0x02.toByte() || response[1] != 0x02.toByte()) {
//          Log.d("BluetoothConnection", "Second page of timing map - bad response. Retrying")
//          response = sendMessage(CdiTimingMapProtocol.createPageWriteMessage(1, page1Data), CdiTimingMapProtocol.TIMING_PAGE_SIZE)
//        }
        
        // Step 5: Send end of transmission
        _timingMapStatus.value = "Saving to CDI..."
        Log.d("BluetoothConnection", "Sending an end of transmission")
        response = sendMessage(CdiTimingMapProtocol.END_OF_TRANSMISSION)
//        while (response[0] != 0x02.toByte() || response[1] != 0x03.toByte()) {
//          Log.d("BluetoothConnection", "Sending timing map termination - bad response. Retrying")
//          response = sendMessage(CdiTimingMapProtocol.END_OF_TRANSMISSION, CdiTimingMapProtocol.TIMING_PAGE_SIZE)
//        }

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

    // Send message
    outputStream?.write(message)
    outputStream?.flush()
    Log.d("BluetoothConnection", "Sent a message: ${message.joinToString(" ") { "%02X".format(it) }}")

    // Wait for CDI to catch up
    delay(100)

    // read a response
    val response = readFullPage(responseSize)
    Log.d("BluetoothConnection", "CDI response: ${response.joinToString(" ") { "%02X".format(it) }}")

    return response
  }

  /**
   * Reads a full 64-byte page from the Bluetooth input stream.
   * Handles partial reads by retrying until complete or timeout.
   */
  private suspend fun readFullPage(responseSize: Int): ByteArray {
    val pageBuffer = ByteArray(responseSize)
    var totalBytesRead = 0
    var attempts = 0
    val maxAttempts = 10
    
    while (totalBytesRead < responseSize && attempts < maxAttempts) {
      val chunk = ByteArray(responseSize)
      val bytesRead = readBytesWithTimeout(chunk, 500)
      
      if (bytesRead > 0) {
        Log.d("BluetoothConnection", "Response bytes so far after sending a message: ${chunk.joinToString(" ") { "%02X".format(it) }}")
        Log.d("BluetoothConnection", "Number of response bytes so far: $bytesRead")
        try {
          System.arraycopy(chunk, 0, pageBuffer, totalBytesRead, bytesRead)
        } catch (e: ArrayIndexOutOfBoundsException) { // If the response doesn't fit in 64 bytes then it's incorrect and we have to retry
          Log.d("BluetoothConnection", "Incorrect response. Bytes received so far: ${pageBuffer.joinToString(" ") { "%02X".format(it) }}")
          Log.d("BluetoothConnection", "Incorrect response. Bytes received in the last (failing) loop: ${chunk.joinToString(" ") { "%02X".format(it) }}")
          _timingMapStatus.value = "CDI not ready to accept timing map. Retrying"
          return ByteArray(0)
        }
        totalBytesRead += bytesRead
      }
      
      attempts++
      if (totalBytesRead < responseSize) {
        delay(100) // wait for CDI to catch up. We shouldn't flood it with request. Otherwise Bluetooth module may disconnect and power cycle is needed.
      }
    }
    
    return pageBuffer
  }

  /**
   * Reads bytes from Bluetooth input stream with a timeout.
   * Unlike USB serial which has built-in timeout, Bluetooth streams need manual handling.
   *
   * @param buffer Buffer to read into
   * @param timeoutMs Timeout in milliseconds
   * @return Number of bytes read
   */
  private suspend fun readBytesWithTimeout(buffer: ByteArray, timeoutMs: Int): Int {
    val startTime = System.currentTimeMillis()
    var totalBytesRead = 0
    
    while (System.currentTimeMillis() - startTime < timeoutMs) {
      val available = inputStream?.available() ?: 0
      if (available > 0) {
        val bytesToRead = minOf(available, buffer.size - totalBytesRead)
        val bytesRead = inputStream?.read(buffer, totalBytesRead, bytesToRead) ?: 0
        totalBytesRead += bytesRead
        if (totalBytesRead >= buffer.size) {
          break
        }
      }
      delay(10)
    }
    
    return totalBytesRead
  }

  inner class BluetoothBinder : Binder() {
    fun getService(): BluetoothConnection = this@BluetoothConnection
  }
}