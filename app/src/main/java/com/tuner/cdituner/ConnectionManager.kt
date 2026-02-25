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
  private var usbService: UsbService? = null
  private var bluetoothService: BluetoothService? = null

  private val _connectionType = MutableStateFlow(ConnectionType.NONE)
  val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

  private val _receivedData = MutableStateFlow<CdiData?>(null)
  val receivedData: StateFlow<CdiData?> = _receivedData.asStateFlow()

  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var usbObserverJob: Job? = null
  private var bluetoothObserverJob: Job? = null

  private val usbServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      usbService = (service as? UsbService.UsbBinder)?.getService()
      if (currentConnectionType == ConnectionType.USB) {
        observeUsbService()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      usbService = null
    }
  }

  private val bluetoothServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      bluetoothService = (service as? BluetoothService.BluetoothBinder)?.getService()
      if (currentConnectionType == ConnectionType.BLUETOOTH) {
        observeBluetoothService()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      bluetoothService = null
    }
  }

  init {
    // Bind to both services
    context.bindService(
      Intent(context, UsbService::class.java),
      usbServiceConnection,
      Context.BIND_AUTO_CREATE
    )
    context.bindService(
      Intent(context, BluetoothService::class.java),
      bluetoothServiceConnection,
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

    usbService?.let {
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

    bluetoothService?.let {
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
    val selector = BluetoothDeviceSelector(context) { device ->
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
        // USB disconnect is handled internally by UsbService
        // Just update our state
      }
      ConnectionType.BLUETOOTH -> {
        bluetoothService?.disconnect()
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
      context.unbindService(usbServiceConnection)
      context.unbindService(bluetoothServiceConnection)
    } catch (e: Exception) {
      // Ignore unbind errors
    }
  }

  private fun observeUsbService() {
    usbObserverJob?.cancel()
    bluetoothObserverJob?.cancel()

    usbService?.let { service ->
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

    bluetoothService?.let { service ->
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