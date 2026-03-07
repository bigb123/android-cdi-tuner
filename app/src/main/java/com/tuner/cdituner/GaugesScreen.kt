package com.tuner.cdituner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuner.cdituner.ui.theme.LocalGaugeColors

/**
 * Main gauges dashboard screen for motorcycle riding.
 * Features a large RPM gauge and smaller voltage/timing gauges.
 * Designed for easy visibility while riding.
 * Uses theme colors from LocalGaugeColors for day/night mode support.
 */
@Composable
fun GaugesScreen(
  cdiData: CdiReceivedMessageDecoder?,
  modifier: Modifier = Modifier
) {
  val gaugeColors = LocalGaugeColors.current
  
  val rpm = cdiData?.rpm?.toFloat() ?: 0f
  val voltage = cdiData?.cdiVoltage ?: 0f
  val timing = cdiData?.timingAngle ?: 0f

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(gaugeColors.gaugeBackground)
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
      arcColor = gaugeColors.rpmArc,
      warningThreshold = 8000f,
      dangerThreshold = 10000f,
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
        arcColor = gaugeColors.voltageArc,
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
        arcColor = gaugeColors.timingArc,
        warningHigh = 50f,
        decimalPlaces = 1
      )
    }

    // Connection status indicator at bottom
    if (cdiData == null) {
      Text(
        text = "Waiting for CDI connection...",
        color = gaugeColors.labelText,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
      )
    }
  }
}
