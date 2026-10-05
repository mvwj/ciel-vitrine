package com.cielvitrine.pos.refdecoder

import com.cielvitrine.pos.PosConstants
import com.cielvitrine.pos.PosWriter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ReferenceDecoderTest {

    @Test
    fun `test standalone Kotlin reference decoder verifies production encoded stream`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "pos_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val originalFile = File(tempDir, "original.bin")
            val posFile = File(tempDir, "sample.pos")
            val recoveredFile = File(tempDir, "recovered.bin")

            val originalContent = "Ciel Vitrine authoritative exact original data preservation test content 1234567890."
                .toByteArray(Charsets.UTF_8)
            originalFile.writeBytes(originalContent)

            val setId = java.nio.ByteBuffer.allocate(16).apply {
                val u = UUID.randomUUID()
                putLong(u.mostSignificantBits)
                putLong(u.leastSignificantBits)
            }.array()

            FileOutputStream(posFile).use { out ->
                PosWriter.write(
                    sourceInput = ByteArrayInputStream(originalContent),
                    destinationOutput = out,
                    setId = setId,
                    transformId = PosConstants.TRANSFORM_POS_XOR_V1,
                    chunkSize = 32
                )
            }

            // Decode with independent decoder
            val result = decodePos(posFile, recoveredFile)

            assertEquals(originalContent.size.toLong(), result.bytesRecovered)
            assertArrayEquals(originalContent, recoveredFile.readBytes())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
