package com.tuner.cdituner

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Interface for CDI communication I/O operations.
 * Implementations will handle USB or Bluetooth specific I/O.
 */
interface CdiIoHandler {
  /**
   * Write data to the device
   * @param data bytes to write
   * @throws IOException if write fails
   */
  suspend fun write(data: ByteArray)

  /**
   * Read data from the device
   * @param buffer buffer to read into
   * @param timeout timeout in milliseconds (optional, may be ignored by some implementations)
   * @return number of bytes read
   * @throws IOException if read fails
   */
  suspend fun read(buffer: ByteArray, timeout: Int = 500): Int

  /**
   * Check if the connection is still active
   * @return true if connected
   */
  fun isConnected(): Boolean

  /**
   * Close the connection
   */
  fun close()
}

/**
 * Unified CDI protocol handler for both USB and Bluetooth connections
 */
class CdiProtocol(
  private val ioHandler: CdiIoHandler,
  private val scope: CoroutineScope
) {
  private val _receivedData = MutableStateFlow<CdiDataDisplay?>(null)
  val receivedData: StateFlow<CdiDataDisplay?> = _receivedData.asStateFlow()

  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

  private var monitoringJob: Job? = null

  companion object {
    // CDI protocol constants
    private val INIT_COMMAND = byteArrayOf(0x01, 0xAB.toByte(), 0xAC.toByte(), 0xA1.toByte())
    private const val PACKET_SIZE = 22
    private const val PACKET_START = 0x03.toByte()
    private const val PACKET_END = 0xA9.toByte()
    private const val INIT_ATTEMPTS = 2
    private const val MONITOR_DELAY = 100L
  }

  /**
   * Initialize CDI communication
   */
  suspend fun initializeCdi() {
    try {
      for (i in 1..INIT_ATTEMPTS) {
        ioHandler.write(INIT_COMMAND)
        delay(100)

        val response = ByteArray(64)
        val len = ioHandler.read(response)
        _connectionStatus.value = "Init #$i, got $len bytes"
      }

      _connectionStatus.value = "Initialized, starting monitor"
      startDataMonitor()
    } catch (e: IOException) {
      _connectionStatus.value = "Error during init: ${e.message}"
      disconnect()
    }
  }

  /**
   * Start monitoring for CDI data packets
   */
  private fun startDataMonitor() {
    monitoringJob?.cancel()
    monitoringJob = scope.launch {
      var packetCount = 0
      val buffer = ByteArray(256)
      var bufferPos = 0

      while (isActive && ioHandler.isConnected()) {
        try {
          // Send request
          ioHandler.write(INIT_COMMAND)
          delay(MONITOR_DELAY)

          // Read response
          val tempBuffer = ByteArray(64)
          val numBytesRead = ioHandler.read(tempBuffer)

          if (numBytesRead > 0) {
            // Add new data to buffer
            val bytesToCopy = minOf(numBytesRead, buffer.size - bufferPos)
            System.arraycopy(tempBuffer, 0, buffer, bufferPos, bytesToCopy)
            bufferPos += bytesToCopy

            // Process buffer for complete packets
            val processedBytes = processBuffer(buffer, bufferPos) { packet ->
              val decoded = decodeCdiPacket(packet)
              if (decoded != null) {
                _receivedData.value = decoded
                packetCount++
                _connectionStatus.value = "Connected - Packets: $packetCount"
              }
            }

            // Shift remaining data to beginning of buffer
            if (processedBytes > 0 && bufferPos > processedBytes) {
              System.arraycopy(buffer, processedBytes, buffer, 0, bufferPos - processedBytes)
              bufferPos -= processedBytes
            } else if (processedBytes > 0) {
              bufferPos = 0
            }

            // Buffer overflow protection
            if (bufferPos > 128) {
              System.arraycopy(buffer, bufferPos - 64, buffer, 0, 64)
              bufferPos = 64
            }
          }

          delay(MONITOR_DELAY)
        } catch (e: IOException) {
          _connectionStatus.value = "Connection lost: ${e.message}"
          disconnect()
          break
        }
      }
    }
  }

  /**
   * Process buffer to find and extract complete CDI packets
   * @param buffer the data buffer
   * @param length valid data length in buffer
   * @param onPacketFound callback for each complete packet found
   * @return number of bytes processed
   */
  private fun processBuffer(
    buffer: ByteArray,
    length: Int,
    onPacketFound: (ByteArray) -> Unit
  ): Int {
    if (length < PACKET_SIZE) return 0

    // Find start of packet
    for (i in 0 until length - PACKET_SIZE + 1) {
      if (buffer[i] == PACKET_START && buffer[i + PACKET_SIZE - 1] == PACKET_END) {
        val packet = buffer.sliceArray(i until i + PACKET_SIZE)
        onPacketFound(packet)
        return i + PACKET_SIZE
      }
    }

    return 0
  }

  /**
   * Decode a CDI packet into structured data
   * @param data raw packet bytes
   * @return decoded CdiDataDisplay or null if invalid
   */
  fun decodeCdiPacket(data: ByteArray): CdiDataDisplay? {
    if (data.size != PACKET_SIZE ||
      data[0] != PACKET_START ||
      data[PACKET_SIZE - 1] != PACKET_END) {
      return null
    }

    val rpm = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
    val batteryVoltage = (data[7].toInt() and 0xFF) / 10.0f
    val statusByte = data[8].toInt() and 0xFF
    val timingByte = data[9].toInt() and 0xFF

    return CdiDataDisplay(rpm, batteryVoltage, statusByte, timingByte)
  }

  /**
   * Disconnect and cleanup
   */
  fun disconnect() {
    monitoringJob?.cancel()
    monitoringJob = null
    ioHandler.close()
    _connectionStatus.value = "Disconnected"
  }
}