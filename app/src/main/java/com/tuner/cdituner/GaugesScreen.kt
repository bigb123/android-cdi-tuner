package com.tuner.cdituner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Main gauges dashboard screen for motorcycle riding.
 * Features a large RPM gauge and smaller voltage/timing gauges.
 * Designed for easy visibility while riding.
 */
@Composable
fun GaugesScreen(
  cdiData: CdiReceivedMessageDecoder?,
  modifier: Modifier = Modifier
) {
  val rpm = cdiData?.rpm?.toFloat() ?: 0f
  val voltage = cdiData?.cdiVoltage ?: 0f
  val timing = cdiData?.timingAngle ?: 0f

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0A0A0A))
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.SpaceEvenly
  ) {
    // Large RPM Gauge - Main focus for riding
    GaugeView(
      value = rpm,
      minValue = 0f,
      maxValue = 12000f,
      label = "ENGINE",
      unit = "RPM",
      modifier = Modifier.padding(8.dp),
      size = 280.dp,
      arcColor = Color(0xFF00FF00), // Bright green
      warningThreshold = 9000f,
      dangerThreshold = 11000f,
      decimalPlaces = 0
    )

    // Secondary gauges row
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Voltage gauge
      SmallGaugeView(
        value = voltage,
        minValue = 0f,
        maxValue = 20f,
        label = "CDI VOLTAGE",
        unit = "V",
        arcColor = Color(0xFF00AAFF), // Blue
        warningLow = 10f,
        warningHigh = 16f,
        decimalPlaces = 1
      )

      // Timing angle gauge
      SmallGaugeView(
        value = timing,
        minValue = 0f,
        maxValue = 60f,
        label = "TIMING",
        unit = "°",
        arcColor = Color(0xFFFFAA00), // Orange
        warningHigh = 50f,
        decimalPlaces = 1
      )
    }

    // Connection status indicator at bottom
    if (cdiData == null) {
      Text(
        text = "Waiting for CDI data...",
        color = Color(0xFF666666),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
      )
    }
  }
}
