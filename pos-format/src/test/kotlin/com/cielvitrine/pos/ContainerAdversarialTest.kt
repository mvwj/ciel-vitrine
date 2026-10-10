package com.cielvitrine.pos

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream

@Timeout(60)
class ContainerAdversarialTest {
    private val original = ByteArray(129) { (it * 37).toByte() }
    private fun packed() = ByteArrayOutputStream().also {
        Pos2.write(original.inputStream(), it, "original-ёж.jpg", "image/jpeg", times = OriginalTimes(1600000000, 123456000))
    }.toByteArray()
    @Test fun `a changed bit in every container byte is rejected`() {
        val good = packed()
        for (index in good.indices) {
            val broken = good.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            assertThrows(Exception::class.java, { Pos2.read(broken.inputStream(), ByteArrayOutputStream()) }, "Changed byte $index accepted")
        }
        val output = ByteArrayOutputStream(); Pos2.read(good.inputStream(), output); assertArrayEquals(original, output.toByteArray())
        println("CVPOS3_MUTATION_POSITIONS=${good.size}")
    }
    @Test fun `every incomplete prefix and appended data are rejected`() {
        val good = packed()
        for (end in good.indices) {
            assertThrows(Exception::class.java, { Pos2.read(good.copyOf(end).inputStream(), ByteArrayOutputStream()) }, "Incomplete prefix $end accepted")
        }
        assertThrows(Exception::class.java) { Pos2.read((good + byteArrayOf(0)).inputStream(), ByteArrayOutputStream()) }
        println("CVPOS3_TRUNCATION_POSITIONS=${good.size}")
    }
    @Test fun `valid replacement with public recovery key cannot override a trusted original hash`() {
        val packed = packed(); val trusted = Pos2.read(packed.inputStream(), ByteArrayOutputStream())
        val replacement = original.copyOf().apply { this[lastIndex] = 91 }
        val forged = ByteArrayOutputStream().also { Pos2.write(replacement.inputStream(), it, trusted.name, trusted.mime, times = trusted.times) }.toByteArray()
        assertThrows(Exception::class.java) { Pos2.read(forged.inputStream(), ByteArrayOutputStream(), trusted.originalBytes, trusted.originalSha256) }
    }
}
