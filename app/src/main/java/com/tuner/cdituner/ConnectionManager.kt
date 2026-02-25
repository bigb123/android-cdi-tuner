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
  private var usbCommunication: UsbCommunication? = null
  private var bluetoothCommunication: BluetoothCommunication? = null

  private val _connectionType = MutableStateFlow(ConnectionType.NONE)
  val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

  private val _receivedData = MutableStateFlow<CdiDataDisplay?>(null)
  val receivedData: StateFlow<CdiDataDisplay?> = _receivedData.asStateFlow()

  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var usbObserverJob: Job? = null
  private var bluetoothObserverJob: Job? = null

  private val usbCommunicationConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      usbCommunication = (service as? UsbCommunication.UsbBinder)?.getService()
      if (currentConnectionType == ConnectionType.USB) {
        observeUsbService()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      usbCommunication = null
    }
  }

  private val bluetoothCommunicationConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      bluetoothCommunication = (service as? BluetoothCommunication.BluetoothBinder)?.getService()
      if (currentConnectionType == ConnectionType.BLUETOOTH) {
        observeBluetoothService()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      bluetoothCommunication = null
    }
  }

  init {
    // Bind to both services
    context.bindService(
      Intent(context, UsbCommunication::class.java),
      usbCommunicationConnection,
      Context.BIND_AUTO_CREATE
    )
    context.bindService(
      Intent(context, BluetoothCommunication::class.java),
      bluetoothCommunicationConnection,
      Context.BIND_AUTO_CREATE
    )
  }

  /**
   * Connect via USB
   */
  fun connectUsb() {
    disconnect()
    currentConnectionType = ConnectionType.USB
    _connectionType.value = ConnectionType.USB

    usbCommunication?.let {
      observeUsbService()
      it.findAndConnect()
    } ?: run {
      _connectionStatus.value = "USB Service not available"
    }
  }

  /**
   * Connect via Bluetooth to a specific device
   */
  fun connectBluetooth(deviceAddress: String) {
    disconnect()
    currentConnectionType = ConnectionType.BLUETOOTH
    _connectionType.value = ConnectionType.BLUETOOTH

    bluetoothCommunication?.let {
      observeBluetoothService()
      it.connectToDevice(deviceAddress)
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
        // USB disconnect is handled internally by UsbCommunication
        // Just update our state
      }
      ConnectionType.BLUETOOTH -> {
        bluetoothCommunication?.disconnect()
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
      context.unbindService(usbCommunicationConnection)
      context.unbindService(bluetoothCommunicationConnection)
    } catch (e: Exception) {
      // Ignore unbind errors
    }
  }

  private fun observeUsbService() {
    usbObserverJob?.cancel()
    bluetoothObserverJob?.cancel()

    usbCommunication?.let { service ->
      usbObserverJob = scope.launch {
        // Observe connection status
        launch {
          service.connectionStatus.collect { status ->
            _connectionStatus.value = "USB: $status"
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

    bluetoothCommunication?.let { service ->
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