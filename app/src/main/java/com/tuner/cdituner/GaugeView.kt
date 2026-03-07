package com.tuner.cdituner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuner.cdituner.ui.theme.LocalGaugeColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * A circular gauge composable that displays a value with an arc indicator.
 * Designed for motorcycle dashboard display - easy to read while riding.
 * Uses theme colors from LocalGaugeColors for day/night mode support.
 */
@Composable
fun GaugeView(
  value: Float,
  minValue: Float,
  maxValue: Float,
  label: String,
  unit: String,
  modifier: Modifier = Modifier,
  size: Dp = 200.dp,
  arcColor: Color? = null, // If null, uses theme default
  warningThreshold: Float? = null,
  dangerThreshold: Float? = null,
  decimalPlaces: Int = 0
) {
  val gaugeColors = LocalGaugeColors.current
  val normalizedValue = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
  
  // Use provided color or default from theme
  val baseArcColor = arcColor ?: gaugeColors.rpmArc
  
  // Determine arc color based on thresholds
  val currentArcColor = when {
    dangerThreshold != null && value >= dangerThreshold -> gaugeColors.danger
    warningThreshold != null && value >= warningThreshold -> gaugeColors.warning
    else -> baseArcColor
  }

  Box(
    modifier = modifier.size(size),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val strokeWidth = size.toPx() * 0.08f
      val arcSize = Size(
        width = this.size.width - strokeWidth,
        height = this.size.height - strokeWidth
      )
      val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
      
      // Start angle: 135 degrees (bottom-left), sweep: 270 degrees
      val startAngle = 135f
      val sweepAngle = 270f
      
      // Draw background arc
      drawArc(
        color = gaugeColors.arcBackground,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
      )
      
      // Draw value arc
      drawArc(
        color = currentArcColor,
        startAngle = startAngle,
        sweepAngle = sweepAngle * normalizedValue,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
      )
      
      // Draw tick marks
      val tickCount = 10
      val tickLength = size.toPx() * 0.08f
      val radius = (this.size.width - strokeWidth) / 2
      val center = Offset(this.size.width / 2, this.size.height / 2)
      
      for (i in 0..tickCount) {
        val angle = Math.toRadians((startAngle + (sweepAngle * i / tickCount)).toDouble())
        val innerRadius = radius - strokeWidth / 2 - tickLength
        val outerRadius = radius - strokeWidth / 2 - 4
        
        val startPoint = Offset(
          x = center.x + (innerRadius * cos(angle)).toFloat(),
          y = center.y + (innerRadius * sin(angle)).toFloat()
        )
        val endPoint = Offset(
          x = center.x + (outerRadius * cos(angle)).toFloat(),
          y = center.y + (outerRadius * sin(angle)).toFloat()
        )
        
        drawLine(
          color = gaugeColors.tickColor,
          start = startPoint,
          end = endPoint,
          strokeWidth = 2f
        )
      }
    }
    
    // Center text display
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      // Value
      Text(
        text = if (decimalPlaces == 0) {
          value.toInt().toString()
        } else {
          String.format("%.${decimalPlaces}f", value)
        },
        color = gaugeColors.valueText,
        fontSize = (size.value * 0.2f).sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
      )
      
      // Unit
      Text(
        text = unit,
        color = gaugeColors.unitText,
        fontSize = (size.value * 0.1f).sp,
        textAlign = TextAlign.Center
      )
      
      // Label
      Text(
        text = label,
        color = gaugeColors.labelText,
        fontSize = (size.value * 0.08f).sp,
        textAlign = TextAlign.Center
      )
    }
  }
}

/**
 * A smaller, simpler gauge for secondary values like voltage and timing.
 * Uses theme colors from LocalGaugeColors for day/night mode support.
 */
@Composable
fun SmallGaugeView(
  value: Float,
  minValue: Float,
  maxValue: Float,
  label: String,
  unit: String,
  modifier: Modifier = Modifier,
  arcColor: Color? = null, // If null, uses theme default
  warningLow: Float? = null,
  warningHigh: Float? = null,
  decimalPlaces: Int = 1
) {
  val gaugeColors = LocalGaugeColors.current
  val normalizedValue = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
  
  // Use provided color or default from theme
  val baseColor = arcColor ?: gaugeColors.voltageArc
  
  // Determine color based on thresholds
  val currentColor = when {
    warningLow != null && value < warningLow -> gaugeColors.lowWarning
    warningHigh != null && value > warningHigh -> gaugeColors.danger
    else -> baseColor
  }

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier.size(120.dp),
      contentAlignment = Alignment.Center
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 12f
        val arcSize = Size(
          width = this.size.width - strokeWidth,
          height = this.size.height - strokeWidth
        )
        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
        
        val startAngle = 135f
        val sweepAngle = 270f
        
        // Background arc
        drawArc(
          color = gaugeColors.arcBackground,
          startAngle = startAngle,
          sweepAngle = sweepAngle,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        
        // Value arc
        drawArc(
          color = currentColor,
          startAngle = startAngle,
          sweepAngle = sweepAngle * normalizedValue,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
      }
      
      Column(
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = String.format("%.${decimalPlaces}f", value),
          color = gaugeColors.valueText,
          fontSize = 24.sp,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = unit,
          color = gaugeColors.unitText,
          fontSize = 12.sp
        )
      }
    }
    
    Text(
      text = label,
      color = gaugeColors.labelText,
      fontSize = 14.sp,
      modifier = Modifier.padding(top = 4.dp)
    )
  }
}
