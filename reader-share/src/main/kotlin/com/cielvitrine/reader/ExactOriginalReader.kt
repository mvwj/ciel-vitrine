package com.cielvitrine.reader

import com.cielvitrine.core.model.ResourceVersion
import com.cielvitrine.pos.*
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface ExactOriginalReader : AutoCloseable {
    val resourceVersion: ResourceVersion
    val totalByteLength: Long

    fun sequentialRead(): InputStream
    fun randomRead(offset: Long, length: Int): ByteArray
}

class LocalFileExactReader(
    override val resourceVersion: ResourceVersion,
    private val file: File
) : ExactOriginalReader {

    override val totalByteLength: Long = file.length()

    override fun sequentialRead(): InputStream = file.inputStream()

    override fun randomRead(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && offset + length <= totalByteLength) { "Range out of bounds" }
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(length)
            raf.readFully(buf)
            return buf
        }
    }

    override fun close() {}
}

class PosFileExactReader(
    override val resourceVersion: ResourceVersion,
    private val posFile: File
) : ExactOriginalReader {

    private val raf = RandomAccessFile(posFile, "r")
    private val header: PosHeader

    init {
        raf.seek(0)
        val hBytes = ByteArray(PosConstants.HEADER_LENGTH)
        raf.readFully(hBytes)
        header = PosHeader.parse(hBytes)
        require(header.partCount == 1 && header.partIndex == 0 && header.originalStartOffset == 0L) { "This reader supports single-part originals only" }
    }

    override val totalByteLength: Long = resourceVersion.trustedOriginalByteLength

    override fun sequentialRead(): InputStream {
        val pipeIn = java.io.PipedInputStream(65536)
        val pipeOut = java.io.PipedOutputStream(pipeIn)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        Thread {
            try {
            posFile.inputStream().use { input ->
                val result = PosReader.read(input, pipeOut)
                check(result.recoveredByteLength == resourceVersion.trustedOriginalByteLength && result.recoveredSha256Hex.equals(resourceVersion.trustedOriginalSha256, true)) { "Recovered stream does not match trusted original" }
            }
            } catch (e: Throwable) { failure.set(e) }
            finally { pipeOut.close() }
        }.apply { isDaemon = true; name = "pos-reader" }.start()
        return object : java.io.FilterInputStream(pipeIn) {
            override fun read(): Int = checked(super.read())
            override fun read(b: ByteArray, off: Int, len: Int): Int = checked(super.read(b, off, len))
            private fun checked(n: Int): Int {
                if (n < 0) failure.get()?.let { throw java.io.IOException("POS recovery failed", it) }
                return n
            }
        }
    }

    @Synchronized
    override fun randomRead(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && offset + length <= totalByteLength) { "Range out of bounds" }
        if (length == 0) return ByteArray(0)

        val mapping = PosRandomAccess.mapOriginalRangeToContainerRange(
            originalStartOffset = offset,
            originalLength = length.toLong(),
            recordChunkSize = header.recordChunkSize,
            headerLength = header.headerLength
        )

        val output = ByteArray(length)
        var outputWritten = 0

        for (seq in mapping.firstRecordSeq..mapping.lastRecordSeq) {
            val recordSlotSize = PosConstants.RECORD_HEADER_LENGTH + header.recordChunkSize.toLong()
            val recordFileOffset = header.headerLength + (seq * recordSlotSize)

            raf.seek(recordFileOffset)
            val recHBytes = ByteArray(PosConstants.RECORD_HEADER_LENGTH)
            raf.readFully(recHBytes)

            val buf = ByteBuffer.wrap(recHBytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            if (!magic.contentEquals(PosConstants.MAGIC_RECORD)) {
                throw IllegalStateException("Record magic mismatch at sequence $seq")
            }
            val rSeq = buf.long
            val rOffset = buf.long
            val payloadLen = buf.int
            val expectedCrc = buf.int
            check(rSeq == seq && rOffset == Math.multiplyExact(seq, header.recordChunkSize.toLong()) && payloadLen in 1..header.recordChunkSize) { "Invalid random-access record" }

            val payload = ByteArray(payloadLen)
            raf.readFully(payload)
            val crc = java.util.zip.CRC32().apply { update(payload) }
            check(crc.value.toInt() == expectedCrc) { "Random-access record CRC mismatch" }

            val plain = PosTransform.inverseTransform(
                transformId = header.transformId,
                setId = header.setId,
                originalOffset = rOffset,
                transformedData = payload,
                length = payloadLen
            )

            // Calculate overlap between this chunk's original range and the requested [offset, offset + length)
            val chunkOrigStart = rOffset
            val chunkOrigEnd = rOffset + payloadLen

            val reqStart = maxOf(offset, chunkOrigStart)
            val reqEnd = minOf(offset + length, chunkOrigEnd)

            val chunkReadOffset = (reqStart - chunkOrigStart).toInt()
            val bytesToCopy = (reqEnd - reqStart).toInt()

            System.arraycopy(plain, chunkReadOffset, output, outputWritten, bytesToCopy)
            outputWritten += bytesToCopy
        }

        return output
    }

    override fun close() {
        raf.close()
    }
}
