package com.tuner.cdituner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuner.cdituner.ui.theme.LocalGaugeColors

/**
 * Data class representing a single point in the ignition timing curve.
 * @param rpm Engine RPM value
 * @param timingRaw Raw timing value from CDI (degrees × 100)
 */
data class TimingPoint(
  val rpm: Int,
  val timingRaw: Int
) {
  /** Timing angle in degrees BTDC (raw value / 100) */
  val timingDegrees: Float get() = timingRaw / 100f
}

/**
 * Timing curve screen that displays the ignition map as a graph.
 * Shows RPM on X-axis and Timing Angle (degrees BTDC) on Y-axis.
 * The CDI supports 16 timing points from 1000 to 16000 RPM.
 * Also displays a table of the timing values below the graph.
 * 
 * @param timingMap List of timing points read from CDI (null if not yet loaded)
 * @param statusMessage Status message to display (loading, error, etc.)
 * @param onRefresh Callback to force refresh timing map from CDI
 */
@Composable
fun TimingScreen(
  timingMap: List<TimingPoint>?,
  statusMessage: String?,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current
  
  // Determine if we're loading
  val isLoading = statusMessage?.contains("Reading") == true

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(gaugeColors.gaugeBackground)
      .padding(16.dp)
      .verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Title row with refresh button
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "Ignition Timing Curve",
        style = MaterialTheme.typography.headlineSmall,
        color = gaugeColors.labelText,
        fontWeight = FontWeight.Bold
      )
      
      // Refresh button
      Button(
        onClick = onRefresh,
        enabled = !isLoading
      ) {
        Text(if (isLoading) "Reading..." else "🔄 Refresh")
      }
    }

    // Show loading indicator or content
    if (isLoading && timingMap == null) {
      // Loading state - show spinner
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(300.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          CircularProgressIndicator(color = gaugeColors.timingArc)
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = statusMessage ?: "Loading...",
            style = MaterialTheme.typography.bodyMedium,
            color = gaugeColors.labelText
          )
        }
      }
    } else if (timingMap != null && timingMap.isNotEmpty()) {
      // Data loaded - show graph and table
      TimingCurveGraph(
        timingCurve = timingMap,
        modifier = Modifier
          .fillMaxWidth()
          .height(300.dp)
          .padding(8.dp)
      )

      Spacer(modifier = Modifier.height(24.dp))

      // Timing Table
      Text(
        text = "Timing Map Data",
        style = MaterialTheme.typography.titleMedium,
        color = gaugeColors.labelText,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
      )

      TimingTable(
        timingCurve = timingMap,
        modifier = Modifier.fillMaxWidth()
      )
    } else {
      // No data yet - show placeholder
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(300.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "📡",
            fontSize = 48.sp
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = statusMessage ?: "Connect to CDI to read timing map",
            style = MaterialTheme.typography.bodyLarge,
            color = gaugeColors.labelText
          )
        }
      }
    }
    
    Spacer(modifier = Modifier.height(16.dp))
    
    // Status text at bottom
    statusMessage?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodyMedium,
        color = if (it.contains("Error") || it.contains("failed")) 
          MaterialTheme.colorScheme.error 
        else 
          gaugeColors.labelText.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 8.dp)
      )
    }
  }
}

/**
 * Composable that draws the timing curve as a line graph.
 */
