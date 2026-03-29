package com.tuner.cdituner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provides GPS-based speed measurement for the motorcycle speedometer.
 * Uses the device's GPS sensor to get real-time speed data.
 * 
 * Simple usage:
 * 1. Create instance: val speedProvider = SpeedProvider(context)
 * 2. Check permission: speedProvider.hasLocationPermission()
 * 3. Start updates: speedProvider.startLocationUpdates()
 * 4. Observe speed: speedProvider.speedKmh.collectAsState()
 * 5. Stop when done: speedProvider.stopLocationUpdates()
 */
class SpeedProvider(private val context: Context) {

  private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

  // Speed in km/h - main value to observe
  private val _speedKmh = MutableStateFlow(0f)
  val speedKmh: StateFlow<Float> = _speedKmh.asStateFlow()

  // Speed in m/s - raw GPS value
  private val _speedMps = MutableStateFlow(0f)
  val speedMps: StateFlow<Float> = _speedMps.asStateFlow()

  // GPS status
  private val _hasGpsFix = MutableStateFlow(false)
  val hasGpsFix: StateFlow<Boolean> = _hasGpsFix.asStateFlow()

  // Is currently tracking
  private val _isTracking = MutableStateFlow(false)
  val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

  // Location listener
  private val locationListener = object : LocationListener {
  override fun onLocationChanged(location: Location) {
    _hasGpsFix.value = true
    
    if (location.hasSpeed()) {
    // GPS provides speed in m/s
    val speedMetersPerSecond = location.speed
    _speedMps.value = speedMetersPerSecond
    
    // Convert to km/h (multiply by 3.6)
    _speedKmh.value = speedMetersPerSecond * 3.6f
    } else {
    // No speed available from GPS (device might be stationary)
    _speedMps.value = 0f
    _speedKmh.value = 0f
    }
  }

  override fun onProviderEnabled(provider: String) {
    // GPS turned on
  }

  override fun onProviderDisabled(provider: String) {
    // GPS turned off
    _hasGpsFix.value = false
    _speedKmh.value = 0f
    _speedMps.value = 0f
  }

  @Deprecated("Deprecated in API level 29")
  override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    // Legacy callback, kept for older devices
  }
  }

  /**
   * Check if the app has location permission.
   */
  fun hasLocationPermission(): Boolean {
  return ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.ACCESS_FINE_LOCATION
  ) == PackageManager.PERMISSION_GRANTED
  }

  /**
   * Check if GPS is enabled on the device.
   */
  fun isGpsEnabled(): Boolean {
  return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
  }

  /**
   * Start receiving GPS location updates.
   * Call this after ensuring permission is granted.
   * 
   * @param minTimeMs Minimum time between updates in milliseconds (default: 500ms)
   * @param minDistanceM Minimum distance between updates in meters (default: 0m for continuous updates)
   */
  fun startLocationUpdates(minTimeMs: Long = 500L, minDistanceM: Float = 0f) {
  if (!hasLocationPermission()) {
    return
  }

  if (_isTracking.value) {
    return // Already tracking
  }

  try {
    locationManager.requestLocationUpdates(
    LocationManager.GPS_PROVIDER,
    minTimeMs,
    minDistanceM,
    locationListener,
    Looper.getMainLooper()
    )
    _isTracking.value = true
  } catch (e: SecurityException) {
    // Permission was revoked
    _isTracking.value = false
  }
  }

  /**
   * Stop receiving GPS location updates.
   * Call this when the gauge screen is no longer visible to save battery.
   */
  fun stopLocationUpdates() {
  try {
    locationManager.removeUpdates(locationListener)
  } catch (e: Exception) {
    // Ignore errors when stopping
  }
  _isTracking.value = false
  _hasGpsFix.value = false
  }

  /**
   * Clean up resources. Call this when the provider is no longer needed.
   */
  fun cleanup() {
  stopLocationUpdates()
  }
}
