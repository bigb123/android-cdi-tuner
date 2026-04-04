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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
  
  // Mutex to prevent parallel sendMessage calls
  private val sendMessageMutex = Mutex()

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
          _connectionStatus.value = "Connection failed. Retrying in ${CdiTimingMapProtocol.WAIT_LONG / 1000}s..."
          delay(CdiTimingMapProtocol.WAIT_LONG)
          continue
        }

        _connectionStatus.value = "Connected, starting CDI communication..."


        // Inner loop: reads CDI packets while connected
        try {
          while (isActive) {

            // Skip sending/reading when paused for timing map operations
            if (pauseCdiCommunication) {
              delay(CdiTimingMapProtocol.WAIT_LONG)
              continue
            }
            else {
              var response = sendMessage(CdiMessageProcessing.STATUS_MESSAGE, CdiTimingMapProtocol.STATUS_PAGE_SIZE)
              while (response[0] != 0x03.toByte()) {
                response = sendMessage(CdiMessageProcessing.STATUS_MESSAGE, CdiTimingMapProtocol.STATUS_PAGE_SIZE)
              }

              packetCount = CdiMessageProcessing.processMessage(response, 0, packetCount, _receivedData, _connectionStatus)
            }
          }
        } catch (e: IOException) {
          // Connection lost — close socket and let the outer loop reconnect
          _connectionStatus.value = "Connection lost. Waiting for device..."
//          closeSocket()
          delay(CdiTimingMapProtocol.WAIT_LONG)
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
      // Avoid sending new messages if writing operation is still ongoing
      while (pauseCdiCommunication) {
        delay(CdiTimingMapProtocol.WAIT)
        continue
      }

      // Pause normal data monitoring (keeps connection alive)
      pauseCdiCommunication = true
      Log.d("BluetoothConnection", "Reading a timing map")
      
      // Clear cached timing map to ensure StateFlow emits the new value
      // (StateFlow uses structural equality, so identical data wouldn't be re-emitted)
      _timingMap.value = null
      _timingMapStatus.value = "Reading timing map..."

      try {
        // Use shared protocol implementation
        val timingMap = CdiTimingMapProtocol.readTimingMapData(
          sendMessage = { message -> sendMessage(message) },
          onStatus = { status -> _timingMapStatus.value = status }
        )

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
        delay(CdiTimingMapProtocol.WAIT)
        continue
      }
      // Pause normal data monitoring (keeps connection alive)
      pauseCdiCommunication = true
      Log.d("BluetoothConnection", "Writing a timing map")
      
      _timingMapStatus.value = "Writing timing map..."

      try {
        // Use shared protocol implementation
        CdiTimingMapProtocol.writeTimingMapData(
          timingMap = timingMap,
          sendMessage = { message -> sendMessage(message) },
          onStatus = { status -> _timingMapStatus.value = status }
        )

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
      // Send message
      outputStream?.write(message)
      outputStream?.flush()
      Log.d("BluetoothConnection", "Sent a message: ${message.joinToString(" ") { "%02X".format(it) }}")

        // Wait for CDI to catch up
        delay(CdiTimingMapProtocol.WAIT)

      // read a response
      val response = readFullPage(responseSize)
      Log.d("BluetoothConnection", "CDI response: ${response.joinToString(" ") { "%02X".format(it) }}")

      response
    }
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

      Log.d("BluetoothConnection", "Response bytes so far after sending a message: ${chunk.joinToString(" ") { "%02X".format(it) }}")
      Log.d("BluetoothConnection", "Number of response bytes so far: $bytesRead")
      if (bytesRead > 0) {
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
        delay(CdiTimingMapProtocol.WAIT) // wait for CDI to catch up. We shouldn't flood it with request. Otherwise, Bluetooth module may disconnect and power cycle is needed.
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
    }
    
    return totalBytesRead
  }

  inner class BluetoothBinder : Binder() {
    fun getService(): BluetoothConnection = this@BluetoothConnection
  }
}