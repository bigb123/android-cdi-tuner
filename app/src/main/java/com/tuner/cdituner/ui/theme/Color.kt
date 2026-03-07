package com.tuner.cdituner.ui.theme

import androidx.compose.ui.graphics.Color

// Default Material colors
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// ============================================
// CDI Tuner Gauge Colors - Night Theme (Dark)
// ============================================
// Background colors - dark for night riding
val GaugeBackgroundNight = Color(0xFF0A0A0A)
val GaugeArcBackgroundNight = Color(0xFF333333)
val GaugeTickColorNight = Color(0xFF666666)

// Text colors - bright for visibility at night
val GaugeValueTextNight = Color(0xFFFFFFFF)
val GaugeUnitTextNight = Color(0xFFAAAAAA)
val GaugeLabelTextNight = Color(0xFF888888)

// Arc colors - bright neon for night visibility
val RpmArcNight = Color(0xFF00FF00)        // Bright green
val VoltageArcNight = Color(0xFF00AAFF)    // Bright blue
val TimingArcNight = Color(0xFFFFAA00)     // Bright orange

// Warning/danger colors
val WarningColorNight = Color(0xFFFFAA00)  // Orange
val DangerColorNight = Color(0xFFFF0000)   // Red
val LowWarningColorNight = Color(0xFFFF6600) // Orange-red

// Terminal colors - night
val TerminalBackgroundNight = Color(0xFF000000)
val TerminalTextNight = Color(0xFF00FF00)  // Classic green terminal
val TerminalHeaderNight = Color(0xFF00FF00)
val TerminalDividerNight = Color(0xFF00FF00)

// ============================================
// CDI Tuner Gauge Colors - Day Theme (Light)
// ============================================
// Background colors - light for daytime visibility
val GaugeBackgroundDay = Color(0xFFF5F5F5)
val GaugeArcBackgroundDay = Color(0xFFDDDDDD)
val GaugeTickColorDay = Color(0xFFAAAAAA)

// Text colors - dark for daytime readability
val GaugeValueTextDay = Color(0xFF1A1A1A)
val GaugeUnitTextDay = Color(0xFF555555)
val GaugeLabelTextDay = Color(0xFF777777)

// Arc colors - saturated for daytime visibility
val RpmArcDay = Color(0xFF00AA00)          // Dark green
val VoltageArcDay = Color(0xFF0077CC)      // Dark blue
val TimingArcDay = Color(0xFFCC7700)       // Dark orange

// Warning/danger colors - same for both themes
val WarningColorDay = Color(0xFFFF8800)    // Orange
val DangerColorDay = Color(0xFFDD0000)     // Red
val LowWarningColorDay = Color(0xFFDD4400) // Orange-red

// Terminal colors - day
val TerminalBackgroundDay = Color(0xFFF0F0F0)
val TerminalTextDay = Color(0xFF006600)    // Dark green
val TerminalHeaderDay = Color(0xFF004400)
val TerminalDividerDay = Color(0xFF008800)
