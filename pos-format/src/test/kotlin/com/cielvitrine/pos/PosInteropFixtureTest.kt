package com.cielvitrine.pos

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class PosInteropFixtureTest {
    private fun pack(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also {
        val result = PosWriter.write(ByteArrayInputStream(bytes), it, ByteArray(16) { n -> n.toByte() })
        val wholeSha = MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        assertEquals(wholeSha, result.containerSha256Hex)
    }.toByteArray()

    @Test fun `generate real Kotlin containers for independent Python decoder`() {
        val root = File(System.getProperty("fixture.root")).apply { mkdirs() }
        for (size in listOf(0, 1, 65536, 131079)) {
            val bytes = ByteArray(size) { (it * 31).toByte() }
            File(root, "$size.original").writeBytes(bytes)
            File(root, "$size.pos").writeBytes(pack(bytes))
        }
    }
    @Test fun `reject forged header metadata footer digest and appended data`() {
        val valid = pack(ByteArray(200) { it.toByte() })
        val mutations = listOf(
            valid.copyOf().apply { this[12] = (this[12].toInt() xor 1).toByte() },
            valid.copyOf().apply { this[size - 58] = (this[size - 58].toInt() xor 1).toByte() },
            valid + byteArrayOf(42),
            valid.copyOf().apply { ByteBuffer.wrap(this).putInt(52, 77) },
            valid.copyOf().apply { ByteBuffer.wrap(this).putInt(128 + 20, Int.MAX_VALUE) },
        )
        mutations.forEach { bytes -> assertThrows(Exception::class.java) { PosReader.read(ByteArrayInputStream(bytes), ByteArrayOutputStream()) } }
    }
    @Test fun `multipart writer fails instead of inventing whole original digest`() {
        assertThrows(IllegalArgumentException::class.java) { PosWriter.write(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), ByteArray(16), partCount = 2) }
    }
}
