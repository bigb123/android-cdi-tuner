package com.tuner.cdituner

/**
 * Protocol handler for reading and writing ignition timing maps to/from CDI.
 *
 * READ Protocol (from serial communication analysis):
 * 1. Send ignition map request: 01 06 00 00 ... 07 B8 (64 bytes)
 * 2. CDI responds with page 0: 02 07 00 00 ... B9 (64 bytes)
 * 3. Host echoes page back with modified markers to request next page
 * 4. CDI sends page 1: 02 07 00 01 ... B9
 * 5. Continues for required pages (2 pages for single timing map)
 *
 * WRITE Protocol:
 * 1. Send write init: 01 01 00 00 ... 02 A8 (64 bytes) - tells CDI we want to write
 * 2. CDI responds: 02 01 00 00 ... 03 A9 (64 bytes) - CDI ready to accept
 * 3. Send page 0: 01 02 00 00 data... [checksum] A8 (64 bytes)
 * 4. CDI echoes: 02 02 00 00 data... [checksum] A9 (64 bytes)
 * 5. Send page 1: 01 02 00 01 data... [checksum] A8 (64 bytes)
 * 6. CDI echoes: 02 02 00 01 data... [checksum] A9 (64 bytes)
 * 7. Send end of transmission: 01 03 F4 B2 F8 9E ... 40 A8 (64 bytes)
 * 8. CDI confirms save: 02 03 00 00 ... 05 A9 (64 bytes)
 *
 * Checksum format (last 2 bytes of each 64-byte message):
 * - Byte 62: Sum of bytes 0-61 modulo 256
 * - Byte 63: 0xA8 for write commands (host), 0xA9 for responses (CDI)
 *
 * Data format:
 * - Each page has 58 bytes of data (bytes 4-61)
 * - First 32 bytes: 16 RPM values (16-bit little-endian)
 * - Next 32 bytes: 16 timing values (16-bit little-endian, degrees × 100)
 */
object CdiTimingMapProtocol {

  /** Initial request message to read timing map from CDI */
  val READ_TIMING_MAP_REQUEST = byteArrayOf(
    0x01, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, 0xB8.toByte()
  )

  val WRITE_TIMING_MAP_REQUEST = byteArrayOf(
    0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xa8.toByte()
  )

  val END_OF_TRANSMISSION = byteArrayOf(
    0x01, 0x03, 0xf4.toByte(), 0xb2.toByte(), 0xf8.toByte(), 0x9e.toByte(), 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0xa8.toByte()
  )

  // This message is most probably necessary for compatibility but doesn't do much
  val COMPATIBILITY_MESSAGE = byteArrayOf(
    0x01, 0x04, 0x00, 0x00, 0xe8.toByte(), 0x03, 0xd0.toByte(), 0x07,
    0xb8.toByte(), 0x0b, 0xa0.toByte(), 0x0f, 0x88.toByte(), 0x13, 0x70, 0x17,
    0x58, 0x1b, 0x40, 0x1f, 0x28, 0x23, 0x10, 0x27,
    0xf8.toByte(), 0x2a, 0xe0.toByte(), 0x2e, 0xc8.toByte(), 0x32, 0xb0.toByte(), 0x36,
    0x98.toByte(), 0x3a, 0x80.toByte(), 0x3e, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x4f, 0xa8.toByte()
  )

  val END_OF_TRANSMISSION_COMPATIBILITY_MESSAGE = byteArrayOf(
    0x01, 0x05, 0xf4.toByte(), 0xb2.toByte(), 0xf9.toByte(), 0x9f.toByte(), 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x44, 0xa8.toByte())

  /** Expected page size in bytes */
  const val TIMING_PAGE_SIZE = 64
  const val STATUS_PAGE_SIZE = 22
  const val USEFUL_DATA_SIZE = 58 // From entire page we actually only need interior bytes that are surrounded by header (4 bytes) and footer (2 bytes)

