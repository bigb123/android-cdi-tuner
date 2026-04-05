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

    // Charts area
    if (dataPoints.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = "Press Record to start logging data",
          color = gaugeColors.labelText,
          fontSize = 16.sp
        )
      }
    } else {
      // RPM Chart
      SingleMetricChart(
        dataPoints = dataPoints,
        viewWindowSeconds = viewWindowSeconds,
        scrollOffset = scrollOffset,
        onScroll = { delta ->
          val maxScroll = (totalDuration - viewWindowSeconds).coerceAtLeast(0f)
          scrollOffset = (scrollOffset - delta).coerceIn(0f, maxScroll)
        },
        label = "RPM",
        unit = "rpm",
        color = Color(0xFFE53935),
        getValue = { it.rpm.toFloat() },
        currentValue = dataPoints.lastOrNull()?.rpm?.toString() ?: "--",
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
      )

      // Timing Chart
      SingleMetricChart(
        dataPoints = dataPoints,
        viewWindowSeconds = viewWindowSeconds,
        scrollOffset = scrollOffset,
        onScroll = { delta ->
          val maxScroll = (totalDuration - viewWindowSeconds).coerceAtLeast(0f)
          scrollOffset = (scrollOffset - delta).coerceIn(0f, maxScroll)
        },
        label = "Timing",
        unit = "°",
        color = Color(0xFF2196F3),
        getValue = { it.timingAngle },
        currentValue = dataPoints.lastOrNull()?.timingAngle?.let { "%.1f".format(it) } ?: "--",
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
      )

      // Speed Chart
      SingleMetricChart(
        dataPoints = dataPoints,
        viewWindowSeconds = viewWindowSeconds,
        scrollOffset = scrollOffset,
        onScroll = { delta ->
          val maxScroll = (totalDuration - viewWindowSeconds).coerceAtLeast(0f)
          scrollOffset = (scrollOffset - delta).coerceIn(0f, maxScroll)
        },
        label = "Speed",
        unit = "km/h",
        color = Color(0xFF4CAF50),
        getValue = { it.speedKmh },
        currentValue = dataPoints.lastOrNull()?.speedKmh?.let { "%.0f".format(it) } ?: "--",
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
      )
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
private fun SingleMetricChart(
  dataPoints: List<LogDataPoint>,
  viewWindowSeconds: Float,
  scrollOffset: Float,
  onScroll: (Float) -> Unit,
  label: String,
  unit: String,
  color: Color,
  getValue: (LogDataPoint) -> Float,
  currentValue: String,
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current

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

  // Calculate max value for normalization
  val maxValue = dataPoints.maxOfOrNull { getValue(it) }?.coerceAtLeast(1f) ?: 100f

  Column(modifier = modifier.padding(vertical = 2.dp)) {
    // Label row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) {
          drawCircle(color = color, radius = size.minDimension / 2)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = label,
          color = gaugeColors.labelText,
          fontSize = 11.sp,
          fontWeight = FontWeight.Medium
        )
      }
      Text(
        text = "$currentValue $unit",
        color = gaugeColors.valueText,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold
      )
    }

    // Chart canvas
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .pointerInput(Unit) {
          detectHorizontalDragGestures { _, dragAmount ->
            val secondsPerPixel = viewWindowSeconds / size.width
            onScroll(dragAmount * secondsPerPixel)
          }
        }
    ) {
      val chartWidth = size.width
      val chartHeight = size.height
      val padding = 4f

      val plotWidth = chartWidth - padding * 2
      val plotHeight = chartHeight - padding * 2

      // Draw background grid
      val gridColor = gaugeColors.arcBackground

      // Horizontal grid lines (3 lines)
      for (i in 0..2) {
        val y = padding + (plotHeight * i / 2)
        drawLine(
          color = gridColor,
          start = Offset(padding, y),
          end = Offset(chartWidth - padding, y),
          strokeWidth = 1f
        )
      }

      // Draw the line
      if (visiblePoints.size >= 2) {
        val path = Path()
        visiblePoints.forEachIndexed { index, point ->
          val relativeTime = (point.timestamp - firstTimestamp) / 1000f
          val x = padding + ((relativeTime - startTime) / viewWindowSeconds) * plotWidth
          val value = getValue(point)
          val y = padding + plotHeight - (value / maxValue) * plotHeight

          if (index == 0) {
            path.moveTo(x, y)
          } else {
            path.lineTo(x, y)
          }
        }
        drawPath(path, color, style = Stroke(width = 2f))
      }
    }
  }
}
