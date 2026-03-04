package com.tuner.cdituner

data class CdiReceivedMessageDecoder(
  val rpm: Int = 0,
  val cdiVoltage: Float = 0.0f,
  val timingAngle: Float = 0.0f
)