package com.cielvitrine.pos

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class Pos2Test {
    @Test fun `original unicode name and mime are exact and oversized metadata is rejected before output`() {
        val name = "Фото 🌌 \"лето\".heic"; val mime = "image/heic"
        val output = ByteArrayOutputStream()
        Pos2.write(bytes(1).inputStream(), output, name, mime)
        val restored = Pos2.read(output.toByteArray().inputStream(), ByteArrayOutputStream())
        assertEquals(name, restored.name); assertEquals(mime, restored.mime)
        listOf("x".repeat(1025) to mime, name to "x".repeat(257)).forEach { (invalidName, invalidMime) ->
            val untouched = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) { Pos2.write(bytes(1).inputStream(), untouched, invalidName, invalidMime) }
            assertEquals(0, untouched.size())
        }
    }
    private fun bytes(size: Int) = ByteArray(size) { (it * 37 + 11).toByte() }
    private fun pack(original: ByteArray) = ByteArrayOutputStream().also {
        Pos2.write(original.inputStream(), it, "HDR original.heic", "image/heic", expectedBytes = original.size.toLong(), chunkBytes = 65536)
    }.toByteArray()
    @Test fun `empty boundaries and compound record hashes roundtrip without secrets`() {
        listOf(0, 1, 65536, 65537, 131079).forEach { size ->
            val original = bytes(size); val packed = pack(original); val output = ByteArrayOutputStream()
            val recovered = Pos2.read(packed.inputStream(), output, size.toLong(), hash(original))
            assertArrayEquals(original, output.toByteArray())
            assertEquals(hash(packed), recovered.containerSha256)
            assertEquals(packed.size.toLong(), recovered.containerBytes)
            assertEquals("HDR original.heic", recovered.name)
            assertTrue(String(packed.copyOfRange(12, 12 + ByteBuffer.wrap(packed, 8, 4).int), Charsets.UTF_8).contains("nonce_prefix_hex"))
        }
    }
    @Test fun `ciphertext metadata and missing footer cannot silently produce success`() {
        val packed = pack(bytes(131079)); val headerEnd = 12 + ByteBuffer.wrap(packed, 8, 4).int
        val tampered = packed.copyOf().apply { this[headerEnd + 24 + 5] = (this[headerEnd + 29].toInt() xor 1).toByte() }
        val wrongName = packed.copyOf().apply { val index = String(this, Charsets.ISO_8859_1).indexOf("HDR original"); this[index] = 'X'.code.toByte() }
        listOf(tampered, wrongName, packed.copyOf(packed.size - 1), packed + byteArrayOf(1)).forEach { broken ->
            assertThrows(Exception::class.java) { Pos2.read(broken.inputStream(), ByteArrayOutputStream()) }
        }
    }
    @Test fun `record order bootstrap bounds and trusted hash are enforced`() {
        val packed = pack(bytes(131079)); val headerEnd = 12 + ByteBuffer.wrap(packed, 8, 4).int
        val reordered = packed.copyOf().apply { ByteBuffer.wrap(this, headerEnd + 4, 8).putLong(1) }
        val hugeBootstrap = packed.copyOf().apply { ByteBuffer.wrap(this, 8, 4).putInt(Int.MAX_VALUE) }
        val hugeRecord = packed.copyOf().apply { ByteBuffer.wrap(this, headerEnd + 20, 4).putInt(Int.MAX_VALUE) }
        listOf(reordered, hugeBootstrap, hugeRecord).forEach { assertThrows(Exception::class.java) { Pos2.read(it.inputStream(), ByteArrayOutputStream()) } }
        assertThrows(Exception::class.java) { Pos2.read(packed.inputStream(), ByteArrayOutputStream(), expectedSha256 = "0".repeat(64)) }
        assertThrows(Exception::class.java) { Pos2.read(packed.inputStream(), ByteArrayOutputStream(), maximumBytes = 10) }
    }
    @Test fun `source changes size limit and non progressing reads fail instead of hanging`() {
        assertThrows(Exception::class.java) { Pos2.write(bytes(100).inputStream(), ByteArrayOutputStream(), "a", "image/raw", expectedBytes = 101) }
        assertThrows(Exception::class.java) { Pos2.write(bytes(100).inputStream(), ByteArrayOutputStream(), "a", "image/raw", maximumBytes = 10) }
        val stalled = object : InputStream() { override fun read() = 0; override fun read(b: ByteArray, off: Int, len: Int) = 0 }
        assertThrows(Exception::class.java) { Pos2.write(stalled, ByteArrayOutputStream(), "a", "image/raw") }
    }
    @Test fun `Kotlin and independent Python AES implementations interoperate both ways`() {
        val root = File(System.getProperty("pos2.fixture.root")).apply { mkdirs() }
        listOf(0, 1, 65536, 65537, 131079).forEach { size ->
            val original = bytes(size); val source = File(root, "$size.original").apply { writeBytes(original) }
            val kotlin = File(root, "$size.cv2").apply { writeBytes(pack(original)) }
            val python = File(root, "$size.python.cv2").apply { delete() }
            val process = ProcessBuilder(System.getenv("POS2_PYTHON") ?: "python", System.getProperty("pos2.python.script"),
                source.absolutePath, "--output", python.absolutePath, "--encode", "--recipe-source", kotlin.absolutePath).redirectErrorStream(true).start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Independent encoder timed out")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            val output = ByteArrayOutputStream()
            Pos2.read(python.inputStream(), output, original.size.toLong(), hash(original))
            assertArrayEquals(original, output.toByteArray())
        }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
