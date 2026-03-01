package com.tuner.cdituner

/**
 * Utility object for decoding CDI packets from byte arrays.
 * This decoder is used by both Bluetooth and USB connectivity implementations.
 */
object CdiMessageProcessing {

  // A sequence of bytes that we send to CDI to receive a response
  val CDI_MESSAGE = byteArrayOf(0x01, 0xAB.toByte(), 0xAC.toByte(), 0xA1.toByte())
  
  /**
   * Decodes a 22-byte CDI packet into a CdiReceivedMessageDecoder object.
   *
   * @param data The byte array containing the CDI packet
   * @return CdiReceivedMessageDecoder if the packet is valid, null otherwise
   *
   * Packet format:
   * - Byte 0: Start byte (0x03)
   * - Bytes 1-2: RPM (high byte, low byte)
   * - Byte 7: Battery voltage (value / 10.0)
   * - Byte 8: Status byte
   * - Byte 9: Timing byte
   * - Byte 21: End byte (0xA9)
   */
  fun decodeCdiPacket(data: ByteArray): CdiReceivedMessageDecoder? {
    // Validate packet structure
    if (data.size != 22 || data[0] != 0x03.toByte() || data[21] != 0xA9.toByte()) {
      return null
    }

    // Extract RPM from bytes 1-2 (big-endian)
    val rpm = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)

    // Extract battery voltage from byte 7
    val batteryVoltage = (data[7].toInt() and 0xFF) / 10.0f

    // Extract status and timing bytes
    val statusByte = data[8].toInt() and 0xFF
    val timingByte = data[9].toInt() and 0xFF

    return CdiReceivedMessageDecoder(rpm, batteryVoltage, statusByte, timingByte)
  }
}