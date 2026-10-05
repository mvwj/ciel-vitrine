package com.cielvitrine.pos

/**
 * READ-003: Deterministic mapping from original byte ranges to bounded POS container ranges.
 * Allows random-access streaming (e.g. video playback / ranged share) without whole-file download.
 */
object PosRandomAccess {

    data class PosContainerRange(
        val firstRecordSeq: Long,
        val lastRecordSeq: Long,
        val containerStartByte: Long,
        val containerEndByte: Long,
        val firstRecordInternalOffset: Int,
        val requestedOriginalLength: Long
    )

    fun mapOriginalRangeToContainerRange(
        originalStartOffset: Long,
        originalLength: Long,
        recordChunkSize: Int = PosConstants.DEFAULT_CHUNK_SIZE,
        headerLength: Int = PosConstants.HEADER_LENGTH
    ): PosContainerRange {
        require(originalLength > 0) { "originalLength must be positive" }
        require(originalStartOffset >= 0) { "originalStartOffset must be non-negative" }

        val firstRecordSeq = originalStartOffset / recordChunkSize
        val lastByteIndex = originalStartOffset + originalLength - 1
        val lastRecordSeq = lastByteIndex / recordChunkSize

        val recordSlotSize = PosConstants.RECORD_HEADER_LENGTH + recordChunkSize.toLong()

        val containerStartByte = headerLength + (firstRecordSeq * recordSlotSize)
        val containerEndByte = headerLength + ((lastRecordSeq + 1) * recordSlotSize)

        val firstRecordInternalOffset = (originalStartOffset % recordChunkSize).toInt()

        return PosContainerRange(
            firstRecordSeq = firstRecordSeq,
            lastRecordSeq = lastRecordSeq,
            containerStartByte = containerStartByte,
            containerEndByte = containerEndByte,
            firstRecordInternalOffset = firstRecordInternalOffset,
            requestedOriginalLength = originalLength
        )
    }
}
