package com.cielvitrine.pos

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

class OriginalTimesTest {
    @Test fun `version three preserves source observations and authenticates them without rewriting media`() {
        val bytes = ByteArray(131079) { (it * 37).toByte() }
        val dates = OriginalTimes(1700000000, 123456700, 1400000000123, 1500000000456, 1300000000789)
        val packed = ByteArrayOutputStream(); Pos2.write(bytes.inputStream(), packed, "original.jpg", "image/jpeg", times = dates)
        val restored = ByteArrayOutputStream(); val result = Pos2.read(packed.toByteArray().inputStream(), restored)
        assertEquals(dates, result.times); assertArrayEquals(bytes, restored.toByteArray())
        val changed = packed.toByteArray(); val at = changed.toString(Charsets.ISO_8859_1).indexOf("1700000000")
        assertTrue(at > 0); changed[at] = '2'.code.toByte()
        assertThrows(Exception::class.java) { Pos2.read(changed.inputStream(), ByteArrayOutputStream()) }
        val old = ByteArrayOutputStream(); Pos2.write(bytes.inputStream(), old, "old.jpg", "image/jpeg")
        assertNull(Pos2.read(old.toByteArray().inputStream(), ByteArrayOutputStream()).times)
    }
    @Test fun `independent Python preserves version three timestamps in both directions`() {
        val root = File(System.getProperty("pos2.fixture.root"), "v3").apply { mkdirs() }
        val source = File(root, "original.jpg").apply { writeBytes(ByteArray(65537) { (it * 11).toByte() }) }
        val dates = OriginalTimes(1700000000, 123456700, 1400000000123, 1500000000456, 1300000000789)
        val packed = File(root, "kotlin.bin")
        packed.outputStream().use { out -> source.inputStream().use { Pos2.write(it, out, "original.jpg", "image/jpeg", times = dates) } }
        val independentlyRecovered = File(root, "python-recovered.jpg").apply { delete() }
        val recovery = ProcessBuilder(System.getenv("POS2_PYTHON") ?: "python", System.getProperty("pos2.python.script"), packed.path,
            "--output", independentlyRecovered.path).redirectErrorStream(true).start()
        assertTrue(recovery.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, recovery.exitValue(), recovery.inputStream.bufferedReader().readText())
        assertArrayEquals(source.readBytes(), independentlyRecovered.readBytes())
        val python = File(root, "python.bin").apply { delete() }
        val process = ProcessBuilder(System.getenv("POS2_PYTHON") ?: "python", System.getProperty("pos2.python.script"), source.path,
            "--output", python.path, "--encode", "--recipe-source", packed.path).redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        val restored = ByteArrayOutputStream(); val result = python.inputStream().use { Pos2.read(it, restored) }
        assertEquals(dates, result.times); assertArrayEquals(source.readBytes(), restored.toByteArray())
    }
    @Test fun `unknown dates remain unknown and invalid date claims fail before source output`() {
        OriginalTimes().validate()
        val packed = ByteArrayOutputStream(); Pos2.write(byteArrayOf(1, 2, 3).inputStream(), packed, "unknown.bin", "application/octet-stream", times = OriginalTimes())
        assertEquals(OriginalTimes(), Pos2.read(packed.toByteArray().inputStream(), ByteArrayOutputStream()).times)
        for (invalid in listOf("-0", "+0", "00", "01")) {
            val fields = OriginalTimes(0, 0).fields().toMutableMap().apply { put("mtime_seconds", invalid) }
            assertThrows(IllegalArgumentException::class.java) { OriginalTimes.from(buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }) }
        }
        assertThrows(IllegalArgumentException::class.java) { OriginalTimes(1, null).validate() }
        assertThrows(IllegalArgumentException::class.java) { OriginalTimes(1, 1000000000).validate() }
        assertThrows(IllegalArgumentException::class.java) { OriginalTimes(createdMillis = -1).validate() }
    }
}