@Composable
fun TimingCurveGraph(
  timingCurve: List<TimingPoint>,
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current
  val textMeasurer = rememberTextMeasurer()
  
  val lineColor = gaugeColors.timingArc
  val gridColor = gaugeColors.labelText.copy(alpha = 0.2f)
  val axisColor = gaugeColors.labelText.copy(alpha = 0.6f)
  val textColor = gaugeColors.labelText
  
  // Chart bounds - 16000 RPM max to accommodate all 16 points
  val maxRpm = 16000f
  val maxTiming = 50f

  Canvas(modifier = modifier) {
    val chartLeft = 60.dp.toPx()
    val chartRight = size.width - 20.dp.toPx()
    val chartTop = 20.dp.toPx()
    val chartBottom = size.height - 40.dp.toPx()
    val chartWidth = chartRight - chartLeft
    val chartHeight = chartBottom - chartTop

    // Draw grid lines
    val rpmSteps = listOf(0, 2000, 4000, 6000, 8000, 10000, 12000, 14000, 16000)
    val timingSteps = listOf(0f, 10f, 20f, 30f, 40f, 50f)

    // Vertical grid lines (RPM)
    rpmSteps.forEach { rpm ->
      val x = chartLeft + (rpm / maxRpm) * chartWidth
      drawLine(
        color = gridColor,
        start = Offset(x, chartTop),
        end = Offset(x, chartBottom),
        strokeWidth = 1.dp.toPx()
      )
      // RPM labels
      val label = if (rpm >= 1000) "${rpm / 1000}k" else "$rpm"
      val textLayoutResult = textMeasurer.measure(
        text = label,
        style = TextStyle(fontSize = 10.sp, color = textColor)
      )
      drawText(
        textLayoutResult = textLayoutResult,
        topLeft = Offset(x - textLayoutResult.size.width / 2, chartBottom + 8.dp.toPx())
      )
    }

    // Horizontal grid lines (Timing)
    timingSteps.forEach { timing ->
      val y = chartBottom - (timing / maxTiming) * chartHeight
      drawLine(
        color = gridColor,
        start = Offset(chartLeft, y),
        end = Offset(chartRight, y),
        strokeWidth = 1.dp.toPx()
      )
      // Timing labels
      val label = "${timing.toInt()}°"
      val textLayoutResult = textMeasurer.measure(
        text = label,
        style = TextStyle(fontSize = 10.sp, color = textColor)
      )
      drawText(
        textLayoutResult = textLayoutResult,
        topLeft = Offset(chartLeft - textLayoutResult.size.width - 8.dp.toPx(), y - textLayoutResult.size.height / 2)
      )
    }

    // Draw axes
    drawLine(
      color = axisColor,
      start = Offset(chartLeft, chartTop),
      end = Offset(chartLeft, chartBottom),
      strokeWidth = 2.dp.toPx()
    )
    drawLine(
      color = axisColor,
      start = Offset(chartLeft, chartBottom),
      end = Offset(chartRight, chartBottom),
      strokeWidth = 2.dp.toPx()
    )

    // Draw timing curve
    if (timingCurve.isNotEmpty()) {
      val path = Path()
      var isFirst = true

      timingCurve.forEach { point ->
        val x = chartLeft + (point.rpm / maxRpm) * chartWidth
        val y = chartBottom - (point.timingDegrees / maxTiming) * chartHeight

        if (isFirst) {
          path.moveTo(x, y)
          isFirst = false
        } else {
          path.lineTo(x, y)
        }
      }

      // Draw the curve line
      drawPath(
        path = path,
        color = lineColor,
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
      )

      // Draw data points
      timingCurve.forEach { point ->
        val x = chartLeft + (point.rpm / maxRpm) * chartWidth
        val y = chartBottom - (point.timingDegrees / maxTiming) * chartHeight
        
        // Outer circle
        drawCircle(
          color = lineColor,
          radius = 6.dp.toPx(),
          center = Offset(x, y)
        )
        // Inner circle
        drawCircle(
          color = Color.White,
          radius = 3.dp.toPx(),
          center = Offset(x, y)
        )
      }
    }
  }
}

/**
 * Composable that displays the timing curve data in a table format.
 */
@Composable
fun TimingTable(
  timingCurve: List<TimingPoint>,
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current
  
  Column(modifier = modifier) {
    // Header row
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(gaugeColors.labelText.copy(alpha = 0.1f))
        .padding(vertical = 8.dp, horizontal = 16.dp),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        text = "RPM",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = gaugeColors.labelText,
        modifier = Modifier.weight(1f)
      )
      Text(
        text = "Timing (°BTDC)",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = gaugeColors.labelText,
        modifier = Modifier.weight(1f)
      )
    }
    
    // Data rows
    timingCurve.forEachIndexed { index, point ->
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            if (index % 2 == 0) Color.Transparent 
            else gaugeColors.labelText.copy(alpha = 0.05f)
          )
          .padding(vertical = 6.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = "${point.rpm}",
          style = MaterialTheme.typography.bodyMedium,
          color = gaugeColors.labelText,
          modifier = Modifier.weight(1f)
        )
        Text(
          text = String.format("%.2f°", point.timingDegrees),
          style = MaterialTheme.typography.bodyMedium,
          color = gaugeColors.timingArc,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}
