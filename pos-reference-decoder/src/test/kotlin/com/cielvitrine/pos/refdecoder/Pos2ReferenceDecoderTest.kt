package com.cielvitrine.pos.refdecoder

import com.cielvitrine.pos.Pos2
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class Pos2ReferenceDecoderTest {
    @Test fun `independent JCA decoder restores boundary sizes and preserves existing destination`() {
        val directory = Files.createTempDirectory("cvpos2-independent").toFile()
        try {
            for (size in listOf(0, 1, 65536, 65537, 131079)) {
                val original = ByteArray(size) { (it * 43).toByte() }
                val container = directory.resolve("$size.cv2")
                container.outputStream().use { Pos2.write(original.inputStream(), it, "test.heic", "image/heic", chunkBytes = 65536) }
                val restored = directory.resolve("$size.original")
                assertEquals(size.toLong(), decodePos2(container, restored).bytesRecovered)
                assertArrayEquals(original, restored.readBytes())
                assertThrows(Exception::class.java) { decodePos2(container, restored) }
                assertArrayEquals(original, restored.readBytes())
                val corrupt = container.readBytes().dropLast(1).toByteArray(); container.writeBytes(corrupt)
                val failed = directory.resolve("$size.failed")
                assertThrows(Exception::class.java) { decodePos2(container, failed) }
                assertFalse(failed.exists())
            }
        } finally { directory.deleteRecursively() }
    }
}
