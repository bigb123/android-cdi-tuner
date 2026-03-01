package com.tuner.cdituner

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Binder
import android.os.IBinder
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

  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)
  private var readingJob: Job? = null

  companion object {
    private const val RECONNECT_DELAY_MS = 2000L
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
   * Call startCdiCommunication() after this to begin the resilient loop.
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
  fun startCdiCommunication() {
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
            outputStream?.write(CdiMessageProcessing.CDI_MESSAGE)
            outputStream?.flush()
            delay(100)

            val available = inputStream?.available() ?: 0
            if (available > 0) {
              val bytesToRead = minOf(available, buffer.size - bufferPos)
              val numBytesRead = inputStream?.read(buffer, bufferPos, bytesToRead) ?: 0
              bufferPos += numBytesRead

              if (bufferPos >= 22) {
                var startIdx = -1
                for (i in 0 until bufferPos - 21) {
                  if (buffer[i] == 0x03.toByte() && buffer[i + 21] == 0xA9.toByte()) {
                    startIdx = i
                    break
                  }
                }

                if (startIdx >= 0) {
                  val data = buffer.sliceArray(startIdx until startIdx + 22)
                  val decoded = CdiMessageProcessing.decodeCdiPacket(data)
                  if (decoded != null) {
                    _receivedData.value = decoded
                    packetCount++
                    _connectionStatus.value = "Connected - Packets: $packetCount"
                  }

                  val remaining = bufferPos - (startIdx + 22)
                  if (remaining > 0) {
                    System.arraycopy(buffer, startIdx + 22, buffer, 0, remaining)
                  }
                  bufferPos = remaining
                } else if (bufferPos > 128) {
                  System.arraycopy(buffer, bufferPos - 64, buffer, 0, 64)
                  bufferPos = 64
                }
              }
            }

            delay(100)
          }
        } catch (e: IOException) {
          // Connection lost — close socket and let the outer loop reconnect
          _connectionStatus.value = "Connection lost. Waiting for device..."
          closeSocket()
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

  inner class BluetoothBinder : Binder() {
    fun getService(): BluetoothConnection = this@BluetoothConnection
  }
}