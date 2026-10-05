package com.cielvitrine.pos

import java.nio.charset.StandardCharsets

object PosConstants {
    const val FORMAT_VERSION: Int = 1
    const val HEADER_LENGTH: Int = 128
    const val FOOTER_LENGTH: Int = 160
    const val RECORD_HEADER_LENGTH: Int = 28 // 4 magic + 8 seq + 8 offset + 4 len + 4 crc
    const val DEFAULT_CHUNK_SIZE: Int = 65536 // 64 KiB

    val MAGIC_HEADER = "CIELPOS1".toByteArray(StandardCharsets.US_ASCII)
    val MAGIC_RECORD = "PREC".toByteArray(StandardCharsets.US_ASCII)
    val MAGIC_FOOTER = "CIELEND1".toByteArray(StandardCharsets.US_ASCII)
    val MARKER_COMPLETE = "COMPLETE".toByteArray(StandardCharsets.US_ASCII)

    const val BOOTSTRAP_DESCRIPTOR = "CIEL_VITRINE_POS_V1_STANDARD"

    const val TRANSFORM_IDENTITY = 0
    const val TRANSFORM_POS_XOR_V1 = 1

    const val FLAG_IS_FINAL_PART_KNOWN = 1 shl 0
    const val FLAG_ORIGINAL_LENGTH_KNOWN = 1 shl 1
    const val FLAG_CONTAINS_MANIFEST = 1 shl 2
}
