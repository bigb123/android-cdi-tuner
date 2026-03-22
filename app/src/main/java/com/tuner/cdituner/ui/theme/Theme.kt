package com.tuner.cdituner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Data class holding all gauge-specific colors for the CDI Tuner app.
 */
data class GaugeColors(
  // Background colors
  val gaugeBackground: Color,
  val arcBackground: Color,
  val tickColor: Color,
  
  // Text colors
  val valueText: Color,
  val unitText: Color,
  val labelText: Color,
  
  // Arc colors for different gauges
  val rpmArc: Color,
  val voltageArc: Color,
  val timingArc: Color,
  
  // Warning/danger colors
  val warning: Color,
  val danger: Color,
  val lowWarning: Color,
  
  // Terminal colors
  val terminalBackground: Color,
  val terminalText: Color,
  val terminalHeader: Color,
  val terminalDivider: Color
)

/**
 * Data class holding colors for the ignition timing graph.
 */
data class GraphColors(
  // Selection colors
  val pointSelected: Color,        // Color for selected point circle
  val tableSelectedBackground: Color, // Background for selected table row
  
  // Safe/Unsafe state colors (locked vs unlocked editing)
  val safe: Color,                 // Green - locked, safe state
  val unsafe: Color                // Red - unlocked, editable state
)

// Night/Dark theme graph colors
val NightGraphColors = GraphColors(
  pointSelected = Color(0xFFFFC107),      // Gold for selection
  tableSelectedBackground = Color(0xFF4A4000), // Dark yellow for night mode
  safe = SafeColorNight,
  unsafe = UnsafeColorNight
)

// Day/Light theme graph colors
val DayGraphColors = GraphColors(
  pointSelected = Color(0xFF795B02),      // Brown for selection
  tableSelectedBackground = Color(0xFFFFF5AB), // Light yellow for day mode
  safe = SafeColorDay,
  unsafe = UnsafeColorDay
)

// CompositionLocal for graph colors
val LocalGraphColors = staticCompositionLocalOf { NightGraphColors }

// Night/Dark theme gauge colors
val NightGaugeColors = GaugeColors(
  gaugeBackground = GaugeBackgroundNight,
  arcBackground = GaugeArcBackgroundNight,
  tickColor = GaugeTickColorNight,
  valueText = GaugeValueTextNight,
  unitText = GaugeUnitTextNight,
  labelText = GaugeLabelTextNight,
  rpmArc = RpmArcNight,
  voltageArc = VoltageArcNight,
  timingArc = TimingArcNight,
  warning = WarningColorNight,
  danger = DangerColorNight,
  lowWarning = LowWarningColorNight,
  terminalBackground = TerminalBackgroundNight,
  terminalText = TerminalTextNight,
  terminalHeader = TerminalHeaderNight,
  terminalDivider = TerminalDividerNight,
)

// Day/Light theme gauge colors
val DayGaugeColors = GaugeColors(
  gaugeBackground = GaugeBackgroundDay,
  arcBackground = GaugeArcBackgroundDay,
  tickColor = GaugeTickColorDay,
  valueText = GaugeValueTextDay,
  unitText = GaugeUnitTextDay,
  labelText = GaugeLabelTextDay,
  rpmArc = RpmArcDay,
  voltageArc = VoltageArcDay,
  timingArc = TimingArcDay,
  warning = WarningColorDay,
  danger = DangerColorDay,
  lowWarning = LowWarningColorDay,
  terminalBackground = TerminalBackgroundDay,
  terminalText = TerminalTextDay,
  terminalHeader = TerminalHeaderDay,
  terminalDivider = TerminalDividerDay
)

// CompositionLocal for gauge colors - accessible throughout the app
val LocalGaugeColors = staticCompositionLocalOf { NightGaugeColors }

private val DarkColorScheme = darkColorScheme(
  primary = Purple80,
  secondary = PurpleGrey80,
  tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
  primary = Purple40,
  secondary = PurpleGrey40,
  tertiary = Pink40
)

@Composable
fun CDITunerTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false, // Disabled to use our custom gauge colors
  content: @Composable () -> Unit
) {
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }
  
  // Select colors based on system theme
  val gaugeColors = if (darkTheme) NightGaugeColors else DayGaugeColors
  val graphColors = if (darkTheme) NightGraphColors else DayGraphColors

  CompositionLocalProvider(
    LocalGaugeColors provides gaugeColors,
    LocalGraphColors provides graphColors
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
    )
  }
}
