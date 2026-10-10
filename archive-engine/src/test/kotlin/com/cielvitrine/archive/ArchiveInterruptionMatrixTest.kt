package com.cielvitrine.archive

import com.cielvitrine.pos.Pos2
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Simulated crashes on both sides of every observed journal/provider/staging boundary.
 * Durable state is serialized between newly constructed engines; this is not a physical power-loss test. */
class ArchiveInterruptionMatrixTest {
    private class Fixture(var failAt: Int? = null) {
        val source = ByteArray(65537) { (it * 13).toByte() }
        val snapshot = source.copyOf()
        val id = "a".repeat(32)
        var saved = Json.encodeToString(ArchiveJob.serializer(), ArchiveJob(id, "123", "root", "source", "raw.dng", "image/dng", source.size.toLong()))
        var staged: ByteArray? = null; var remote: ByteArray? = null; var uploads = 0
        val trace = mutableListOf<String>()
        fun boundary(name: String) { trace += name; if (trace.lastIndex == failAt) throw IOException("Injected interruption: $name") }
        val journal = object : ArchiveJournal {
            override fun load(id: String) = Json.decodeFromString(ArchiveJob.serializer(), saved)
            override fun save(job: ArchiveJob) {
                boundary("save-${job.phase}-before"); job.validate()
                saved = Json.encodeToString(ArchiveJob.serializer(), job)
                boundary("save-${job.phase}-after")
            }
        }
        val staging = object : ArchiveStaging {
            override fun prepare(job: ArchiveJob): ArchiveProof {
                boundary("prepare-before")
                val out = ByteArrayOutputStream(); val result = Pos2.write(source.inputStream(), out, job.name, job.mime)
                staged = out.toByteArray(); boundary("prepare-after"); return ArchiveProof.from(result)
            }
            override fun open(id: String): InputStream { boundary("stage-open"); return staged!!.inputStream() }
            override fun removeOwnedContainer(id: String) { boundary("cleanup-before"); staged = null; boundary("cleanup-after") }
        }
        val cloud = object : ArchiveCloud {
            override fun requireAccount(uid: String, rootId: String) { boundary("account-before"); require(uid == "123" && rootId == "root"); boundary("account-after") }
            override fun ensureColdFolders() { boundary("folders-before"); boundary("folders-after") }
            override fun inspect(path: String): ArchiveRemote? {
                boundary("inspect-before"); val result = remote?.let { ArchiveRemote("object", "v1", it.size.toLong(), null) }
                boundary("inspect-after"); return result
            }
            override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) {
                boundary("put-before"); assertEquals(ArchivePhase.UPLOAD_UNCERTAIN, journal.load(id).phase)
                check(remote == null); uploads++; remote = open().use { it.readBytes() }; boundary("put-after")
            }
            override fun <T> readback(path: String, read: (InputStream) -> T): T {
                boundary("read-before"); val result = remote!!.inputStream().use(read); boundary("read-after"); return result
            }
        }
        fun step() = ColdArchive(journal, staging, cloud).step(id)
    }
    @Test fun `interrupting each boundary preserves source and never replays an uncertain PUT`() {
        val baseline = Fixture(); repeat(3) { baseline.step() }
        assertEquals(ArchivePhase.VERIFIED, baseline.journal.load(baseline.id).phase)
        for (cut in baseline.trace.indices) {
            val f = Fixture(cut)
            assertThrows(IOException::class.java, { repeat(3) { f.step() } }, baseline.trace[cut])
            assertArrayEquals(f.snapshot, f.source); f.failAt = null
            repeat(4) { runCatching { f.step() } }
            val job = f.journal.load(f.id); job.validate()
            assertTrue(f.uploads <= 1, "Replayed PUT at ${baseline.trace[cut]}")
            assertArrayEquals(f.snapshot, f.source)
            if (job.phase == ArchivePhase.VERIFIED) {
                val recovered = ByteArrayOutputStream()
                Pos2.read(f.remote!!.inputStream(), recovered, job.proof!!.originalBytes, job.proof!!.originalSha256)
                assertArrayEquals(f.snapshot, recovered.toByteArray())
            } else {
                // Intent persisted but no observable object: retain source and refuse another PUT.
                assertEquals(ArchivePhase.UPLOAD_UNCERTAIN, job.phase)
                assertNull(f.remote); assertNotNull(f.staged)
            }
        }
        println("ARCHIVE_INTERRUPTION_BOUNDARIES=${baseline.trace.size}")
    }
}
