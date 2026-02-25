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

class BluetoothCommunication : Service() {

  private val binder = BluetoothBinder()
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothSocket: BluetoothSocket? = null
  private var inputStream: InputStream? = null
  private var outputStream: OutputStream? = null
  private var cdiProtocol: CdiProtocol? = null
  private var bluetoothIoHandler: BluetoothIoHandler? = null

  // Use CdiProtocol's data flow, but keep our own connection status for Bluetooth-specific messages
  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus = _connectionStatus.asStateFlow()
  
  // Expose the CdiProtocol's receivedData flow
  val receivedData get() = cdiProtocol?.receivedData ?: MutableStateFlow<CdiDataDisplay?>(null).asStateFlow()

  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)

  // Standard SPP UUID for Serial Port Profile
  private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

  /**
   * Bluetooth-specific implementation of CdiIoHandler
   */
  private inner class BluetoothIoHandler(
    private val input: InputStream,
    private val output: OutputStream
  ) : CdiIoHandler {
    
    override suspend fun write(data: ByteArray) {
      withContext(Dispatchers.IO) {
        output.write(data)
        output.flush()
      }
    }

    override suspend fun read(buffer: ByteArray, timeout: Int): Int {
      return withContext(Dispatchers.IO) {
        // For Bluetooth, we ignore timeout and use available bytes
        val available = input.available()
        if (available > 0) {
          val bytesToRead = minOf(available, buffer.size)
          input.read(buffer, 0, bytesToRead)
        } else {
          0
        }
      }
    }

    override fun isConnected(): Boolean {
      return bluetoothSocket?.isConnected == true
    }

    override fun close() {
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
    }
  }

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

    disconnect()

    scope.launch {
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

        if (inputStream != null && outputStream != null) {
          // Create IO handler and CdiProtocol
          bluetoothIoHandler = BluetoothIoHandler(inputStream!!, outputStream!!)
          cdiProtocol = CdiProtocol(bluetoothIoHandler!!, scope)
          
          // Subscribe to CdiProtocol's connection status
          launch {
            cdiProtocol?.connectionStatus?.collect { status ->
              _connectionStatus.value = status
            }
          }
          
          _connectionStatus.value = "Connected, initializing..."
          
          // Start CDI initialization
          cdiProtocol?.initializeCdi()
        } else {
          _connectionStatus.value = "Failed to get I/O streams"
          disconnect()
        }

      } catch (e: IOException) {
        _connectionStatus.value = "Connection failed: ${e.message}"
        disconnect()
      } catch (e: SecurityException) {
        _connectionStatus.value = "Permission denied"
        disconnect()
      }
    }
  }

  fun disconnect() {
    cdiProtocol?.disconnect()
    cdiProtocol = null
    bluetoothIoHandler = null
    _connectionStatus.value = "Disconnected"
  }

  @SuppressLint("MissingPermission")
  fun getPairedDevices(): List<BluetoothDevice> {
    return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
  }

  inner class BluetoothBinder : Binder() {
    fun getService(): BluetoothCommunication = this@BluetoothCommunication
  }
}