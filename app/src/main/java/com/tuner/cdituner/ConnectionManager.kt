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
  private var usbConnectivity: UsbConnectivity? = null
  private var bluetoothConnectivity: BluetoothConnectivity? = null

  private val _connectionType = MutableStateFlow(ConnectionType.NONE)
  val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

  private val _receivedData = MutableStateFlow<CdiMessageInterpretation?>(null)
  val receivedData: StateFlow<CdiMessageInterpretation?> = _receivedData.asStateFlow()

  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var usbObserverJob: Job? = null
  private var bluetoothObserverJob: Job? = null

  private val usbConnectivityConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      usbConnectivity = (service as? UsbConnectivity.UsbBinder)?.getService()
      if (currentConnectionType == ConnectionType.USB) {
        observeUsbService()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      usbConnectivity = null
    }
  }

  private val bluetoothConnectivityConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      bluetoothConnectivity = (service as? BluetoothConnectivity.BluetoothBinder)?.getService()
      if (currentConnectionType == ConnectionType.BLUETOOTH) {
        observeBluetoothService()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      bluetoothConnectivity = null
    }
  }

  init {
    // Bind to both services
    context.bindService(
      Intent(context, UsbConnectivity::class.java),
      usbConnectivityConnection,
      Context.BIND_AUTO_CREATE
    )
    context.bindService(
      Intent(context, BluetoothConnectivity::class.java),
      bluetoothConnectivityConnection,
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

    usbConnectivity?.let {
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

    bluetoothConnectivity?.let {
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
        // USB disconnect is handled internally by UsbConnectivity
        // Just update our state
      }
      ConnectionType.BLUETOOTH -> {
        bluetoothConnectivity?.disconnect()
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
      context.unbindService(usbConnectivityConnection)
      context.unbindService(bluetoothConnectivityConnection)
    } catch (e: Exception) {
      // Ignore unbind errors
    }
  }

  private fun observeUsbService() {
    usbObserverJob?.cancel()
    bluetoothObserverJob?.cancel()

    usbConnectivity?.let { service ->
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

    bluetoothConnectivity?.let { service ->
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