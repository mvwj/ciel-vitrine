package com.cielvitrine.reader

import com.cielvitrine.core.model.ResourceVersion
import com.cielvitrine.core.model.SourceRepresentationKind
import com.cielvitrine.pos.PosConstants
import com.cielvitrine.pos.PosWriter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class ReaderShareTest {
    @Test fun `export rejects existing source aliases and preserves all original bytes`() {
        val directory = java.nio.file.Files.createTempDirectory("export-alias-").toFile()
        try {
            val bytes = ByteArray(1024) { (it * 7).toByte() }
            val source = File(directory, "original.pos")
            source.outputStream().use { PosWriter.write(bytes.inputStream(), it, ByteArray(16)) }
            val encoded = source.readBytes()
            val rv = ResourceVersion("v", "r", "a", SourceRepresentationKind.EXACT_ORIGINAL,
                bytes.size.toLong(), sha256Hex(bytes), 0)
            val hardlink = File(directory, "hardlink.pos")
            java.nio.file.Files.createLink(hardlink.toPath(), source.toPath())
            val existing = File(directory, "existing.bin").apply { writeText("keep me") }
            PosFileExactReader(rv, source).use { reader ->
                for (destination in listOf(source, File(directory, "./original.pos"), hardlink, existing)) {
                    assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                        ShareExportService.exportAndVerifyFile(reader, destination)
                    }
                    assertArrayEquals(encoded, source.readBytes())
                }
                assertEquals("keep me", existing.readText())
                val output = File(directory, "restored.bin")
                assertTrue(ShareExportService.exportAndVerifyFile(reader, output).isDestinationVerified)
                assertArrayEquals(bytes, output.readBytes())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `corrupt footer reaches sequential consumer instead of successful EOF`() {
        val bytes = ByteArray(500) { it.toByte() }
        val file = File.createTempFile("corrupt-footer", ".pos").apply { deleteOnExit() }
        file.outputStream().use { PosWriter.write(ByteArrayInputStream(bytes), it, ByteArray(16)) }
        file.writeBytes(file.readBytes().dropLast(1).toByteArray())
        val v = ResourceVersion("v", "r", "a", SourceRepresentationKind.EXACT_ORIGINAL, bytes.size.toLong(), sha256Hex(bytes), 0)
        PosFileExactReader(v, file).use { reader ->
            assertThrows(java.io.IOException::class.java) { reader.sequentialRead().use { it.readBytes() } }
        }
    }
    @Test fun `random access rejects changed record payload`() {
        val bytes = ByteArray(500) { it.toByte() }
        val file = File.createTempFile("corrupt-record", ".pos").apply { deleteOnExit() }
        file.outputStream().use { PosWriter.write(ByteArrayInputStream(bytes), it, ByteArray(16)) }
        file.writeBytes(file.readBytes().apply { this[PosConstants.HEADER_LENGTH + PosConstants.RECORD_HEADER_LENGTH + 5] = 42 })
        val v = ResourceVersion("v", "r", "a", SourceRepresentationKind.EXACT_ORIGINAL, bytes.size.toLong(), sha256Hex(bytes), 0)
        PosFileExactReader(v, file).use { reader -> assertThrows(IllegalStateException::class.java) { reader.randomRead(0, 30) } }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `test PosFileExactReader random access across chunk boundaries`() {
        val tempDir = File.createTempFile("pos_reader_test_", "").apply { delete(); mkdirs() }
        try {
            val sourceSize = 100_000
            val sourceBytes = ByteArray(sourceSize) { ((it * 17) and 0xFF).toByte() }
            val sourceSha = sha256Hex(sourceBytes)

            val posFile = File(tempDir, "test.pos")
            val setId = ByteArray(16) { 1 }

            FileOutputStream(posFile).use { out ->
                PosWriter.write(
                    sourceInput = ByteArrayInputStream(sourceBytes),
                    destinationOutput = out,
                    setId = setId,
                    transformId = PosConstants.TRANSFORM_POS_XOR_V1,
                    chunkSize = 16384 // 16 KiB chunks
                )
            }

            val rv = ResourceVersion(
                resourceVersionId = "rv-1",
                resourceId = "res-1",
                assetId = "asset-1",
                sourceRepresentationKind = SourceRepresentationKind.EXACT_ORIGINAL,
                trustedOriginalByteLength = sourceSize.toLong(),
                trustedOriginalSha256 = sourceSha,
                createdAt = 0L
            )

            PosFileExactReader(rv, posFile).use { reader ->
                // Test random read spanning chunk boundaries (e.g. from offset 16300, length 200)
                val slice = reader.randomRead(16300, 200)
                assertEquals(200, slice.size)

                val expectedSlice = ByteArray(200)
                System.arraycopy(sourceBytes, 16300, expectedSlice, 0, 200)
                assertArrayEquals(expectedSlice, slice)

                // Test destination readback verified export
                val exportedFile = File(tempDir, "exported.bin")
                val exportRes = ShareExportService.exportAndVerifyFile(reader, exportedFile)

                assertEquals(ExportProofLevel.DESTINATION_READBACK_VERIFIED, exportRes.proofLevel)
                assertTrue(exportRes.isDestinationVerified)
                assertArrayEquals(sourceBytes, exportedFile.readBytes())
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
