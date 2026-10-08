package com.cielvitrine.archive

import com.cielvitrine.pos.Pos2
import com.cielvitrine.pos.Pos2Result
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.FutureTask

/** A new physical container, from a trusted old container; plaintext exists only in a bounded pipe. */
object OriginalRepack {
    fun write(open: () -> InputStream, output: OutputStream, proof: ArchiveProof, name: String, mime: String): Pos2Result {
        val input = PipedInputStream(128 * 1024)
        val pipe = PipedOutputStream(input)
        val decode = FutureTask {
            pipe.use { sink -> open().use { source ->
                val recovered = Pos2.read(source, sink, proof.originalBytes, proof.originalSha256, ColdArchive.MAX_BYTES)
                require(ArchiveProof.from(recovered) == proof)
            } }
        }
        val thread = Thread(decode, "original-repack").apply { isDaemon = true; start() }
        try {
            val result = input.use { Pos2.write(it, output, name, mime, proof.originalBytes, ColdArchive.MAX_BYTES) }
            // EOF in the pipe does not mean the source footer was valid: the producer must also succeed.
            decode.get()
            require(result.originalSha256 == proof.originalSha256 && result.originalBytes == proof.originalBytes && result.bundleId != proof.bundleId)
            return result
        } finally {
            input.close(); pipe.close(); decode.cancel(true); thread.join(1000)
        }
    }
}
