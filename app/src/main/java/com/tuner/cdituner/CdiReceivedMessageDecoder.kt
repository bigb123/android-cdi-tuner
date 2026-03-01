package com.tuner.cdituner

data class CdiReceivedMessageDecoder(
  val rpm: Int = 0,
  val batteryVoltage: Float = 0.0f,
  val statusByte: Int = 0,
  val timingByte: Int = 0,
)