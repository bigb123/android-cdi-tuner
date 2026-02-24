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

class BluetoothService : Service() {

    private val binder = BluetoothBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val _receivedData = MutableStateFlow<CdiData?>(null)
    val receivedData = _receivedData.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus = _connectionStatus.asStateFlow()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var readingJob: Job? = null

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

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String) {
        if (bluetoothAdapter == null) {
            _connectionStatus.value = "Bluetooth not available"
            return
        }

        readingJob?.cancel()
        disconnect()

        readingJob = scope.launch {
            try {
                _connectionStatus.value = "Connecting..."
                
                val device: BluetoothDevice = bluetoothAdapter!!.getRemoteDevice(deviceAddress)
                
                // Create RFCOMM socket
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                
                // Cancel discovery to speed up connection
                bluetoothAdapter?.cancelDiscovery()
                
                // Connect to the device
                bluetoothSocket?.connect()
                
                // Get streams
                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream
                
                _connectionStatus.value = "Connected, initializing..."
                
                // Initialize CDI communication
                initializeCdi()
                
            } catch (e: IOException) {
                _connectionStatus.value = "Connection failed: ${e.message}"
                disconnect()
            } catch (e: SecurityException) {
                _connectionStatus.value = "Permission denied"
                disconnect()
            }
        }
    }

    private suspend fun initializeCdi() {
        val initBytes = byteArrayOf(0x01, 0xAB.toByte(), 0xAC.toByte(), 0xA1.toByte())
        try {
            for (i in 1..2) {
                outputStream?.write(initBytes)
                outputStream?.flush()
                delay(100)
                
                val response = ByteArray(64)
                val available = inputStream?.available() ?: 0
                if (available > 0) {
                    val len = inputStream?.read(response, 0, minOf(available, 64)) ?: 0
                    _connectionStatus.value = "Init #${i}, got ${len} bytes"
                }
            }
            _connectionStatus.value = "Initialized, starting monitor"
            startDataMonitor()
        } catch (e: IOException) {
            _connectionStatus.value = "Error during init: ${e.message}"
            disconnect()
        }
    }

    private fun startDataMonitor() {
        readingJob = scope.launch {
            val request = byteArrayOf(0x01, 0xAB.toByte(), 0xAC.toByte(), 0xA1.toByte())
            var packetCount = 0
            val buffer = ByteArray(256) // Larger buffer for Bluetooth
            var bufferPos = 0
            
            while (isActive) {
                try {
                    // Send request
                    outputStream?.write(request)
                    outputStream?.flush()
                    delay(100)

                    // Read response
                    val available = inputStream?.available() ?: 0
                    if (available > 0) {
                        val bytesToRead = minOf(available, buffer.size - bufferPos)
                        val numBytesRead = inputStream?.read(buffer, bufferPos, bytesToRead) ?: 0
                        bufferPos += numBytesRead

                        // Look for valid 22-byte packet starting with 0x03
                        if (bufferPos >= 22) {
                            // Find start of packet (0x03)
                            var startIdx = -1
                            for (i in 0 until bufferPos - 21) {
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
                                
                                // Shift remaining data to beginning of buffer
                                val remaining = bufferPos - (startIdx + 22)
                                if (remaining > 0) {
                                    System.arraycopy(buffer, startIdx + 22, buffer, 0, remaining)
                                }
                                bufferPos = remaining
                            } else if (bufferPos > 128) {
                                // Buffer overflow protection - keep last 64 bytes
                                System.arraycopy(buffer, bufferPos - 64, buffer, 0, 64)
                                bufferPos = 64
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
        if (data.size != 22 || data[0] != 0x03.toByte() || data[21] != 0xA9.toByte()) {
            return null
        }

        val rpm = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val batteryVoltage = (data[7].toInt() and 0xFF) / 10.0f
        val statusByte = data[8].toInt() and 0xFF
        val timingByte = data[9].toInt() and 0xFF

        return CdiData(rpm, batteryVoltage, statusByte, timingByte)
    }

    fun disconnect() {
        readingJob?.cancel()
        readingJob = null
        
        try {
            outputStream?.close()
            inputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            // Ignore close errors
        }
        
        outputStream = null
        inputStream = null
        bluetoothSocket = null
        _connectionStatus.value = "Disconnected"
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    inner class BluetoothBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }
}