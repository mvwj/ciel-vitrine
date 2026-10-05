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
        val containerDigest = MessageDigest.getInstance("SHA-256")
        val digestOut = java.security.DigestOutputStream(object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }, containerDigest)
        header.writeTo(digestOut)
        var shortRecordSeen = false

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
            check(!shortRecordSeen) { "A short record must be final" }
            val record = PosRecord.readFrom(pushback, header.recordChunkSize)
                ?: throw IllegalStateException("Expected POS record at sequence $recordCount but got null")

            if (record.recordSeq != recordCount) {
                throw IllegalStateException("Out of order record sequence: expected $recordCount, got ${record.recordSeq}")
            }
            check(record.originalOffset == Math.addExact(header.originalStartOffset, recoveredBytes)) { "Non-contiguous original offset" }
            shortRecordSeen = record.payloadLength < header.recordChunkSize
            record.writeTo(digestOut)

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
        check(footer.formatVersion == header.formatVersion && footer.partIndex == header.partIndex) { "Header/footer identity mismatch" }
        check(containerDigest.digest().contentEquals(footer.containerSha256)) { "Container digest mismatch" }
        check(pushback.read() == -1) { "Trailing bytes after completion footer" }

        if (footer.recordCount != recordCount) {
            throw IllegalStateException("Record count mismatch! Footer states ${footer.recordCount}, decoded $recordCount")
        }

        if (footer.partOriginalByteLength != recoveredBytes) {
            throw IllegalStateException("Byte length mismatch! Footer states ${footer.partOriginalByteLength}, decoded $recoveredBytes")
        }

        val recoveredSha256 = recoveredDigest.digest()
        if (header.partCount == 1) {
            check(header.partIndex == 0 && header.originalStartOffset == 0L && footer.totalOriginalByteLength == recoveredBytes && footer.totalOriginalSha256.contentEquals(recoveredSha256)) { "Single-part whole original mismatch" }
        }
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