  /** Checksum end markers */
  const val WRITE_END_MARKER: Byte = 0xA8.toByte()
  const val READ_END_MARKER: Byte = 0xA9.toByte()

  /** Number of pages to read (2 pages for single ignition map) */
  const val PAGES_TO_READ = 2

  /** Number of timing points in the map */
  const val TIMING_POINTS = 16

  /**
   * Validates a received page from CDI.
   * @param page The 64-byte page data
   * @return true if valid CDI response page
   */
//  fun isValidPage(page: ByteArray): Boolean {
//    return page.size == TIMING_PAGE_SIZE &&
//           page[0] == 0x02.toByte() &&
//           page[1] == 0x07.toByte() &&
//           page[63] == 0xB9.toByte()
//  }

  /**
   * Creates acknowledgment message to request next page.
   * Changes byte 0 from 0x02 (response) to 0x01 (request),
   * adjusts checksum byte, and sets end marker.
   * 
   * @param pageData The received page data to acknowledge
   * @return Acknowledgment message (64 bytes)
   */
  fun createAcknowledgeMessage(pageData: ByteArray): ByteArray {
    val ack = pageData.copyOf()
    ack[0] = 0x01  // Change response marker to request marker
    ack[62] = (ack[62] - 1).toByte()  // Adjust checksum
    ack[63] = (ack[63] - 1).toByte()  // End marker for request
    return ack
  }

  /**
   * Parses the timing map data from two pages of CDI response.
   * 
   * @param pageData Combined data from all pages (bytes 4-61 from each page)
   * @return List of TimingPoint objects, or null if parsing fails
   */
  fun parseTimingMap(pageData: ByteArray): List<TimingPoint>? {
    // We need at least 64 bytes for 16 RPM + 16 timing values (each 16-bit = 32 + 32)
    if (pageData.size < 64) {
      return null
    }

    val rpmValues = mutableListOf<Int>()
    val timingValues = mutableListOf<Int>()

    // First 32 bytes are RPM values (16 × 16-bit little-endian)
    for (i in 0 until 32 step 2) {
      val rpm = (pageData[i].toInt() and 0xFF) or 
                ((pageData[i + 1].toInt() and 0xFF) shl 8)
      rpmValues.add(rpm)
    }

    // Next 32 bytes are timing values (16 × 16-bit little-endian)
    for (i in 32 until 64 step 2) {
      val timing = (pageData[i].toInt() and 0xFF) or 
                   ((pageData[i + 1].toInt() and 0xFF) shl 8)
      timingValues.add(timing)
    }

    // Combine into TimingPoint list
    return rpmValues.zip(timingValues) { rpm, timing ->
      TimingPoint(rpm, timing)
    }
  }

  // ==================== WRITE PROTOCOL ====================

  /**
   * Calculates checksum for a 64-byte message.
   * Checksum byte 0: Sum of bytes 0-61 modulo 256
   * Checksum byte 1: 0xA8 for write commands, 0xA9 for responses
   *
   * @param data The first 62 bytes of the message
   * @param isWriteCommand true for host commands (0xA8), false for CDI responses (0xA9)
   * @return 2-byte checksum array
   */
  fun calculateChecksum(data: ByteArray, isWriteCommand: Boolean = true): ByteArray {
    var sum = 0
    for (i in 0 until minOf(62, data.size)) {
      sum += data[i].toInt() and 0xFF
    }
    val checksumByte1 = (sum and 0xFF).toByte()
    val checksumByte2 = if (isWriteCommand) WRITE_END_MARKER else READ_END_MARKER
    return byteArrayOf(checksumByte1, checksumByte2)
  }

