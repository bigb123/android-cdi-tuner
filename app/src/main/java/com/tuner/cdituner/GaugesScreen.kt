package com.tuner.cdituner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuner.cdituner.ui.theme.LocalGaugeColors

/**
 * Main gauges dashboard screen for motorcycle riding.
 * Features a large RPM gauge, GPS speed gauge, and smaller voltage/timing gauges.
 * Designed for easy visibility while riding.
 * Uses theme colors from LocalGaugeColors for day/night mode support.
 */
@Composable
fun GaugesScreen(
  cdiData: CdiReceivedMessageDecoder?,
  speedKmh: Float = 0f,
  hasGpsFix: Boolean = false,
  batterySaverEnabled: Boolean = false,
  onBatterySaverChanged: (Boolean) -> Unit = {},
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
    // Top row: RPM and Speed gauges side by side
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Large RPM Gauge
      GaugeView(
        value = rpm,
        minValue = 0f,
        maxValue = 12000f,
        label = "ENGINE",
        unit = "RPM",
        modifier = Modifier.padding(4.dp),
        size = 180.dp,
        arcColor = gaugeColors.rpmArc,
        warningThreshold = 8000f,
        dangerThreshold = 10000f,
        decimalPlaces = 0
      )

      // GPS Speed Gauge (greyed out when battery saver is on)
      GaugeView(
        value = if (batterySaverEnabled) 0f else speedKmh,
        minValue = 0f,
        maxValue = 200f,
        label = if (batterySaverEnabled) "GPS OFF" else if (hasGpsFix) "GPS SPEED" else "NO GPS",
        unit = "km/h",
        modifier = Modifier
          .padding(4.dp)
          .alpha(if (batterySaverEnabled) 0.3f else 1f),
        size = 180.dp,
        arcColor = if (batterySaverEnabled) Color.Gray else Color(0xFF4CAF50), // Green for speed, grey when off
        warningThreshold = 120f,
        dangerThreshold = 160f,
        decimalPlaces = 0
      )
    }

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

    // Battery Saver Switch and Status indicators at bottom
    Column(
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Battery Saver Toggle
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.padding(vertical = 8.dp)
      ) {
        Text(
          text = "🔋 Battery Saver",
          color = gaugeColors.labelText,
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
          checked = batterySaverEnabled,
          onCheckedChange = onBatterySaverChanged,
          colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF4CAF50),
            checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.5f)
          )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = if (batterySaverEnabled) "GPS OFF" else "GPS ON",
          color = if (batterySaverEnabled) gaugeColors.labelText.copy(alpha = 0.7f) else Color(0xFF4CAF50),
          fontSize = 12.sp
        )
      }

      if (cdiData == null) {
        Text(
          text = "Waiting for CDI connection...",
          color = gaugeColors.labelText,
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium
        )
      }
      if (!batterySaverEnabled && !hasGpsFix) {
        Text(
          text = "Acquiring GPS signal...",
          color = gaugeColors.labelText.copy(alpha = 0.7f),
          fontSize = 12.sp
        )
      }
    }
  }
}
