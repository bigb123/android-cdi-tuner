package com.tuner.cdituner

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data point for logging RPM, Speed, and Timing over time.
 * Each point represents a snapshot at a specific moment.
 */
data class LogDataPoint(
  val timestamp: Long,      // System.currentTimeMillis()
  val rpm: Int,             // Engine RPM
  val speedKmh: Float,      // GPS speed in km/h
  val timingAngle: Float    // Ignition timing in degrees
)

/**
 * Logs CDI data over time for visualization in charts.
 * 
 * Features:
 * - In-memory storage for fast access (up to 1 hour of data)
 * - Auto-save to file when session ends
 * - Load previous session on startup
 * 
 * Memory usage: ~20 bytes per sample
 * At 500ms intervals: 7,200 samples/hour = ~144 KB
 */
class DataLogger(private val context: Context) {

  companion object {
    // Maximum samples to keep in memory (1 hour at 500ms = 7,200 samples)
    private const val MAX_SAMPLES = 7200
    private const val LOG_FILENAME = "cdi_log.csv"
  }

  // In-memory data storage
  private val _dataPoints = MutableStateFlow<List<LogDataPoint>>(emptyList())
  val dataPoints: StateFlow<List<LogDataPoint>> = _dataPoints.asStateFlow()

  // Recording state
  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  // Session start time (for relative time display)
  private var sessionStartTime: Long = 0L

  /**
   * Start a new recording session.
   * Clears previous data and begins fresh.
   */
  fun startRecording() {
    _dataPoints.value = emptyList()
    sessionStartTime = System.currentTimeMillis()
    _isRecording.value = true
  }

  /**
   * Stop recording and optionally save to file.
   */
  fun stopRecording(saveToFile: Boolean = true) {
    _isRecording.value = false
    if (saveToFile && _dataPoints.value.isNotEmpty()) {
      saveToFile()
    }
  }

  /**
   * Add a new data point to the log.
   * Only adds if recording is active.
   * Automatically trims old data if exceeding MAX_SAMPLES.
   */
  fun addDataPoint(rpm: Int, speedKmh: Float, timingAngle: Float) {
    if (!_isRecording.value) return

    val newPoint = LogDataPoint(
      timestamp = System.currentTimeMillis(),
      rpm = rpm,
      speedKmh = speedKmh,
      timingAngle = timingAngle
    )

    val currentList = _dataPoints.value.toMutableList()
    currentList.add(newPoint)

    // Trim if exceeding max samples
    if (currentList.size > MAX_SAMPLES) {
      _dataPoints.value = currentList.takeLast(MAX_SAMPLES)
    } else {
      _dataPoints.value = currentList
    }
  }

  /**
   * Clear all logged data.
   */
  fun clearData() {
    _dataPoints.value = emptyList()
    sessionStartTime = System.currentTimeMillis()
  }

  /**
   * Get the elapsed time since session start in seconds.
   */
  fun getElapsedSeconds(): Float {
    if (sessionStartTime == 0L) return 0f
    return (System.currentTimeMillis() - sessionStartTime) / 1000f
  }

  /**
   * Get relative time for a data point (seconds since session start).
   */
  fun getRelativeTime(point: LogDataPoint): Float {
    if (sessionStartTime == 0L) return 0f
    return (point.timestamp - sessionStartTime) / 1000f
  }

  /**
   * Save current data to CSV file.
   */
  private fun saveToFile() {
    try {
      val file = File(context.filesDir, LOG_FILENAME)
      val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

      file.bufferedWriter().use { writer ->
        // CSV header
        writer.write("timestamp,datetime,rpm,speed_kmh,timing_angle\n")

        // Data rows
        _dataPoints.value.forEach { point ->
          val datetime = dateFormat.format(Date(point.timestamp))
          writer.write("${point.timestamp},$datetime,${point.rpm},${point.speedKmh},${point.timingAngle}\n")
        }
      }
    } catch (e: Exception) {
      // Silently fail - logging shouldn't crash the app
      e.printStackTrace()
    }
  }

  /**
   * Load previous session data from file.
   */
  fun loadFromFile() {
    try {
      val file = File(context.filesDir, LOG_FILENAME)
      if (!file.exists()) return

      val loadedPoints = mutableListOf<LogDataPoint>()

      file.bufferedReader().useLines { lines ->
        lines.drop(1).forEach { line -> // Skip header
          val parts = line.split(",")
          if (parts.size >= 5) {
            try {
              loadedPoints.add(
                LogDataPoint(
                  timestamp = parts[0].toLong(),
                  rpm = parts[2].toInt(),
                  speedKmh = parts[3].toFloat(),
                  timingAngle = parts[4].toFloat()
                )
              )
            } catch (e: NumberFormatException) {
              // Skip malformed lines
            }
          }
        }
      }

      if (loadedPoints.isNotEmpty()) {
        _dataPoints.value = loadedPoints.takeLast(MAX_SAMPLES)
        sessionStartTime = loadedPoints.first().timestamp
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  /**
   * Delete the saved log file.
   */
  fun deleteLogFile() {
    try {
      val file = File(context.filesDir, LOG_FILENAME)
      if (file.exists()) {
        file.delete()
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  /**
   * Get statistics about current data.
   */
  fun getStats(): LogStats {
    val points = _dataPoints.value
    if (points.isEmpty()) {
      return LogStats(0, 0f, 0, 0, 0f, 0f, 0f, 0f)
    }

    val durationSeconds = (points.last().timestamp - points.first().timestamp) / 1000f

    return LogStats(
      sampleCount = points.size,
      durationSeconds = durationSeconds,
      maxRpm = points.maxOf { it.rpm },
      minRpm = points.minOf { it.rpm },
      maxSpeed = points.maxOf { it.speedKmh },
      minSpeed = points.minOf { it.speedKmh },
      maxTiming = points.maxOf { it.timingAngle },
      minTiming = points.minOf { it.timingAngle }
    )
  }
}

/**
 * Statistics about logged data.
 */
data class LogStats(
  val sampleCount: Int,
  val durationSeconds: Float,
  val maxRpm: Int,
  val minRpm: Int,
  val maxSpeed: Float,
  val minSpeed: Float,
  val maxTiming: Float,
  val minTiming: Float
)
