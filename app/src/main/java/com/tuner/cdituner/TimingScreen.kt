package com.tuner.cdituner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
 * @param onPointClick Callback when a timing point is clicked (index, point)
 */
@Composable
fun TimingScreen(
  timingMap: List<TimingPoint>?,
  statusMessage: String?,
  onRefresh: () -> Unit,
  onPointClick: (Int, TimingPoint) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current
  
  // Determine if we're loading
  val isLoading = statusMessage?.contains("Reading") == true
  
  // Track selected point index - shared between graph and table
  val selectedPointIndex = remember { mutableStateOf<Int?>(null) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(gaugeColors.gaugeBackground)
      .padding(4.dp)
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
      // Graph spans full width (no horizontal padding) for better readability
      TimingCurveGraph(
        timingCurve = timingMap,
        selectedIndex = selectedPointIndex.value,
        onPointClick = { index, point ->
          selectedPointIndex.value = index
          onPointClick(index, point)
        },
        onDeselect = { selectedPointIndex.value = null },
        modifier = Modifier
          .fillMaxWidth()
          .height(300.dp)
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
        selectedIndex = selectedPointIndex.value,
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
 * Points are clickable - tap on a point to select it.
 * Supports horizontal zoom (pinch) and pan (drag) on X-axis.
 *
 * @param timingCurve List of timing points to display
 * @param selectedIndex Currently selected point index (null if none)
 * @param onPointClick Callback when a point is clicked (index, point)
 * @param onDeselect Callback when user taps outside any point
 * @param modifier Modifier for the canvas
 */
@Composable
fun TimingCurveGraph(
  timingCurve: List<TimingPoint>,
  selectedIndex: Int? = null,
  onPointClick: (Int, TimingPoint) -> Unit = { _, _ -> },
  onDeselect: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current
  val textMeasurer = rememberTextMeasurer()
  
  val lineColor = gaugeColors.timingArc
  val gridColor = gaugeColors.labelText.copy(alpha = 0.2f)
  val axisColor = gaugeColors.labelText.copy(alpha = 0.6f)
  val textColor = gaugeColors.labelText
  val selectedColor = Color(0xFFFFD700) // Gold color for selected point
  
  // Chart bounds - 16000 RPM max to accommodate all 16 points
  val maxRpm = 16000f
  val maxTiming = 50f
  
  // Store calculated point positions for hit testing
  val pointPositions = remember { mutableStateOf<List<Offset>>(emptyList()) }
  
  // Zoom and pan state for X-axis
  // zoom: 1.0 = fit all, 2.0 = 2x zoom, etc. Max 4x zoom
  val zoomX = remember { mutableFloatStateOf(1f) }
  // panX: offset in RPM units (0 = start at 0 RPM)
  val panX = remember { mutableFloatStateOf(0f) }

  Canvas(
    modifier = modifier
      .clipToBounds()
      // Combined gesture handler for tap, zoom, and pan
      .pointerInput(timingCurve) {
        awaitEachGesture {
          val firstDown = awaitFirstDown(requireUnconsumed = false)
          val startPosition = firstDown.position
          var totalDragDistance = 0f
          var isMultiTouch = false
          var hasMoved = false
          
          do {
            val event = awaitPointerEvent()
            val pointerCount = event.changes.count { it.pressed }
            
            // Check if this is a multi-touch gesture (pinch zoom)
            if (pointerCount >= 2) {
              isMultiTouch = true
              val zoom = event.calculateZoom()
              val centroid = event.calculateCentroid(useCurrent = true)
              
              if (zoom != 1f) {
                // Calculate new zoom (clamp between 1x and 4x)
                val newZoom = (zoomX.floatValue * zoom).coerceIn(1f, 4f)
                
                // Adjust pan to keep the centroid point stable
                val chartLeft = 8.dp.toPx()  // Must match drawing code
                val chartWidth = size.width - chartLeft - 8.dp.toPx()
                val visibleRpmRange = maxRpm / zoomX.floatValue
                val centroidRpm = panX.floatValue + ((centroid.x - chartLeft) / chartWidth) * visibleRpmRange
                
                val newVisibleRpmRange = maxRpm / newZoom
                val newPanX = centroidRpm - ((centroid.x - chartLeft) / chartWidth) * newVisibleRpmRange
                
                zoomX.floatValue = newZoom
                panX.floatValue = newPanX.coerceIn(0f, maxRpm - newVisibleRpmRange)
              }
              
              event.changes.forEach { it.consume() }
            } else if (pointerCount == 1) {
              // Single finger - could be tap or pan
              val change = event.changes.first()
              if (change.positionChanged()) {
                val dragX = change.position.x - change.previousPosition.x
                totalDragDistance += kotlin.math.abs(dragX)
                
                // Only pan if we've moved enough (prevents accidental pan on tap)
                if (totalDragDistance > 8.dp.toPx()) {
                  hasMoved = true
                  val chartLeft = 8.dp.toPx()  // Must match drawing code
                  val chartWidth = size.width - chartLeft - 8.dp.toPx()
                  val visibleRpmRange = maxRpm / zoomX.floatValue
                  val rpmPerPixel = visibleRpmRange / chartWidth
                  
                  // Pan in opposite direction of drag
                  val newPanX = panX.floatValue - dragX * rpmPerPixel
                  panX.floatValue = newPanX.coerceIn(0f, maxRpm - visibleRpmRange)
                  
                  change.consume()
                }
              }
            }
          } while (event.changes.any { it.pressed })
          
          // If it was a tap (no significant movement and single touch), handle point selection
          if (!hasMoved && !isMultiTouch) {
            val touchRadius = 24.dp.toPx()
            val positions = pointPositions.value
            var pointClicked = false
            
            positions.forEachIndexed { index, pointOffset ->
              if (!pointClicked) {
                val distance = kotlin.math.sqrt(
                  (startPosition.x - pointOffset.x) * (startPosition.x - pointOffset.x) +
                  (startPosition.y - pointOffset.y) * (startPosition.y - pointOffset.y)
                )
                if (distance <= touchRadius) {
                  onPointClick(index, timingCurve[index])
                  pointClicked = true
                }
              }
            }
            // Tap was not on any point - deselect
            if (!pointClicked) {
              onDeselect()
            }
          }
        }
      }
  ) {
    val chartLeft = 24.dp.toPx()  // Leave space for Y-axis labels
    val chartRight = size.width - 8.dp.toPx()
    val chartTop = 16.dp.toPx()
    val chartBottom = size.height - 36.dp.toPx()
    val chartWidth = chartRight - chartLeft
    val chartHeight = chartBottom - chartTop
    
    // Calculate visible RPM range based on zoom
    val visibleRpmRange = maxRpm / zoomX.floatValue
    val minVisibleRpm = panX.floatValue
    val maxVisibleRpm = minVisibleRpm + visibleRpmRange
    
    // Helper function to convert RPM to X coordinate
    fun rpmToX(rpm: Float): Float {
      return chartLeft + ((rpm - minVisibleRpm) / visibleRpmRange) * chartWidth
    }

    // Draw grid lines - dynamically adjust based on zoom level
    val rpmStep = when {
      zoomX.floatValue >= 3f -> 500
      zoomX.floatValue >= 2f -> 1000
      else -> 2000
    }
    val timingSteps = listOf(0f, 10f, 20f, 30f, 40f, 50f)

    // Vertical grid lines (RPM) - only draw visible ones
    var rpm = ((minVisibleRpm / rpmStep).toInt() * rpmStep)
    while (rpm <= maxVisibleRpm) {
      val x = rpmToX(rpm.toFloat())
      if (x >= chartLeft && x <= chartRight) {
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
      rpm += rpmStep
    }

    // Horizontal grid lines (Timing) - these don't change with zoom
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

    // Draw timing curve - clipped to chart area so it doesn't overlap Y-axis
    if (timingCurve.isNotEmpty()) {
      val path = Path()
      var isFirst = true
      
      // Calculate and store all point positions for hit testing
      val positions = mutableListOf<Offset>()

      timingCurve.forEach { point ->
        val x = rpmToX(point.rpm.toFloat())
        val y = chartBottom - (point.timingDegrees / maxTiming) * chartHeight
        positions.add(Offset(x, y))

        if (isFirst) {
          path.moveTo(x, y)
          isFirst = false
        } else {
          path.lineTo(x, y)
        }
      }
      
      // Store positions for hit testing in pointerInput
      pointPositions.value = positions

      // Clip the curve and points to the chart area (prevents overlapping Y-axis when zoomed)
      clipRect(
        left = chartLeft,
        top = chartTop,
        right = size.width,
        bottom = chartBottom
      ) {
        // Draw the curve line
        drawPath(
          path = path,
          color = lineColor,
          style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw data points (only visible ones)
        positions.forEachIndexed { index, offset ->
          // Only draw points that are within the visible area
          if (offset.x >= chartLeft - 10.dp.toPx() && offset.x <= chartRight + 10.dp.toPx()) {
            val isSelected = selectedIndex == index
            
            // Outer circle (larger when selected)
            drawCircle(
              color = if (isSelected) selectedColor else lineColor,
              radius = if (isSelected) 10.dp.toPx() else 6.dp.toPx(),
              center = offset
            )
            // Inner circle
            drawCircle(
              color = Color.White,
              radius = if (isSelected) 5.dp.toPx() else 3.dp.toPx(),
              center = offset
            )
          }
        }
      }
    }
    
    // Draw zoom indicator if zoomed in
    if (zoomX.floatValue > 1.01f) {
      val zoomText = "%.1fx".format(zoomX.floatValue)
      val zoomTextLayout = textMeasurer.measure(
        text = zoomText,
        style = TextStyle(fontSize = 12.sp, color = textColor.copy(alpha = 0.7f))
      )
      drawText(
        textLayoutResult = zoomTextLayout,
        topLeft = Offset(chartRight - zoomTextLayout.size.width - 4.dp.toPx(), chartTop + 4.dp.toPx())
      )
    }
  }
}

/**
 * Composable that displays the timing curve data in a table format.
 *
 * @param timingCurve List of timing points to display
 * @param selectedIndex Currently selected point index (null if none) - row will be highlighted
 * @param modifier Modifier for the table
 */
@Composable
fun TimingTable(
  timingCurve: List<TimingPoint>,
  selectedIndex: Int? = null,
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current
  val selectedColor = Color(0xFFFFD700) // Gold color for selected row
  
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
      val isSelected = selectedIndex == index
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            when {
              isSelected -> selectedColor.copy(alpha = 0.3f)
              index % 2 == 0 -> Color.Transparent
              else -> gaugeColors.labelText.copy(alpha = 0.05f)
            }
          )
          .padding(vertical = 6.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = "${point.rpm}",
          style = MaterialTheme.typography.bodyMedium,
          color = if (isSelected) selectedColor else gaugeColors.labelText,
          fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
          modifier = Modifier.weight(1f)
        )
        Text(
          text = String.format("%.2f°", point.timingDegrees),
          style = MaterialTheme.typography.bodyMedium,
          color = if (isSelected) selectedColor else gaugeColors.timingArc,
          fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}
