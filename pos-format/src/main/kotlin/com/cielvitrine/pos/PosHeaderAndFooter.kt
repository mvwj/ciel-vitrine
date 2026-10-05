package com.cielvitrine.pos

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class PosHeader(
    val formatVersion: Int = PosConstants.FORMAT_VERSION,
    val headerLength: Int = PosConstants.HEADER_LENGTH,
    val setId: ByteArray, // 16 bytes
    val partIndex: Int = 0,
    val partCount: Int = 1,
    val originalStartOffset: Long = 0L,
    val plannedPartLength: Long = 0L,
    val transformId: Int = PosConstants.TRANSFORM_IDENTITY,
    val recordChunkSize: Int = PosConstants.DEFAULT_CHUNK_SIZE,
    val flags: Int = 0,
    val bootstrapDescriptor: String = PosConstants.BOOTSTRAP_DESCRIPTOR
) {
    init {
        require(setId.size == 16) { "SetId must be exactly 16 bytes" }
        require(formatVersion == 1 && headerLength == PosConstants.HEADER_LENGTH)
        require(partIndex >= 0 && partCount >= 0 && (partCount == 0 || partIndex < partCount))
        require(originalStartOffset >= 0 && plannedPartLength >= 0)
        require(recordChunkSize in 1..PosConstants.MAX_CHUNK_SIZE)
        require(transformId in 0..1) { "Unsupported transform" }
        require(flags and 7.inv() == 0) { "Unsupported flags" }
    }

    fun toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(PosConstants.HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
        buf.put(PosConstants.MAGIC_HEADER)
        buf.putShort(formatVersion.toShort())
        buf.putShort(headerLength.toShort())
        buf.put(setId)
        buf.putInt(partIndex)
        buf.putInt(partCount)
        buf.putLong(originalStartOffset)
        buf.putLong(plannedPartLength)
        buf.putInt(transformId)
        buf.putInt(recordChunkSize)
        buf.putInt(flags)

        val descBytes = bootstrapDescriptor.toByteArray(StandardCharsets.US_ASCII)
        val descBuffer = ByteArray(32)
        System.arraycopy(descBytes, 0, descBuffer, 0, minOf(descBytes.size, 32))
        buf.put(descBuffer)

        buf.put(ByteArray(32)) // 32 bytes reserved
        return buf.array()
    }

    fun writeTo(out: OutputStream) {
        out.write(toByteArray())
    }

    companion object {
        fun readFrom(input: InputStream): PosHeader {
            val bytes = ByteArray(PosConstants.HEADER_LENGTH)
            var read = 0
            while (read < PosConstants.HEADER_LENGTH) {
                val n = input.read(bytes, read, PosConstants.HEADER_LENGTH - read)
                if (n < 0) throw IllegalStateException("EOF reached while reading POS header")
                read += n
            }
            return parse(bytes)
        }

        fun parse(bytes: ByteArray): PosHeader {
            require(bytes.size >= PosConstants.HEADER_LENGTH) { "Invalid POS header buffer size" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(8)
            buf.get(magic)
            if (!magic.contentEquals(PosConstants.MAGIC_HEADER)) {
                throw IllegalArgumentException("Invalid POS header magic")
            }
            val formatVersion = buf.short.toInt() and 0xFFFF
            if (formatVersion != PosConstants.FORMAT_VERSION) {
                throw UnsupportedOperationException("Unsupported POS format version: $formatVersion")
            }
            val headerLen = buf.short.toInt() and 0xFFFF
            val setId = ByteArray(16)
            buf.get(setId)
            val partIndex = buf.int
            val partCount = buf.int
            val startOffset = buf.long
            val plannedLen = buf.long
            val transformId = buf.int
            val chunkSize = buf.int
            val flags = buf.int

            val descBytes = ByteArray(32)
            buf.get(descBytes)
            val nullIdx = descBytes.indexOf(0)
            val descLen = if (nullIdx >= 0) nullIdx else 32
            val descriptor = String(descBytes, 0, descLen, StandardCharsets.US_ASCII)
            require(descriptor == PosConstants.BOOTSTRAP_DESCRIPTOR && descBytes.drop(descLen).all { it == 0.toByte() }) { "Unsupported bootstrap descriptor" }
            require(bytes.sliceArray(96..127).all { it == 0.toByte() }) { "Nonzero reserved header" }

            return PosHeader(
                formatVersion = formatVersion,
                headerLength = headerLen,
                setId = setId,
                partIndex = partIndex,
                partCount = partCount,
                originalStartOffset = startOffset,
                plannedPartLength = plannedLen,
                transformId = transformId,
                recordChunkSize = chunkSize,
                flags = flags,
                bootstrapDescriptor = descriptor
            )
        }
    }
}

data class PosFooter(
    val formatVersion: Int = PosConstants.FORMAT_VERSION,
    val partIndex: Int = 0,
    val totalOriginalByteLength: Long,
    val totalOriginalSha256: ByteArray, // 32 bytes
    val partOriginalByteLength: Long,
    val partOriginalSha256: ByteArray, // 32 bytes
    val recordCount: Long,
    val containerSha256: ByteArray // 32 bytes
) {
    init {
        require(totalOriginalSha256.size == 32) { "totalOriginalSha256 must be 32 bytes" }
        require(partOriginalSha256.size == 32) { "partOriginalSha256 must be 32 bytes" }
        require(containerSha256.size == 32) { "containerSha256 must be 32 bytes" }
        require(formatVersion == 1 && partIndex >= 0)
        require(totalOriginalByteLength >= 0 && partOriginalByteLength >= 0 && recordCount >= 0)
    }

    fun toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(PosConstants.FOOTER_LENGTH).order(ByteOrder.BIG_ENDIAN)
        buf.put(PosConstants.MAGIC_FOOTER)
        buf.putShort(formatVersion.toShort())
        buf.putInt(partIndex)
        buf.putLong(totalOriginalByteLength)
        buf.put(totalOriginalSha256)
        buf.putLong(partOriginalByteLength)
        buf.put(partOriginalSha256)
        buf.putLong(recordCount)
        buf.put(containerSha256)
        buf.put(ByteArray(18)) // 18 bytes reserved
        buf.put(PosConstants.MARKER_COMPLETE)
        return buf.array()
    }

    fun writeTo(out: OutputStream) {
        out.write(toByteArray())
    }

    companion object {
        fun readFrom(input: InputStream): PosFooter {
            val bytes = ByteArray(PosConstants.FOOTER_LENGTH)
            var read = 0
            while (read < PosConstants.FOOTER_LENGTH) {
                val n = input.read(bytes, read, PosConstants.FOOTER_LENGTH - read)
                if (n < 0) throw IllegalStateException("EOF reached while reading POS footer")
                read += n
            }
            return parse(bytes)
        }

        fun parse(bytes: ByteArray): PosFooter {
            require(bytes.size >= PosConstants.FOOTER_LENGTH) { "Invalid POS footer buffer size" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(8)
            buf.get(magic)
            if (!magic.contentEquals(PosConstants.MAGIC_FOOTER)) {
                throw IllegalArgumentException("Invalid POS footer magic")
            }
            val formatVersion = buf.short.toInt() and 0xFFFF
            val partIndex = buf.int
            val totalLen = buf.long
            val totalSha = ByteArray(32)
            buf.get(totalSha)
            val partLen = buf.long
            val partSha = ByteArray(32)
            buf.get(partSha)
            val recordCount = buf.long
            val containerSha = ByteArray(32)
            buf.get(containerSha)

            val reserved = ByteArray(18)
            buf.get(reserved)
            require(reserved.all { it == 0.toByte() }) { "Nonzero reserved footer" }
            val marker = ByteArray(8)
            buf.get(marker)
            if (!marker.contentEquals(PosConstants.MARKER_COMPLETE)) {
                throw IllegalStateException("POS file is incomplete; missing COMPLETE marker")
            }

            return PosFooter(
                formatVersion = formatVersion,
                partIndex = partIndex,
                totalOriginalByteLength = totalLen,
                totalOriginalSha256 = totalSha,
                partOriginalByteLength = partLen,
                partOriginalSha256 = partSha,
                recordCount = recordCount,
                containerSha256 = containerSha
            )
        }
    }
}
