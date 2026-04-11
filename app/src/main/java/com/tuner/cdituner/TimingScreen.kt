package com.tuner.cdituner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuner.cdituner.ui.theme.LocalGaugeColors
import com.tuner.cdituner.ui.theme.LocalGraphColors
import kotlinx.coroutines.launch

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
 * Helper data class to store chart dimensions for coordinate conversion.
 */
private data class ChartDimensions(
  val chartLeft: Float,
  val chartRight: Float,
  val chartTop: Float,
  val chartBottom: Float,
  val chartWidth: Float,
  val chartHeight: Float
)

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
 * @param onPointDrag Callback when a timing point is dragged to new values (index, newRpm, newTimingRaw)
 * @param onLockWithChanges Callback when the chart is locked AND there are pending changes to save (full map for saving to CDI)
 */
@Composable
fun TimingScreen(
  timingMap: List<TimingPoint>?,
  statusMessage: String?,
  currentRpm: Int? = null,
  onRefresh: () -> Unit,
  onPointClick: (Int, TimingPoint) -> Unit = { _, _ -> },
  onPointDrag: (Int, Int, Int) -> Unit = { _, _, _ -> },
  onLockWithChanges: (List<TimingPoint>) -> Unit = { _ -> },
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current
  
  // Determine if we're loading
  val isLoading = statusMessage?.contains("Reading") == true
  
  // Track selected point index - shared between graph and table
  val selectedPointIndex = remember { mutableStateOf<Int?>(null) }
  
  // Lock state - shared between graph and table (locked by default to prevent accidental timing changes)
  val isLocked = remember { mutableStateOf(true) }
  
  // Local editable copy of timing map for immediate visual feedback during dragging
  val editableTimingMap = remember { mutableStateOf<List<TimingPoint>?>(null) }
  
  // History stack for undo functionality - stores previous states of the timing map
  val timingMapHistory = remember { mutableStateOf<List<List<TimingPoint>>>(emptyList()) }
  
  // Track if there are unsaved changes (any edits made since last lock/save)
  val hasUnsavedChanges = remember { mutableStateOf(false) }
  
  // Sync editable map with source when source changes (e.g., after refresh)
  // Also clear history and unsaved changes flag when a fresh map is loaded from CDI
  LaunchedEffect(timingMap) {
    editableTimingMap.value = timingMap?.toList()
    timingMapHistory.value = emptyList()
    hasUnsavedChanges.value = false
  }
  
  // The map to display - use editable copy if available, otherwise source
  val displayMap = editableTimingMap.value ?: timingMap
  
  // LazyListState for programmatic scrolling of the table
  val tableListState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  
  // Scroll table to selected point when selection changes
  LaunchedEffect(selectedPointIndex.value) {
    selectedPointIndex.value?.let { index ->
      coroutineScope.launch {
        // Scroll to make the selected row visible (with some padding)
        tableListState.animateScrollToItem(index)
      }
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(gaugeColors.gaugeBackground)
      .padding(4.dp),
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
    } else if (displayMap != null && displayMap.isNotEmpty()) {
      // Data loaded - show graph and table
      // Graph spans full width (no horizontal padding) for better readability
      TimingCurveGraph(
        timingCurve = displayMap,
        selectedIndex = selectedPointIndex.value,
        currentRpm = currentRpm,
        isLocked = isLocked,
        hasUnsavedChanges = hasUnsavedChanges.value,
        canUndo = timingMapHistory.value.isNotEmpty(),
        onUndo = {
          // Pop the last state from history and restore it
          val history = timingMapHistory.value
          if (history.isNotEmpty()) {
            val previousState = history.last()
            timingMapHistory.value = history.dropLast(1)
            editableTimingMap.value = previousState
            // If we've undone all changes (history is now empty), mark as no unsaved changes
            if (timingMapHistory.value.isEmpty()) {
              hasUnsavedChanges.value = false
            }
          }
        },
        onPointClick = { index, point ->
          selectedPointIndex.value = index
          onPointClick(index, point)
        },
        onDeselect = { selectedPointIndex.value = null },
        onDragStart = {
          // Save current state to history when drag starts (before any changes)
          editableTimingMap.value?.let { currentMap ->
            timingMapHistory.value = timingMapHistory.value + listOf(currentMap)
          }
        },
        onPointDrag = { index, newRpm, newTimingRaw ->
          // Update local editable map for immediate visual feedback
          editableTimingMap.value = editableTimingMap.value?.toMutableList()?.apply {
            this[index] = TimingPoint(newRpm, newTimingRaw)
          }
          // Also notify parent (for potential saving to CDI later)
          onPointDrag(index, newRpm, newTimingRaw)
        },
        onDragEnd = {
          // Mark that we have unsaved changes when drag ends
          hasUnsavedChanges.value = true
        },
        onLock = {
          // When user locks the chart, save the timing map to CDI if there are changes
          if (hasUnsavedChanges.value) {
            editableTimingMap.value?.let { updatedMap ->
              onLockWithChanges(updatedMap)
            }
            // Clear unsaved changes flag and history after saving
            hasUnsavedChanges.value = false
            timingMapHistory.value = emptyList()
          }
        },
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
        timingCurve = displayMap,
        selectedIndex = selectedPointIndex.value,
        isLocked = isLocked.value,
        listState = tableListState,
        onRowClick = { index, point ->
          // Select the point on the graph when table row is clicked
          selectedPointIndex.value = index
          onPointClick(index, point)
        },
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)  // Take remaining space and be independently scrollable
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
 * Composable that draws the timing curve as a line graph with a lock button overlay.
 * Points are clickable - tap on a point to select it.
 * Selected points can be dragged up/down (timing) and left/right (RPM) within bounds,
 * but only when the graph is unlocked.
 * Supports horizontal zoom (pinch) and pan (drag) on X-axis.
 *
 * @param timingCurve List of timing points to display
 * @param selectedIndex Currently selected point index (null if none)
 * @param hasUnsavedChanges Whether there are unsaved changes to the timing map
 * @param onPointClick Callback when a point is clicked (index, point)
 * @param onDeselect Callback when user taps outside any point
 * @param onPointDrag Callback when a point is dragged to new values (index, newRpm, newTimingRaw)
 * @param onLock Callback when the chart is locked (padlock clicked to lock)
 * @param modifier Modifier for the canvas
 */
@Preview
@Composable
fun TimingCurveGraph(
  timingCurve: List<TimingPoint>,
  selectedIndex: Int? = null,
  currentRpm: Int? = null,
  isLocked: MutableState<Boolean> = mutableStateOf(true),
  hasUnsavedChanges: Boolean = false,
  canUndo: Boolean = false,
  onUndo: () -> Unit = {},
  onPointClick: (Int, TimingPoint) -> Unit = { _, _ -> },
  onDeselect: () -> Unit = {},
  onDragStart: () -> Unit = {},
  onPointDrag: (Int, Int, Int) -> Unit = { _, _, _ -> },
  onDragEnd: () -> Unit = {},
  onLock: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current
  val graphColors = LocalGraphColors.current
  val textMeasurer = rememberTextMeasurer()

  // Helper function to get selection color based on lock state
  fun selectionColor(locked: Boolean, alpha: Float = 1f): Color {
    return if (locked) graphColors.safe.copy(alpha = alpha) else graphColors.unsafe.copy(alpha = alpha)
  }

  val lineColor = selectionColor(isLocked.value)
  val gridColor = gaugeColors.labelText.copy(alpha = 0.2f)
  val axisColor = gaugeColors.labelText.copy(alpha = 0.6f)
  val textColor = gaugeColors.labelText
  
  // Chart bounds - 17000 RPM max to allow easy navigation past the last point at 16000 RPM
  val maxRpm = 17000f
  val maxTiming = 50f
  
  // Store calculated point positions for hit testing
  val pointPositions = remember { mutableStateOf<List<Offset>>(emptyList()) }
  
  // Zoom and pan state for both axes
  // zoom: 1.0 = fit all, 2.0 = 2x zoom, etc. Max 4x zoom
  val zoomX = remember { mutableFloatStateOf(1f) }
  val zoomY = remember { mutableFloatStateOf(1f) }
  // panX: offset in RPM units (0 = start at 0 RPM)
  // panY: offset in timing degrees (0 = start at 0 degrees)
  val panX = remember { mutableFloatStateOf(0f) }
  val panY = remember { mutableFloatStateOf(0f) }
  
  // Store chart dimensions for coordinate conversion in gesture handler
  val chartDimensions = remember { mutableStateOf<ChartDimensions?>(null) }
  
  // Use rememberUpdatedState to access latest values inside gesture handler without restarting it
  val currentTimingCurve = rememberUpdatedState(timingCurve)
  val currentSelectedIndex = rememberUpdatedState(selectedIndex)
  val currentOnDragStart = rememberUpdatedState(onDragStart)
  val currentOnPointDrag = rememberUpdatedState(onPointDrag)
  val currentOnDragEnd = rememberUpdatedState(onDragEnd)
  val currentOnPointClick = rememberUpdatedState(onPointClick)
  val currentOnDeselect = rememberUpdatedState(onDeselect)
  val currentIsLocked = rememberUpdatedState(isLocked.value)

  // Use Box to overlay the lock button on top of the canvas
  Box(modifier = modifier) {
    Canvas(
      modifier = Modifier
        .fillMaxSize()
        .clipToBounds()
        // Combined gesture handler for tap, zoom, pan, and point dragging
        // Use Unit as key so it doesn't restart when timingCurve changes during drag
        .pointerInput(Unit) {
          awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            val startPosition = firstDown.position
            var totalDragDistance = 0f
            var isMultiTouch = false
            var hasMoved = false
            var isDraggingPoint = false
            var draggedPointIndex = -1
            var hasSavedHistory = false  // Track if we've saved history for this drag operation
            
            // Check if we're starting a drag on the selected point (only if unlocked)
            // Use currentSelectedIndex.value to get latest value
            val selIdx = currentSelectedIndex.value
            val locked = currentIsLocked.value
            if (selIdx != null && !locked) {
              val positions = pointPositions.value
              if (selIdx < positions.size) {
                val selectedPointPos = positions[selIdx]
                val touchRadius = 30.dp.toPx() // Slightly larger touch area for dragging
                val distance = kotlin.math.sqrt(
                  (startPosition.x - selectedPointPos.x) * (startPosition.x - selectedPointPos.x) +
                  (startPosition.y - selectedPointPos.y) * (startPosition.y - selectedPointPos.y)
                )
                if (distance <= touchRadius) {
                  isDraggingPoint = true
                  draggedPointIndex = selIdx
                }
              }
            }
            
            do {
              val event = awaitPointerEvent()
              val pointerCount = event.changes.count { it.pressed }
              
              // Check if this is a multi-touch gesture (pinch zoom)
              if (pointerCount >= 2) {
                isMultiTouch = true
                isDraggingPoint = false // Cancel point drag on multi-touch
                val zoom = event.calculateZoom()
                val centroid = event.calculateCentroid(useCurrent = true)
                
                if (zoom != 1f) {
                  // Calculate new zoom for both axes (clamp between 1x and 4x)
                  val newZoomX = (zoomX.floatValue * zoom).coerceIn(1f, 4f)
                  val newZoomY = (zoomY.floatValue * zoom).coerceIn(1f, 4f)
                  
                  // Adjust pan to keep the centroid point stable - X axis
                  val chartLeft = 24.dp.toPx()  // Must match drawing code
                  val chartRight = size.width - 16.dp.toPx()
                  val chartTop = 16.dp.toPx()
                  val chartBottom = size.height - 36.dp.toPx()
                  val chartWidth = chartRight - chartLeft
                  val chartHeight = chartBottom - chartTop
                  
                  val visibleRpmRange = maxRpm / zoomX.floatValue
                  val centroidRpm = panX.floatValue + ((centroid.x - chartLeft) / chartWidth) * visibleRpmRange
                  val newVisibleRpmRange = maxRpm / newZoomX
                  val newPanX = centroidRpm - ((centroid.x - chartLeft) / chartWidth) * newVisibleRpmRange
                  
                  // Adjust pan to keep the centroid point stable - Y axis
                  val visibleTimingRange = maxTiming / zoomY.floatValue
                  val centroidTiming = panY.floatValue + ((chartBottom - centroid.y) / chartHeight) * visibleTimingRange
                  val newVisibleTimingRange = maxTiming / newZoomY
                  val newPanY = centroidTiming - ((chartBottom - centroid.y) / chartHeight) * newVisibleTimingRange
                  
                  zoomX.floatValue = newZoomX
                  zoomY.floatValue = newZoomY
                  panX.floatValue = newPanX.coerceIn(0f, maxRpm - newVisibleRpmRange)
                  panY.floatValue = newPanY.coerceIn(0f, maxTiming - newVisibleTimingRange)
                }
                
                event.changes.forEach { it.consume() }
              } else if (pointerCount == 1) {
                val change = event.changes.first()
                if (change.positionChanged()) {
                  val dragX = change.position.x - change.previousPosition.x
                  val dragY = change.position.y - change.previousPosition.y
                  totalDragDistance += kotlin.math.sqrt(dragX * dragX + dragY * dragY)
                  
                  // Only process movement if we've moved enough (prevents accidental moves on tap)
                  if (totalDragDistance > 8.dp.toPx()) {
                  hasMoved = true
                  
                  if (isDraggingPoint && draggedPointIndex >= 0 && !currentIsLocked.value) {
                    // Save history once when drag starts (before any changes)
                    if (!hasSavedHistory) {
                      currentOnDragStart.value()
                      hasSavedHistory = true
                    }
                    
                    // Dragging a selected point - convert position to RPM and timing values
                    val dims = chartDimensions.value
                      val curve = currentTimingCurve.value
                      if (dims != null && curve.isNotEmpty()) {
                        val currentPos = change.position
                        
                        // Convert pixel position to RPM and timing values (using zoom/pan)
                        val visibleRpmRange = maxRpm / zoomX.floatValue
                        val minVisibleRpm = panX.floatValue
                        val visibleTimingRange = maxTiming / zoomY.floatValue
                        val minVisibleTiming = panY.floatValue
                        
                        // Calculate new RPM from X position
                        val newRpm = minVisibleRpm + ((currentPos.x - dims.chartLeft) / dims.chartWidth) * visibleRpmRange
                        
                        // Calculate new timing from Y position (inverted - top is higher timing, with Y zoom/pan)
                        val newTiming = minVisibleTiming + ((dims.chartBottom - currentPos.y) / dims.chartHeight) * visibleTimingRange
                        
                        // First point (1000 RPM) and last point (16000 RPM) have fixed RPM values
                        // User can only change timing (Y-axis) for these boundary points
                        val isFirstPoint = draggedPointIndex == 0
                        val isLastPoint = draggedPointIndex == curve.size - 1
                        
                        // Calculate RPM bounds based on neighboring points (use latest curve data)
                        // First and last points are locked to their RPM values
                        val clampedRpm = when {
                          isFirstPoint -> 1000  // First point locked at 1000 RPM
                          isLastPoint -> 16000  // Last point locked at 16000 RPM
                          else -> {
                            val minRpm = curve[draggedPointIndex - 1].rpm + 100 // At least 100 RPM gap
                            val maxRpmBound = curve[draggedPointIndex + 1].rpm - 100 // At least 100 RPM gap
                            newRpm.toInt().coerceIn(minRpm, maxRpmBound)
                          }
                        }
                        val clampedTiming = (newTiming * 100).toInt().coerceIn(0, 5000) // 0-50 degrees as raw value
                        
                        // Notify parent of the drag (use latest callback)
                        currentOnPointDrag.value(draggedPointIndex, clampedRpm, clampedTiming)
                      }
                      change.consume()
                    } else {
                      // Not dragging a point - pan the chart in both axes
                      val chartLeft = 24.dp.toPx()  // Must match drawing code
                      val chartRight = size.width - 16.dp.toPx()
                      val chartTop = 16.dp.toPx()
                      val chartBottom = size.height - 36.dp.toPx()
                      val chartWidth = chartRight - chartLeft
                      val chartHeight = chartBottom - chartTop
                      
                      val visibleRpmRange = maxRpm / zoomX.floatValue
                      val visibleTimingRange = maxTiming / zoomY.floatValue
                      val rpmPerPixel = visibleRpmRange / chartWidth
                      val timingPerPixel = visibleTimingRange / chartHeight
                      
                      // Pan in opposite direction of drag (X and Y)
                      val newPanX = panX.floatValue - dragX * rpmPerPixel
                      val newPanY = panY.floatValue + dragY * timingPerPixel  // Y is inverted (drag up = increase timing)
                      panX.floatValue = newPanX.coerceIn(0f, maxRpm - visibleRpmRange)
                      panY.floatValue = newPanY.coerceIn(0f, maxTiming - visibleTimingRange)
                      
                      change.consume()
                    }
                  }
                }
              }
            } while (event.changes.any { it.pressed })
            
            // If we were dragging a point and made changes, notify that drag ended
            if (hasSavedHistory && isDraggingPoint) {
              currentOnDragEnd.value()
            }
            
            // If it was a tap (no significant movement and single touch), handle point selection
            if (!hasMoved && !isMultiTouch) {
              val touchRadius = 24.dp.toPx()
              val positions = pointPositions.value
              val curve = currentTimingCurve.value
              var pointClicked = false
              
              positions.forEachIndexed { index, pointOffset ->
                if (!pointClicked && index < curve.size) {
                  val distance = kotlin.math.sqrt(
                    (startPosition.x - pointOffset.x) * (startPosition.x - pointOffset.x) +
                    (startPosition.y - pointOffset.y) * (startPosition.y - pointOffset.y)
                  )
                  if (distance <= touchRadius) {
                    currentOnPointClick.value(index, curve[index])
                    pointClicked = true
                  }
                }
              }
              // Tap was not on any point - deselect
              if (!pointClicked) {
                currentOnDeselect.value()
              }
            }
          }
        }
    ) {
      val chartLeft = 24.dp.toPx()  // Leave space for Y-axis labels
      val chartRight = size.width - 16.dp.toPx()
      val chartTop = 16.dp.toPx()
      val chartBottom = size.height - 36.dp.toPx()
      val chartWidth = chartRight - chartLeft
      val chartHeight = chartBottom - chartTop
      
      // Store chart dimensions for gesture handler to use
      chartDimensions.value = ChartDimensions(
        chartLeft = chartLeft,
        chartRight = chartRight,
        chartTop = chartTop,
        chartBottom = chartBottom,
        chartWidth = chartWidth,
        chartHeight = chartHeight
      )
      
      // Calculate visible ranges based on zoom for both axes
      val visibleRpmRange = maxRpm / zoomX.floatValue
      val minVisibleRpm = panX.floatValue
      val maxVisibleRpm = minVisibleRpm + visibleRpmRange
      
      val visibleTimingRange = maxTiming / zoomY.floatValue
      val minVisibleTiming = panY.floatValue
      val maxVisibleTiming = minVisibleTiming + visibleTimingRange
      
      // Helper function to convert RPM to X coordinate
      fun rpmToX(rpm: Float): Float {
        return chartLeft + ((rpm - minVisibleRpm) / visibleRpmRange) * chartWidth
      }
      
      // Helper function to convert timing degrees to Y coordinate
      fun timingToY(timing: Float): Float {
        return chartBottom - ((timing - minVisibleTiming) / visibleTimingRange) * chartHeight
      }

      // Draw grid lines - dynamically adjust based on zoom level
      val rpmStep = when {
        zoomX.floatValue >= 3f -> 500
        zoomX.floatValue >= 2f -> 1000
        else -> 2000
      }
      val timingStep = when {
        zoomY.floatValue >= 3f -> 2
        zoomY.floatValue >= 2f -> 5
        else -> 10
      }

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
            style = TextStyle(
              fontSize = 16.sp,
              fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
              color = textColor
            )
          )
          drawText(
            textLayoutResult = textLayoutResult,
            topLeft = Offset(x - textLayoutResult.size.width / 2, chartBottom + 8.dp.toPx())
          )
        }
        rpm += rpmStep
      }

      // Horizontal grid lines (Timing) - dynamically adjust based on Y zoom
      var timing = ((minVisibleTiming / timingStep).toInt() * timingStep)
      while (timing <= maxVisibleTiming) {
        val y = timingToY(timing.toFloat())
        if (y >= chartTop && y <= chartBottom) {
          drawLine(
            color = gridColor,
            start = Offset(chartLeft, y),
            end = Offset(chartRight, y),
            strokeWidth = 1.dp.toPx()
          )
          // Timing labels
          val label = "${timing}°"
          val textLayoutResult = textMeasurer.measure(
            text = label,
            style = TextStyle(fontSize = 12.sp, color = textColor)
          )
          drawText(
            textLayoutResult = textLayoutResult,
            topLeft = Offset(chartLeft - textLayoutResult.size.width - 8.dp.toPx(), y - textLayoutResult.size.height / 2)
          )
        }
        timing += timingStep
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

      // Draw 10,000 RPM reference bar (slightly visible orange)
      val rpm10kX = rpmToX(10000f)
      if (rpm10kX >= chartLeft && rpm10kX <= chartRight) {
        drawLine(
          color = Color(0xFFFF9800).copy(alpha = 0.5f),  // Orange with 50% opacity
          start = Offset(rpm10kX, chartTop),
          end = Offset(rpm10kX, chartBottom),
          strokeWidth = 2.dp.toPx(),
          cap = StrokeCap.Round
        )
      }

      // Draw current RPM indicator as a vertical bar UNDER the timing curve
      if (currentRpm != null && currentRpm > 0) {
        val rpmX = rpmToX(currentRpm.toFloat())
        // Only draw if the RPM is within the visible range
        if (rpmX >= chartLeft && rpmX <= chartRight) {
          drawLine(
            color = Color.Blue.copy(alpha = 0.7f),
            start = Offset(rpmX, chartTop),
            end = Offset(rpmX, chartBottom),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
          )
        }
      }

      // Draw timing curve - clipped to chart area so it doesn't overlap Y-axis
      if (timingCurve.isNotEmpty()) {
        val path = Path()
        var isFirst = true
        
        // Calculate and store all point positions for hit testing
        val positions = mutableListOf<Offset>()

        timingCurve.forEach { point ->
          val x = rpmToX(point.rpm.toFloat())
          val y = timingToY(point.timingDegrees)
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

        // Clip the curve and points to the chart area (prevents overlapping axes when zoomed)
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

          // Draw data points (only visible ones in both X and Y)
          positions.forEachIndexed { index, offset ->
            // Only draw points that are within the visible area (both X and Y)
            val inXRange = offset.x >= chartLeft - 10.dp.toPx() && offset.x <= chartRight + 10.dp.toPx()
            val inYRange = offset.y >= chartTop - 10.dp.toPx() && offset.y <= chartBottom + 10.dp.toPx()
            if (inXRange && inYRange) {
              val isSelected = selectedIndex == index
              
              // Outer circle (larger when selected)
              drawCircle(
                color = if (isSelected) graphColors.pointSelected else lineColor,
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

      // Draw zoom indicator if zoomed in (show both X and Y zoom)
      val isZoomedX = zoomX.floatValue > 1.01f
      val isZoomedY = zoomY.floatValue > 1.01f
      if (isZoomedX || isZoomedY) {
        val zoomText = "%.1fx".format(zoomX.floatValue.coerceAtLeast(zoomY.floatValue))
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
    
    // Undo button overlay in top LEFT corner - positioned for right-handed users to avoid accidental clicks
    // Only visible when there's something to undo
    if (canUndo) {
      Box(
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(start = 32.dp, top = 8.dp)
          .background(
            color = graphColors.unsafe.copy(alpha = 0.4f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
          )
          .clickable { onUndo() }
          .padding(8.dp)
      ) {
        Text(
          text = "↩️",
          fontSize = 28.sp
        )
      }
    }
    
    // Warning text overlay at top center when edit mode is active (unlocked)
    // Semi-transparent red text to raise awareness without being too intrusive
    if (!isLocked.value) {
      Text(
        text = if (hasUnsavedChanges) "⚠️ Unsaved changes\nTap 🔓 to save" else "Warning: edit mode active",
        color = Color.Red.copy(alpha = if (hasUnsavedChanges) 0.8f else 0.5f),
        fontSize = 14.sp,
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(top = 4.dp)
      )
    }
    
    // Padlock button overlay in top right corner (offset left to not obscure last graph point)
    // Green background when locked (safe), red background when unlocked (editable/danger)
    // When clicked to lock AND there are unsaved changes, the onLock callback saves the map to CDI
    Box(
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(end = 48.dp, top = 8.dp)
        .background(
          color = selectionColor(isLocked.value, 0.4f),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        )
        .clickable {
          val wasUnlocked = !isLocked.value
          isLocked.value = !isLocked.value
          // If we just locked (was unlocked, now locked), trigger onLock to save changes
          if (wasUnlocked && isLocked.value) {
            onLock()
          }
        }
        .padding(8.dp)
    ) {
      Text(
        text = if (isLocked.value) "🔒" else "🔓",
        fontSize = 28.sp
      )
    }
  }
}

/**
 * Composable that displays the timing curve data in a table format.
 * Uses LazyColumn for independent scrolling within the screen.
 *
 * @param timingCurve List of timing points to display
 * @param selectedIndex Currently selected point index (null if none) - row will be highlighted
 * @param isLocked Whether the chart is locked (affects selection color)
 * @param listState LazyListState for programmatic scrolling (e.g., when chart point is clicked)
 * @param onRowClick Callback when a row is clicked (index, point) - selects the point on the graph
 * @param modifier Modifier for the table
 */
@Composable
fun TimingTable(
  timingCurve: List<TimingPoint>,
  selectedIndex: Int? = null,
  isLocked: Boolean = true,
  listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
  onRowClick: (Int, TimingPoint) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current
  val graphColors = LocalGraphColors.current
  
  // Helper function to get selection color based on lock state
  fun selectionColor(locked: Boolean, alpha: Float = 1f): Color {
    return if (locked) graphColors.safe.copy(alpha = alpha) else graphColors.unsafe.copy(alpha = alpha)
  }

  Column(modifier = modifier) {
    // Header row (fixed, not scrollable)
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

    // Data rows (scrollable independently)
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize()
    ) {
      itemsIndexed(timingCurve) { index, point ->
        val isSelected = selectedIndex == index
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onRowClick(index, point) }
            .background(
              when {
                isSelected -> selectionColor(isLocked, 0.3f)
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
            color = if (isSelected) graphColors.pointSelected else gaugeColors.labelText,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
          )
          Text(
            text = String.format("%.2f°", point.timingDegrees),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) graphColors.pointSelected else gaugeColors.timingArc,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.weight(1f)
          )
        }
      }
    }
  }
}
