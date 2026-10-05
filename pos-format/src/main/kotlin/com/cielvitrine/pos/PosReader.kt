package com.cielvitrine.pos

import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest

data class PosReadResult(
    val header: PosHeader,
    val footer: PosFooter,
    val recoveredByteLength: Long,
    val recoveredSha256Hex: String,
    val recordCount: Long,
    val isSha256Verified: Boolean
)

object PosReader {

    fun read(
        posInput: InputStream,
        destinationOutput: OutputStream
    ): PosReadResult {
        val pushback = PushbackInputStream(posInput, PosConstants.FOOTER_LENGTH)

        // 1. Read Header
        val header = PosHeader.readFrom(pushback)

        // 2. Read records sequentially until footer magic is detected
        val recoveredDigest = MessageDigest.getInstance("SHA-256")
        var recordCount = 0L
        var recoveredBytes = 0L

        while (true) {
            // Check next 8 bytes to see if it's the footer magic
            val probe = ByteArray(8)
            var pRead = 0
            while (pRead < 8) {
                val n = pushback.read(probe, pRead, 8 - pRead)
                if (n < 0) break
                pRead += n
            }

            if (pRead < 8) {
                throw IllegalStateException("Unexpected EOF before completing POS file")
            }

            // If magic matches footer magic, unread the probe bytes and break to read footer
            if (probe.contentEquals(PosConstants.MAGIC_FOOTER)) {
                pushback.unread(probe, 0, pRead)
                break
            }

            // Otherwise, it must be the start of a record
            pushback.unread(probe, 0, pRead)
            val record = PosRecord.readFrom(pushback)
                ?: throw IllegalStateException("Expected POS record at sequence $recordCount but got null")

            if (record.recordSeq != recordCount) {
                throw IllegalStateException("Out of order record sequence: expected $recordCount, got ${record.recordSeq}")
            }

            // Inverse transform
            val originalPayload = PosTransform.inverseTransform(
                transformId = header.transformId,
                setId = header.setId,
                originalOffset = record.originalOffset,
                transformedData = record.payload,
                length = record.payloadLength
            )

            recoveredDigest.update(originalPayload, 0, record.payloadLength)
            destinationOutput.write(originalPayload, 0, record.payloadLength)
            recoveredBytes += record.payloadLength
            recordCount++
        }

        destinationOutput.flush()

        // 3. Read Footer
        val footer = PosFooter.readFrom(pushback)

        if (footer.recordCount != recordCount) {
            throw IllegalStateException("Record count mismatch! Footer states ${footer.recordCount}, decoded $recordCount")
        }

        if (footer.partOriginalByteLength != recoveredBytes) {
            throw IllegalStateException("Byte length mismatch! Footer states ${footer.partOriginalByteLength}, decoded $recoveredBytes")
        }

        val recoveredSha256 = recoveredDigest.digest()
        val isVerified = recoveredSha256.contentEquals(footer.partOriginalSha256)
        if (!isVerified) {
            throw IllegalStateException(
                "SHA-256 integrity mismatch! Expected ${footer.partOriginalSha256.toHex()}, got ${recoveredSha256.toHex()}"
            )
        }

        return PosReadResult(
            header = header,
            footer = footer,
            recoveredByteLength = recoveredBytes,
            recoveredSha256Hex = recoveredSha256.toHex(),
            recordCount = recordCount,
            isSha256Verified = isVerified
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
