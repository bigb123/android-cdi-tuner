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

  // Preferences for saving/loading connection settings
  private val preferences = ConnectionPreferences(context)
  
  // Track the last connected Bluetooth address (for saving to preferences)
  private var lastBluetoothAddress: String? = null

  private val _connectionType = MutableStateFlow(ConnectionType.NONE)
  val connectionType: StateFlow<ConnectionType> = _connectionType.asStateFlow()

  private val _connectionStatus = MutableStateFlow("Disconnected")
  val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

  private val _receivedData = MutableStateFlow<CdiReceivedMessageDecoder?>(null)
  val receivedData: StateFlow<CdiReceivedMessageDecoder?> = _receivedData.asStateFlow()

  private val _timingMap = MutableStateFlow<List<TimingPoint>?>(null)
  val timingMap: StateFlow<List<TimingPoint>?> = _timingMap.asStateFlow()

  private val _timingMapStatus = MutableStateFlow<String?>(null)
  val timingMapStatus: StateFlow<String?> = _timingMapStatus.asStateFlow()

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

    // Close other connection if any before attempting to establish a new connection
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
   * Waits for service to be bound if not already available
   */
  fun connectBluetooth(deviceAddress: String, deviceName: String? = null) {

    // Close other connection if any before attempting to establish a new connection
    disconnect()

    currentConnectionType = ConnectionType.BLUETOOTH
    _connectionType.value = ConnectionType.BLUETOOTH
    
    // Save the address for later preference saving
    lastBluetoothAddress = deviceAddress

    scope.launch {
      // Wait for Bluetooth service to be bound (with timeout)
      val maxWaitTime = 5000L // 5 seconds timeout
      val startTime = System.currentTimeMillis()
      
      while (!bluetoothServiceBound && (System.currentTimeMillis() - startTime) < maxWaitTime) {
        _connectionStatus.value = "BT: Waiting for service..."
        delay(100)
      }
      
      bluetoothConnection?.let {
        observeBluetoothService()
        it.connectToDevice(deviceAddress)
        it.startCdiCommunication()
        
        // Save Bluetooth device to preferences
        preferences.saveBluetoothDevice(deviceAddress, deviceName)
        preferences.saveConnectionType(ConnectionType.BLUETOOTH)
      } ?: run {
        _connectionStatus.value = "BT: Service not available (timeout)"
        currentConnectionType = ConnectionType.NONE
        _connectionType.value = ConnectionType.NONE
      }
    }
  }

  /**
   * Show Bluetooth device selection dialog
   */
  fun showBluetoothDeviceSelector() {
    val selector = BluetoothDeviceSelectionMenu(context) { device ->
      connectBluetooth(device.address, device.name)
    }
    selector.showDeviceSelectionDialog()
  }

  /**
   * Auto-connect based on saved preferences.
   * Call this on app startup when NOT triggered by USB attachment.
   */
  fun autoConnectFromPreferences() {
    when (preferences.getLastConnectionType()) {
      ConnectionType.USB -> {
        connectUsb()
      }
      ConnectionType.BLUETOOTH -> {
        val address = preferences.getLastBluetoothAddress()
        if (address != null) {
          connectBluetooth(address, preferences.getLastBluetoothName())
        }
      }
      ConnectionType.NONE -> {
        // No saved preference, do nothing
      }
    }
  }

  /**
   * Get the last saved connection type (for UI display purposes)
   */
  fun getLastConnectionType(): ConnectionType = preferences.getLastConnectionType()

  /**
   * Get the last saved Bluetooth device name (for UI display purposes)
   */
  fun getLastBluetoothName(): String? = preferences.getLastBluetoothName()

  /**
   * Read the timing map from CDI.
   * Only reads if not already loaded (cached).
   * Call this when user opens the Timing tab.
   */
  fun readTimingMapIfNeeded() {
    // Only read if we don't already have a timing map cached
    if (_timingMap.value != null) {
      return  // Already loaded, keep the cached version
    }
    
    when (currentConnectionType) {
      ConnectionType.USB -> {
        usbConnection?.readTimingMap()
      }
      ConnectionType.BLUETOOTH -> {
        // TODO: Implement for Bluetooth when needed
        _timingMapStatus.value = "Timing map reading not yet supported over Bluetooth"
      }
      ConnectionType.NONE -> {
        _timingMapStatus.value = "Not connected to CDI"
      }
    }
  }

  /**
   * Force refresh the timing map from CDI.
   * Reads even if already cached.
   */
  fun refreshTimingMap() {
    _timingMap.value = null  // Clear cache
    readTimingMapIfNeeded()
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
                // Save USB as the preferred connection type
                preferences.saveConnectionType(ConnectionType.USB)
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

        // Observe timing map data
        launch {
          service.timingMap.collect { data ->
            _timingMap.value = data
          }
        }

        // Observe timing map status
        launch {
          service.timingMapStatus.collect { status ->
            _timingMapStatus.value = status
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