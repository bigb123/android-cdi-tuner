package com.tuner.cdituner

/**
 * Protocol handler for reading ignition timing maps from CDI.
 * 
 * Protocol (from serial communication analysis):
 * 1. Send ignition map request: 01 06 00 00 ... 07 B8 (64 bytes)
 * 2. CDI responds with page 0: 02 07 00 00 ... B9 (64 bytes)
 * 3. Host echoes page back with modified markers to request next page
 * 4. CDI sends page 1: 02 07 00 01 ... B9
 * 5. Continues for required pages (2 pages for single timing map)
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

  /** Expected page size in bytes */
  const val PAGE_SIZE = 64
  const val USEFUL_DATA_SIZE = 58 // From entire page we actually only need interior bytes that are surrounded by header (4 bytes) and footer (2 bytes)


  /** Number of pages to read (2 pages for single ignition map) */
  const val PAGES_TO_READ = 2

  /** Number of timing points in the map */
  const val TIMING_POINTS = 16

  /**
   * Validates a received page from CDI.
   * @param page The 64-byte page data
   * @return true if valid CDI response page
   */
  fun isValidPage(page: ByteArray): Boolean {
    return page.size == PAGE_SIZE && 
           page[0] == 0x02.toByte() && 
           page[1] == 0x07.toByte() &&
           page[63] == 0xB9.toByte()
  }

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
//
//  /**
//   * Extracts the data portion from a CDI response page.
//   * Data is in bytes 4-61 (58 bytes per page).
//   *
//   * @param page The 64-byte page
//   * @return Data bytes (58 bytes)
//   */
//  fun extractPageData(page: ByteArray): ByteArray {
//    return page.sliceArray(4 until 62)
//  }
}
