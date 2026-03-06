package com.tuner.cdituner

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple helper class to save and load connection preferences using SharedPreferences.
 * This allows the app to remember the last used connection type and Bluetooth device.
 */
class ConnectionPreferences(context: Context) {

  companion object {
    private const val PREFS_NAME = "cdi_tuner_connection_prefs"
    private const val KEY_LAST_CONNECTION_TYPE = "last_connection_type"
    private const val KEY_LAST_BLUETOOTH_ADDRESS = "last_bluetooth_address"
    private const val KEY_LAST_BLUETOOTH_NAME = "last_bluetooth_name"
  }

  private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /**
   * Save the last used connection type (USB or BLUETOOTH)
   */
  fun saveConnectionType(type: ConnectionManager.ConnectionType) {
    prefs.edit()
      .putString(KEY_LAST_CONNECTION_TYPE, type.name)
      .apply()
  }

  /**
   * Get the last used connection type
   * Returns NONE if no preference was saved
   */
  fun getLastConnectionType(): ConnectionManager.ConnectionType {
    val typeName = prefs.getString(KEY_LAST_CONNECTION_TYPE, ConnectionManager.ConnectionType.NONE.name)
    return try {
      ConnectionManager.ConnectionType.valueOf(typeName ?: ConnectionManager.ConnectionType.NONE.name)
    } catch (e: IllegalArgumentException) {
      ConnectionManager.ConnectionType.NONE
    }
  }

  /**
   * Save the last connected Bluetooth device info
   */
  fun saveBluetoothDevice(address: String, name: String? = null) {
    prefs.edit()
      .putString(KEY_LAST_BLUETOOTH_ADDRESS, address)
      .putString(KEY_LAST_BLUETOOTH_NAME, name)
      .apply()
  }

  /**
   * Get the last connected Bluetooth device address
   * Returns null if no device was saved
   */
  fun getLastBluetoothAddress(): String? {
    return prefs.getString(KEY_LAST_BLUETOOTH_ADDRESS, null)
  }

  /**
   * Get the last connected Bluetooth device name (for display purposes)
   * Returns null if no name was saved
   */
  fun getLastBluetoothName(): String? {
    return prefs.getString(KEY_LAST_BLUETOOTH_NAME, null)
  }

  /**
   * Clear all saved preferences
   */
  fun clear() {
    prefs.edit().clear().apply()
  }
}
