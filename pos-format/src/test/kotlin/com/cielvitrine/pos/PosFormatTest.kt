package com.cielvitrine.pos

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

class PosFormatTest {

    private fun generateRandomBytes(size: Int): ByteArray {
        val b = ByteArray(size)
        for (i in 0 until size) {
            b[i] = ((i * 31 + 7) and 0xFF).toByte()
        }
        return b
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(16)
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        return bb.array()
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `test zero-byte input encoding and recovery`() {
        val setId = uuidToBytes(UUID.randomUUID())
        val source = ByteArray(0)
        val posOut = ByteArrayOutputStream()

        val writeResult = PosWriter.write(
            sourceInput = ByteArrayInputStream(source),
            destinationOutput = posOut,
            setId = setId,
            chunkSize = 65536
        )

        assertEquals(0L, writeResult.partOriginalByteLength)
        assertEquals(0L, writeResult.recordCount)
        assertEquals(sha256Hex(source), writeResult.totalOriginalSha256Hex)

        val posBytes = posOut.toByteArray()
        assertEquals(PosConstants.HEADER_LENGTH + PosConstants.FOOTER_LENGTH, posBytes.size)

        val recoverOut = ByteArrayOutputStream()
        val readResult = PosReader.read(
            posInput = ByteArrayInputStream(posBytes),
            destinationOutput = recoverOut
        )

        assertEquals(0L, readResult.recoveredByteLength)
        assertTrue(readResult.isSha256Verified)
        assertArrayEquals(source, recoverOut.toByteArray())
    }

    @Test
    fun `test one-byte input encoding and recovery`() {
        val setId = uuidToBytes(UUID.randomUUID())
        val source = byteArrayOf(42)
        val posOut = ByteArrayOutputStream()

        val writeResult = PosWriter.write(
            sourceInput = ByteArrayInputStream(source),
            destinationOutput = posOut,
            setId = setId,
            chunkSize = 65536
        )

        assertEquals(1L, writeResult.partOriginalByteLength)
        assertEquals(1L, writeResult.recordCount)

        val recoverOut = ByteArrayOutputStream()
        val readResult = PosReader.read(
            posInput = ByteArrayInputStream(posOut.toByteArray()),
            destinationOutput = recoverOut
        )

        assertEquals(1L, readResult.recoveredByteLength)
        assertTrue(readResult.isSha256Verified)
        assertArrayEquals(source, recoverOut.toByteArray())
    }

    @Test
    fun `test multi-chunk input encoding and exact byte recovery with XOR transform`() {
        val setId = uuidToBytes(UUID.randomUUID())
        val sourceSize = 200_000 // Spans across ~4 chunks of 64 KiB
        val source = generateRandomBytes(sourceSize)
        val posOut = ByteArrayOutputStream()

        val writeResult = PosWriter.write(
            sourceInput = ByteArrayInputStream(source),
            destinationOutput = posOut,
            setId = setId,
            transformId = PosConstants.TRANSFORM_POS_XOR_V1,
            chunkSize = 65536
        )

        assertEquals(sourceSize.toLong(), writeResult.partOriginalByteLength)
        assertEquals(4L, writeResult.recordCount) // 65536 * 3 = 196608, remaining 3392 -> 4 records

        val recoverOut = ByteArrayOutputStream()
        val readResult = PosReader.read(
            posInput = ByteArrayInputStream(posOut.toByteArray()),
            destinationOutput = recoverOut
        )

        assertEquals(sourceSize.toLong(), readResult.recoveredByteLength)
        assertEquals(sha256Hex(source), readResult.recoveredSha256Hex)
        assertTrue(readResult.isSha256Verified)
        assertArrayEquals(source, recoverOut.toByteArray())
    }

    @Test
    fun `test corrupted payload triggers CRC32 failure`() {
        val setId = uuidToBytes(UUID.randomUUID())
        val source = generateRandomBytes(1000)
        val posOut = ByteArrayOutputStream()

        PosWriter.write(
            sourceInput = ByteArrayInputStream(source),
            destinationOutput = posOut,
            setId = setId
        )

        val posBytes = posOut.toByteArray()
        // Corrupt a payload byte inside record 0 (offset: header + 28 record header + 10)
        posBytes[PosConstants.HEADER_LENGTH + PosConstants.RECORD_HEADER_LENGTH + 10] = 0x7F

        val recoverOut = ByteArrayOutputStream()
        val ex = assertThrows<IllegalStateException> {
            PosReader.read(
                posInput = ByteArrayInputStream(posBytes),
                destinationOutput = recoverOut
            )
        }
        assertTrue(ex.message!!.contains("CRC-32 mismatch"))
    }

    @Test
    fun `test random access mapping formula`() {
        // Map offset 130,000 to chunk of size 65536
        // Chunk 0: 0..65535
        // Chunk 1: 65536..131071 -> 130,000 is inside Chunk 1
        val range = PosRandomAccess.mapOriginalRangeToContainerRange(
            originalStartOffset = 130_000,
            originalLength = 1000,
            recordChunkSize = 65536
        )

        assertEquals(1L, range.firstRecordSeq)
        assertEquals(1L, range.lastRecordSeq)
        assertEquals((130_000 % 65536), range.firstRecordInternalOffset)
    }
}
