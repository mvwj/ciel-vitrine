package com.cielvitrine.pos

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

data class PosRecord(
    val recordSeq: Long,
    val originalOffset: Long,
    val payloadLength: Int,
    val payloadCrc32: Int,
    val payload: ByteArray
) {
    fun writeTo(out: OutputStream) {
        val headerBuf = ByteBuffer.allocate(PosConstants.RECORD_HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
        headerBuf.put(PosConstants.MAGIC_RECORD)
        headerBuf.putLong(recordSeq)
        headerBuf.putLong(originalOffset)
        headerBuf.putInt(payloadLength)
        headerBuf.putInt(payloadCrc32)
        out.write(headerBuf.array())
        out.write(payload, 0, payloadLength)
    }

    companion object {
        fun create(
            recordSeq: Long,
            originalOffset: Long,
            transformedPayload: ByteArray,
            length: Int = transformedPayload.size
        ): PosRecord {
            val crc = CRC32()
            crc.update(transformedPayload, 0, length)
            return PosRecord(
                recordSeq = recordSeq,
                originalOffset = originalOffset,
                payloadLength = length,
                payloadCrc32 = crc.value.toInt(),
                payload = transformedPayload
            )
        }

        fun readFrom(input: InputStream): PosRecord? {
            val headerBytes = ByteArray(PosConstants.RECORD_HEADER_LENGTH)
            var read = 0
            while (read < PosConstants.RECORD_HEADER_LENGTH) {
                val n = input.read(headerBytes, read, PosConstants.RECORD_HEADER_LENGTH - read)
                if (n < 0) {
                    if (read == 0) return null // Clean EOF before record
                    throw IllegalStateException("Unexpected EOF in record header at byte $read")
                }
                read += n
            }

            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            if (!magic.contentEquals(PosConstants.MAGIC_RECORD)) {
                // Not a record magic; may be the footer magic
                return null
            }

            val recordSeq = buf.long
            val originalOffset = buf.long
            val payloadLen = buf.int
            val expectedCrc = buf.int

            val payload = ByteArray(payloadLen)
            var pRead = 0
            while (pRead < payloadLen) {
                val n = input.read(payload, pRead, payloadLen - pRead)
                if (n < 0) throw IllegalStateException("Unexpected EOF while reading record payload")
                pRead += n
            }

            val crc = CRC32()
            crc.update(payload, 0, payloadLen)
            if (crc.value.toInt() != expectedCrc) {
                throw IllegalStateException("Record $recordSeq CRC-32 mismatch! Corrupted record payload.")
            }

            return PosRecord(
                recordSeq = recordSeq,
                originalOffset = originalOffset,
                payloadLength = payloadLen,
                payloadCrc32 = expectedCrc,
                payload = payload
            )
        }
    }
}
