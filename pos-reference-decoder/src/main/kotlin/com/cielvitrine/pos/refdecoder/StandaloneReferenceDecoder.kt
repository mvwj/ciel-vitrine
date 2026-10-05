package com.cielvitrine.pos.refdecoder

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * POS-007: Independent reference decoder implemented cleanly without production dependencies.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: StandaloneReferenceDecoder <input.pos> [output_file]")
        return
    }

    val inputFile = File(args[0])
    val outputFile = if (args.size > 1) File(args[1]) else null

    try {
        val result = decodePos(inputFile, outputFile)
        println("Decoded successfully: ${result.bytesRecovered} bytes, SHA-256=${result.sha256Hex}")
    } catch (e: Exception) {
        System.err.println("Decode failed: ${e.message}")
        e.printStackTrace()
    }
}

data class DecodeResult(
    val bytesRecovered: Long,
    val recordCount: Long,
    val sha256Hex: String
)

fun decodePos(inputFile: File, outputFile: File?): DecodeResult {
    FileInputStream(inputFile).use { input ->
        // 1. Header (128 bytes)
        val headerBytes = ByteArray(128)
        var readH = 0
        while (readH < 128) {
            val n = input.read(headerBytes, readH, 128 - readH)
            if (n < 0) throw IllegalStateException("Header truncated")
            readH += n
        }

        val hBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val magicH = ByteArray(8)
        hBuf.get(magicH)
        if (!magicH.contentEquals("CIELPOS1".toByteArray())) {
            throw IllegalArgumentException("Invalid POS magic")
        }
        val version = hBuf.short.toInt() and 0xFFFF
        if (version != 1) throw UnsupportedOperationException("Unsupported version: $version")
        val hLen = hBuf.short.toInt() and 0xFFFF
        val setId = ByteArray(16)
        hBuf.get(setId)
        val partIndex = hBuf.int
        val partCount = hBuf.int
        val startOffset = hBuf.long
        val plannedLen = hBuf.long
        val transformId = hBuf.int
        val chunkSize = hBuf.int

        val outStream = outputFile?.let { FileOutputStream(it) }
        val digest = MessageDigest.getInstance("SHA-256")
        var recordCount = 0L
        var totalBytes = 0L

        try {
            while (true) {
                // Probe for record or footer magic
                val probe = ByteArray(8)
                var pRead = 0
                while (pRead < 8) {
                    val n = input.read(probe, pRead, 8 - pRead)
                    if (n < 0) break
                    pRead += n
                }

                if (pRead < 8) throw IllegalStateException("Unexpected EOF")

                if (probe.contentEquals("CIELEND1".toByteArray())) {
                    // Reached footer
                    break
                }

                // It's a record: the first 4 bytes were 'PREC', remaining 4 bytes of probe are top 4 bytes of recordSeq
                val rBuf = ByteBuffer.wrap(probe).order(ByteOrder.BIG_ENDIAN)
                val recMagic = ByteArray(4)
                rBuf.get(recMagic)
                if (!recMagic.contentEquals("PREC".toByteArray())) {
                    throw IllegalStateException("Expected record magic PREC")
                }

                val restOfHeader = ByteArray(20) // 4 remaining bytes of seq + 8 offset + 4 len + 4 crc
                var rRead = 0
                while (rRead < 20) {
                    val n = input.read(restOfHeader, rRead, 20 - rRead)
                    if (n < 0) throw IllegalStateException("Truncated record header")
                    rRead += n
                }

                val fullRecH = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                fullRecH.putInt(rBuf.int) // top 4 bytes of seq
                fullRecH.put(restOfHeader)
                fullRecH.flip()

                val recSeq = fullRecH.long
                val recOffset = fullRecH.long
                val payloadLen = fullRecH.int
                val expectedCrc = fullRecH.int

                val payload = ByteArray(payloadLen)
                var plRead = 0
                while (plRead < payloadLen) {
                    val n = input.read(payload, plRead, payloadLen - plRead)
                    if (n < 0) throw IllegalStateException("Truncated record payload")
                    plRead += n
                }

                val crc = CRC32()
                crc.update(payload, 0, payloadLen)
                if (crc.value.toInt() != expectedCrc) {
                    throw IllegalStateException("Record $recSeq CRC mismatch")
                }

                // Inverse transform
                val plain = if (transformId == 1) {
                    val out = ByteArray(payloadLen)
                    for (i in 0 until payloadLen) {
                        val currOffset = recOffset + i
                        val sByte = setId[(currOffset % 16).toInt()].toInt() and 0xFF
                        val k = (sByte xor (currOffset % 251).toInt()) and 0xFF
                        out[i] = (payload[i].toInt() xor k).toByte()
                    }
                    out
                } else {
                    payload
                }

                digest.update(plain, 0, payloadLen)
                outStream?.write(plain, 0, payloadLen)
                totalBytes += payloadLen
                recordCount++
            }

            // Read rest of footer (160 - 8 = 152 bytes)
            val restFooter = ByteArray(152)
            var fRead = 0
            while (fRead < 152) {
                val n = input.read(restFooter, fRead, 152 - fRead)
                if (n < 0) throw IllegalStateException("Truncated footer")
                fRead += n
            }

            val fBuf = ByteBuffer.wrap(restFooter).order(ByteOrder.BIG_ENDIAN)
            val fVer = fBuf.short.toInt() and 0xFFFF
            val fPartIdx = fBuf.int
            val totalLen = fBuf.long
            val totalSha = ByteArray(32)
            fBuf.get(totalSha)
            val partLen = fBuf.long
            val partSha = ByteArray(32)
            fBuf.get(partSha)
            val recCount = fBuf.long
            val contSha = ByteArray(32)
            fBuf.get(contSha)
            val reserved = ByteArray(18)
            fBuf.get(reserved)
            val marker = ByteArray(8)
            fBuf.get(marker)

            if (!marker.contentEquals("COMPLETE".toByteArray())) {
                throw IllegalStateException("Missing COMPLETE marker")
            }

            if (recCount != recordCount) throw IllegalStateException("Record count mismatch")
            if (partLen != totalBytes) throw IllegalStateException("Byte length mismatch")

            val computedSha = digest.digest()
            if (!computedSha.contentEquals(partSha)) {
                throw IllegalStateException("SHA-256 digest mismatch")
            }

            return DecodeResult(
                bytesRecovered = totalBytes,
                recordCount = recordCount,
                sha256Hex = computedSha.joinToString("") { "%02x".format(it) }
            )
        } finally {
            outStream?.close()
        }
    }
}