  /**
   * Creates a complete 64-byte message with proper checksum.
   *
   * @param data The first 62 bytes of the message (will be padded with zeros if shorter)
   * @param isWriteCommand true for host commands (0xA8), false for CDI responses (0xA9)
   * @return Complete 64-byte message with checksum
   */
  fun createMessage(data: ByteArray, isWriteCommand: Boolean = true): ByteArray {
    val message = ByteArray(TIMING_PAGE_SIZE)
    // Copy data (up to 62 bytes)
    data.copyInto(message, 0, 0, minOf(62, data.size))
    // Calculate and append checksum
    val checksum = calculateChecksum(message, isWriteCommand)
    message[62] = checksum[0]
    message[63] = checksum[1]
    return message
  }

  /**
   * Creates a page write message containing timing map data.
   * Header: 01 02 00 [pageNum], then 58 bytes of data, then checksum.
   *
   * @param pageNum Page number (0 or 1)
   * @param pageData 58 bytes of timing data for this page
   * @return 64-byte page write message
   */
  fun createPageWriteMessage(pageNum: Int, pageData: ByteArray): ByteArray {
    val data = ByteArray(62)
    data[0] = 0x01  // Command marker
    data[1] = 0x02  // Page write command
    data[2] = 0x00  // Reserved
    data[3] = pageNum.toByte()  // Page number
    // Copy page data (up to 58 bytes) starting at byte 4
    pageData.copyInto(data, 4, 0, minOf(58, pageData.size))
    return createMessage(data, isWriteCommand = true)
  }

  // We verify only response header as sometimes response has to be reread multiple times.
//  fun isValidResponseHeader(response: ByteArray, sentMessage: ByteArray): Boolean {
//
//    if (response.size == 0)             return false
//    if (response[0] != 0x02.toByte())   return false
//    if (response[1] != sentMessage[1] + 1)  return false
//
//    return true
//  }

  /**
   * Converts a list of TimingPoints to the raw byte format for writing to CDI.
   * Returns 2 pages of 58 bytes each (116 bytes total).
   *
   * Page 0 (58 bytes):
   * - Bytes 0-31: 16 RPM values (16-bit little-endian)
   * - Bytes 32-57: First 26 bytes of timing values
   *
   * Page 1 (58 bytes):
   * - Bytes 0-5: Last 6 bytes of timing values
   * - Bytes 6-57: Zeros (or additional data if needed)
   *
   * @param timingMap List of 16 TimingPoints
   * @return Pair of ByteArrays (page0Data, page1Data), each 58 bytes
   */
  fun timingMapToPageData(timingMap: List<TimingPoint>): Pair<ByteArray, ByteArray> {
    require(timingMap.size == TIMING_POINTS) { "Timing map must have exactly $TIMING_POINTS points" }
    
    // Build the raw data: 32 bytes RPM + 32 bytes timing = 64 bytes total
    val rawData = ByteArray(64)
    
    // First 32 bytes: RPM values (16-bit little-endian)
    for (i in 0 until TIMING_POINTS) {
      val rpm = timingMap[i].rpm
      rawData[i * 2] = (rpm and 0xFF).toByte()
      rawData[i * 2 + 1] = ((rpm shr 8) and 0xFF).toByte()
    }
    
    // Next 32 bytes: Timing values (16-bit little-endian)
    for (i in 0 until TIMING_POINTS) {
      val timing = timingMap[i].timingRaw
      rawData[32 + i * 2] = (timing and 0xFF).toByte()
      rawData[32 + i * 2 + 1] = ((timing shr 8) and 0xFF).toByte()
    }
    
    // Split into 2 pages of 58 bytes each
    // Page 0: bytes 0-57 of raw data
    val page0 = ByteArray(58)
    rawData.copyInto(page0, 0, 0, 58)
    
    // Page 1: bytes 58-63 of raw data, then zeros
    val page1 = ByteArray(58)
    rawData.copyInto(page1, 0, 58, 64)
    // Rest of page1 is zeros
    
    return Pair(page0, page1)
  }

  /**
   * Result of a write operation.
   */
  sealed class WriteResult {
    object Success : WriteResult()
    data class Error(val message: String, val step: String) : WriteResult()
  }
}
