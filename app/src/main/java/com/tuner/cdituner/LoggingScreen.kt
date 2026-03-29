package com.tuner.cdituner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuner.cdituner.ui.theme.LocalGaugeColors

/**
 * Logging screen with a multi-line time-series chart.
 * Shows RPM, Speed, and Timing over time.
 */
@Composable
fun LoggingScreen(
  dataPoints: List<LogDataPoint>,
  isRecording: Boolean,
  onStartRecording: () -> Unit,
  onStopRecording: () -> Unit,
  onClearData: () -> Unit,
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current

  // View window: how many seconds to show at once
  var viewWindowSeconds by remember { mutableFloatStateOf(60f) }

  // Scroll offset (in seconds from the end)
  var scrollOffset by remember { mutableFloatStateOf(0f) }

  // Calculate time range
  val totalDuration = if (dataPoints.size >= 2) {
    (dataPoints.last().timestamp - dataPoints.first().timestamp) / 1000f
  } else 0f

  // Auto-scroll to end when recording
  LaunchedEffect(dataPoints.size, isRecording) {
    if (isRecording) {
      scrollOffset = 0f
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(gaugeColors.gaugeBackground)
      .padding(8.dp)
  ) {
    // Control buttons row
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Record/Stop button
      Button(
        onClick = {
          if (isRecording) onStopRecording() else onStartRecording()
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = if (isRecording) Color(0xFFE53935) else Color(0xFF4CAF50)
        )
      ) {
        Text(if (isRecording) "⏹ Stop" else "⏺ Record")
      }

      // Status text
      Text(
        text = if (isRecording) "Recording..." else "${dataPoints.size} samples",
        color = gaugeColors.labelText,
        fontSize = 14.sp
      )

      // Clear button
      Button(
        onClick = onClearData,
        enabled = !isRecording && dataPoints.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.error
        )
      ) {
        Text("Clear")
      }
    }

    // Legend
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceEvenly
    ) {
      LegendItem(color = Color(0xFFE53935), label = "RPM", value = dataPoints.lastOrNull()?.rpm?.toString() ?: "--")
      LegendItem(color = Color(0xFF4CAF50), label = "Speed", value = dataPoints.lastOrNull()?.speedKmh?.let { "%.0f".format(it) } ?: "--")
      LegendItem(color = Color(0xFF2196F3), label = "Timing", value = dataPoints.lastOrNull()?.timingAngle?.let { "%.1f°".format(it) } ?: "--")
    }

    // Zoom controls
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("Zoom:", color = gaugeColors.labelText, fontSize = 12.sp)
      Spacer(modifier = Modifier.width(8.dp))

      listOf(30f, 60f, 120f, 300f).forEach { seconds ->
        val label = when (seconds) {
          30f -> "30s"
          60f -> "1m"
          120f -> "2m"
          300f -> "5m"
          else -> "${seconds.toInt()}s"
        }
        FilterChip(
          selected = viewWindowSeconds == seconds,
          onClick = { viewWindowSeconds = seconds },
          label = { Text(label, fontSize = 11.sp) },
          modifier = Modifier.padding(horizontal = 2.dp)
        )
      }
    }

    // Chart area
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .padding(top = 8.dp)
    ) {
      if (dataPoints.isEmpty()) {
        // Empty state
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "Press Record to start logging data",
            color = gaugeColors.labelText,
            fontSize = 16.sp
          )
        }
      } else {
        // Chart
        MultiLineChart(
          dataPoints = dataPoints,
          viewWindowSeconds = viewWindowSeconds,
          scrollOffset = scrollOffset,
          onScroll = { delta ->
            val maxScroll = (totalDuration - viewWindowSeconds).coerceAtLeast(0f)
            scrollOffset = (scrollOffset - delta).coerceIn(0f, maxScroll)
          },
          modifier = Modifier.fillMaxSize()
        )
      }
    }

    // Time info
    if (dataPoints.isNotEmpty()) {
      Text(
        text = "Duration: %.1f sec | Showing: %.0fs window".format(totalDuration, viewWindowSeconds),
        color = gaugeColors.labelText.copy(alpha = 0.7f),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 4.dp)
      )
    }
  }
}

@Composable
private fun LegendItem(color: Color, label: String, value: String) {
  val gaugeColors = LocalGaugeColors.current

  Row(
    verticalAlignment = Alignment.CenterVertically
  ) {
    Canvas(modifier = Modifier.size(12.dp)) {
      drawCircle(color = color, radius = size.minDimension / 2)
    }
    Spacer(modifier = Modifier.width(4.dp))
    Text(
      text = "$label: $value",
      color = gaugeColors.valueText,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium
    )
  }
}

