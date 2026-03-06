package com.tuner.cdituner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages connections to either USB or Bluetooth service
 * Provides a unified interface for CDI communication
 */
class ConnectionManager(private val context: Context) {

  enum class ConnectionType {
    NONE,
    USB,
    BLUETOOTH
  }

  private var currentConnectionType = ConnectionType.NONE
  private var usbConnection: UsbConnection? = null
  private var bluetoothConnection: BluetoothConnection? = null
  
  // Flags to track service binding status
  private var usbServiceBound = false
  private var bluetoothServiceBound = false

  private val _connectionType = MutableStateFlow(ConnectionType.NONE)
  val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

  private val _receivedData = MutableStateFlow<CdiReceivedMessageDecoder?>(null)
  val receivedData: StateFlow<CdiReceivedMessageDecoder?> = _receivedData.asStateFlow()

  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var usbObserverJob: Job? = null
  private var bluetoothObserverJob: Job? = null

  private val usbConnectionConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      usbConnection = (service as? UsbConnection.UsbBinder)?.getService()
      usbServiceBound = true
      if (currentConnectionType == ConnectionType.USB) {
        observeUsbService()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      usbConnection = null
      usbServiceBound = false
    }
  }

  private val bluetoothConnectionConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      bluetoothConnection = (service as? BluetoothConnection.BluetoothBinder)?.getService()
      bluetoothServiceBound = true
      if (currentConnectionType == ConnectionType.BLUETOOTH) {
        observeBluetoothService()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      bluetoothConnection = null
      bluetoothServiceBound = false
    }
  }

  init {
    // Bind to both services
    context.bindService(
      Intent(context, UsbConnection::class.java),
      usbConnectionConnection,
      Context.BIND_AUTO_CREATE
    )
    context.bindService(
      Intent(context, BluetoothConnection::class.java),
      bluetoothConnectionConnection,
      Context.BIND_AUTO_CREATE
    )
  }

  /**
   * Connect via USB
   * Waits for service to be bound if not already available
   */
  fun connectUsb() {
    disconnect()
    // Don't set connection type yet - wait until we know connection succeeded
    
    scope.launch {
      // Wait for USB service to be bound (with timeout)
      val maxWaitTime = 5000L // 5 seconds timeout
      val startTime = System.currentTimeMillis()
      
      while (!usbServiceBound && (System.currentTimeMillis() - startTime) < maxWaitTime) {
        _connectionStatus.value = "Waiting for USB service..."
        delay(100)
      }
      
      usbConnection?.let { usb ->
        observeUsbService()
        // Call the suspend function to find and connect
        while (usb.findAndConnect() != 0) {
          delay(1000)
        }
        // After findAndConnect returns 0, check if we actually connected
        // by observing the connection status
      } ?: run {
        _connectionStatus.value = "USB Service not available (timeout)"
      }
    }
  }

  /**
   * Connect via Bluetooth to a specific device
   */
  fun connectBluetooth(deviceAddress: String) {
//    disconnect()
    currentConnectionType = ConnectionType.BLUETOOTH
    _connectionType.value = ConnectionType.BLUETOOTH

    bluetoothConnection?.let {
      observeBluetoothService()
      it.connectToDevice(deviceAddress)
      it.startCdiCommunication()
    } ?: run {
      _connectionStatus.value = "Bluetooth Service not available"
    }
  }

  /**
   * Show Bluetooth device selection dialog
   */
  fun showBluetoothDeviceSelector() {
    val selector = BluetoothDeviceSelectionMenu(context) { device ->
      connectBluetooth(device.address)
    }
    selector.showDeviceSelectionDialog()
  }

  /**
   * Disconnect current connection
   */
  fun disconnect() {
    when (currentConnectionType) {
      ConnectionType.USB -> {
        usbConnection?.disconnect()
      }
      ConnectionType.BLUETOOTH -> {
        bluetoothConnection?.disconnect()
      }
      ConnectionType.NONE -> {
        // Already disconnected
      }
    }
    currentConnectionType = ConnectionType.NONE
    _connectionType.value = ConnectionType.NONE
    _connectionStatus.value = "Disconnected"
  }

  /**
   * Clean up resources
   */
  fun cleanup() {
    disconnect()
    scope.cancel()
    try {
      context.unbindService(usbConnectionConnection)
      context.unbindService(bluetoothConnectionConnection)
    } catch (e: Exception) {
      // Ignore unbind errors
    }
  }

  private fun observeUsbService() {
    usbObserverJob?.cancel()
    bluetoothObserverJob?.cancel()

    usbConnection?.let { service ->
      usbObserverJob = scope.launch {
        // Observe connection status
        launch {
          service.connectionStatus.collect { status ->
            _connectionStatus.value = "USB: $status"
            
            // Update connection type based on actual status
            when {
              status.contains("Connected") -> {
                // Successfully connected - set connection type to USB
                currentConnectionType = ConnectionType.USB
                _connectionType.value = ConnectionType.USB
              }
              status.contains("Permission not granted") ||
              status.contains("Permission denied") ||
              status == "Disconnected" -> {
                // Permission denied or disconnected - reset to NONE
                currentConnectionType = ConnectionType.NONE
                _connectionType.value = ConnectionType.NONE
              }
              // For other statuses (like "No serial devices found"), keep current state
            }
          }
        }

        // Observe received data
        launch {
          service.receivedData.collect { data ->
            _receivedData.value = data
          }
        }
      }
    }
  }

  private fun observeBluetoothService() {
    usbObserverJob?.cancel()
    bluetoothObserverJob?.cancel()

    bluetoothConnection?.let { service ->
      bluetoothObserverJob = scope.launch {
        // Observe connection status
        launch {
          service.connectionStatus.collect { status ->
            _connectionStatus.value = "BT: $status"
          }
        }

        // Observe received data
        launch {
          service.receivedData.collect { data ->
            _receivedData.value = data
          }
        }
      }
    }
  }
}