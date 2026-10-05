package com.cielvitrine.pos

import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

data class PosWriteResult(
    val setId: ByteArray,
    val partIndex: Int,
    val partOriginalByteLength: Long,
    val partOriginalSha256Hex: String,
    val totalOriginalByteLength: Long,
    val totalOriginalSha256Hex: String,
    val recordCount: Long,
    val containerByteLength: Long,
    val containerSha256Hex: String
)

object PosWriter {

    fun write(
        sourceInput: InputStream,
        destinationOutput: OutputStream,
        setId: ByteArray,
        partIndex: Int = 0,
        partCount: Int = 1,
        originalStartOffset: Long = 0L,
        totalOriginalByteLength: Long = 0L,
        transformId: Int = PosConstants.TRANSFORM_POS_XOR_V1,
        chunkSize: Int = PosConstants.DEFAULT_CHUNK_SIZE
    ): PosWriteResult {
        val containerDigest = MessageDigest.getInstance("SHA-256")
        val containerOut = DigestOutputStream(destinationOutput, containerDigest)

        // 1. Write Header
        val header = PosHeader(
            setId = setId,
            partIndex = partIndex,
            partCount = partCount,
            originalStartOffset = originalStartOffset,
            transformId = transformId,
            recordChunkSize = chunkSize
        )
        header.writeTo(containerOut)
        var totalContainerBytes = PosConstants.HEADER_LENGTH.toLong()

        // 2. Stream Records
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(chunkSize)
        var recordSeq = 0L
        var partOriginalBytes = 0L

        while (true) {
            var bytesReadInChunk = 0
            while (bytesReadInChunk < chunkSize) {
                val n = sourceInput.read(buffer, bytesReadInChunk, chunkSize - bytesReadInChunk)
                if (n < 0) break
                bytesReadInChunk += n
            }

            if (bytesReadInChunk == 0) break // EOF reached

            // Update source original digest
            sourceDigest.update(buffer, 0, bytesReadInChunk)

            val currentRecordOffset = originalStartOffset + partOriginalBytes
            val transformed = PosTransform.transform(
                transformId = transformId,
                setId = setId,
                originalOffset = currentRecordOffset,
                data = buffer,
                length = bytesReadInChunk
            )

            val record = PosRecord.create(
                recordSeq = recordSeq,
                originalOffset = currentRecordOffset,
                transformedPayload = transformed,
                length = bytesReadInChunk
            )

            record.writeTo(containerOut)
            totalContainerBytes += (PosConstants.RECORD_HEADER_LENGTH + bytesReadInChunk)

            partOriginalBytes += bytesReadInChunk
            recordSeq++
        }

        val partSha256Bytes = sourceDigest.digest()
        val totalLen = if (totalOriginalByteLength > 0L) totalOriginalByteLength else partOriginalBytes
        val totalSha256Bytes = partSha256Bytes // For single-part, part == total

        // 3. Write Footer
        val containerShaPreFooter = containerDigest.digest() // Container digest up to footer
        val footer = PosFooter(
            partIndex = partIndex,
            totalOriginalByteLength = totalLen,
            totalOriginalSha256 = totalSha256Bytes,
            partOriginalByteLength = partOriginalBytes,
            partOriginalSha256 = partSha256Bytes,
            recordCount = recordSeq,
            containerSha256 = containerShaPreFooter
        )
        footer.writeTo(destinationOutput) // write footer to destination directly
        totalContainerBytes += PosConstants.FOOTER_LENGTH

        destinationOutput.flush()

        return PosWriteResult(
            setId = setId,
            partIndex = partIndex,
            partOriginalByteLength = partOriginalBytes,
            partOriginalSha256Hex = partSha256Bytes.toHex(),
            totalOriginalByteLength = totalLen,
            totalOriginalSha256Hex = totalSha256Bytes.toHex(),
            recordCount = recordSeq,
            containerByteLength = totalContainerBytes,
            containerSha256Hex = containerShaPreFooter.toHex()
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