@Composable
private fun MultiLineChart(
  dataPoints: List<LogDataPoint>,
  viewWindowSeconds: Float,
  scrollOffset: Float,
  onScroll: (Float) -> Unit,
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current

  // Colors for each line
  val rpmColor = Color(0xFFE53935)      // Red
  val speedColor = Color(0xFF4CAF50)    // Green
  val timingColor = Color(0xFF2196F3)   // Blue

  // Calculate visible time range
  val firstTimestamp = dataPoints.first().timestamp
  val lastTimestamp = dataPoints.last().timestamp
  val totalDuration = (lastTimestamp - firstTimestamp) / 1000f

  // Visible window
  val endTime = totalDuration - scrollOffset
  val startTime = (endTime - viewWindowSeconds).coerceAtLeast(0f)

  // Filter visible points
  val visiblePoints = dataPoints.filter { point ->
    val relativeTime = (point.timestamp - firstTimestamp) / 1000f
    relativeTime >= startTime && relativeTime <= endTime
  }

  // Calculate max values for normalization
  val maxRpm = dataPoints.maxOfOrNull { it.rpm }?.toFloat()?.coerceAtLeast(1000f) ?: 12000f
  val maxSpeed = dataPoints.maxOfOrNull { it.speedKmh }?.coerceAtLeast(10f) ?: 200f
  val maxTiming = dataPoints.maxOfOrNull { it.timingAngle }?.coerceAtLeast(10f) ?: 60f

  Canvas(
    modifier = modifier
      .pointerInput(Unit) {
        detectHorizontalDragGestures { _, dragAmount ->
          // Convert drag to time offset
          val secondsPerPixel = viewWindowSeconds / size.width
          onScroll(dragAmount * secondsPerPixel)
        }
      }
  ) {
    val chartWidth = size.width
    val chartHeight = size.height
    val padding = 40f

    val plotWidth = chartWidth - padding * 2
    val plotHeight = chartHeight - padding * 2

    // Draw grid lines
    val gridColor = gaugeColors.arcBackground

    // Horizontal grid lines (5 lines)
    for (i in 0..4) {
      val y = padding + (plotHeight * i / 4)
      drawLine(
        color = gridColor,
        start = Offset(padding, y),
        end = Offset(chartWidth - padding, y),
        strokeWidth = 1f
      )
    }

    // Vertical grid lines (time markers)
    val timeStep = when {
      viewWindowSeconds <= 60 -> 10f
      viewWindowSeconds <= 120 -> 20f
      else -> 60f
    }

    var t = (startTime / timeStep).toInt() * timeStep
    while (t <= endTime) {
      if (t >= startTime) {
        val x = padding + ((t - startTime) / viewWindowSeconds) * plotWidth
        drawLine(
          color = gridColor,
          start = Offset(x, padding),
          end = Offset(x, chartHeight - padding),
          strokeWidth = 1f
        )
      }
      t += timeStep
    }

    // Draw lines for each metric
    if (visiblePoints.size >= 2) {
      // RPM line
      val rpmPath = Path()
      visiblePoints.forEachIndexed { index, point ->
        val relativeTime = (point.timestamp - firstTimestamp) / 1000f
        val x = padding + ((relativeTime - startTime) / viewWindowSeconds) * plotWidth
        val y = padding + plotHeight - (point.rpm / maxRpm) * plotHeight

        if (index == 0) {
          rpmPath.moveTo(x, y)
        } else {
          rpmPath.lineTo(x, y)
        }
      }
      drawPath(rpmPath, rpmColor, style = Stroke(width = 2f))

      // Speed line
      val speedPath = Path()
      visiblePoints.forEachIndexed { index, point ->
        val relativeTime = (point.timestamp - firstTimestamp) / 1000f
        val x = padding + ((relativeTime - startTime) / viewWindowSeconds) * plotWidth
        val y = padding + plotHeight - (point.speedKmh / maxSpeed) * plotHeight

        if (index == 0) {
          speedPath.moveTo(x, y)
        } else {
          speedPath.lineTo(x, y)
        }
      }
      drawPath(speedPath, speedColor, style = Stroke(width = 2f))

      // Timing line
      val timingPath = Path()
      visiblePoints.forEachIndexed { index, point ->
        val relativeTime = (point.timestamp - firstTimestamp) / 1000f
        val x = padding + ((relativeTime - startTime) / viewWindowSeconds) * plotWidth
        val y = padding + plotHeight - (point.timingAngle / maxTiming) * plotHeight

        if (index == 0) {
          timingPath.moveTo(x, y)
        } else {
          timingPath.lineTo(x, y)
        }
      }
      drawPath(timingPath, timingColor, style = Stroke(width = 2f))
    }

    // Draw axis labels
    // Y-axis labels would require DrawScope.drawText which needs more setup
    // For simplicity, we rely on the legend showing current values
  }
}
