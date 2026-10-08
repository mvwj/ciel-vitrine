package com.cielvitrine.archive

import com.cielvitrine.pos.Pos2
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ColdArchiveTest {
    private class Fixture {
        val source = ByteArray(131079) { (it * 31).toByte() }
        var saved = Json.encodeToString(ArchiveJob.serializer(), ArchiveJob("a".repeat(32), "123", "root-1", "source", "photo.heic", "image/heic", source.size.toLong()))
        val journal = object : ArchiveJournal {
            override fun load(id: String) = Json.decodeFromString(ArchiveJob.serializer(), saved)
            override fun save(job: ArchiveJob) { job.validate(); saved = Json.encodeToString(ArchiveJob.serializer(), job) }
        }
        var staged: ByteArray? = null
        var remote: ByteArray? = null
        var uploads = 0
        var wrongAccount = false
        var throwAfterCommit = false
        var removed = false
        var changeVersion = false
        var inspections = 0
        val staging = object : ArchiveStaging {
            override fun prepare(job: ArchiveJob): ArchiveProof {
                val output = ByteArrayOutputStream()
                val proof = Pos2.write(source.inputStream(), output, job.name, job.mime, job.sourceBytes)
                staged = output.toByteArray()
                return ArchiveProof.from(proof)
            }
            override fun open(id: String) = staged!!.inputStream()
            override fun removeOwnedContainer(id: String) { removed = true; staged = null }
        }
        val cloud = object : ArchiveCloud {
            override fun requireAccount(uid: String, rootId: String) { require(!wrongAccount && uid == "123" && rootId == "root-1") }
            override fun ensureColdFolders() {}
            override fun inspect(path: String): ArchiveRemote? {
                inspections++
                return remote?.let { ArchiveRemote("object", if (changeVersion && inspections > 2) "v2" else "v1", it.size.toLong(), null) }
            }
            override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) {
                uploads++
                assertEquals(ArchivePhase.UPLOAD_UNCERTAIN, journal.load("a".repeat(32)).phase)
                remote = open().use { it.readBytes() }
                if (throwAfterCommit) error("Reply lost after commit")
            }
            override fun <T> readback(path: String, read: (InputStream) -> T): T = remote!!.inputStream().use(read)
        }
        fun step() = ColdArchive(journal, staging, cloud).step("a".repeat(32))
    }
    @Test fun `restart after lost reply only verifies and never uploads again`() {
        val f = Fixture()
        f.step(); f.throwAfterCommit = true
        assertThrows(IllegalStateException::class.java) { f.step() }
        assertEquals(ArchivePhase.UPLOAD_UNCERTAIN, f.journal.load("a".repeat(32)).phase)
        assertFalse(f.removed)
        assertEquals(ArchivePhase.VERIFIED, f.step().phase)
        assertEquals(1, f.uploads)
        assertTrue(f.removed)
        assertEquals(ArchivePhase.VERIFIED, f.step().phase)
        assertEquals(1, f.uploads)
        assertEquals(131079, f.source.size)
    }
    @Test fun `tampered remote and missing path preserve staging and do not authorize retry PUT`() {
        val f = Fixture(); f.step(); f.step()
        f.remote!![f.remote!!.lastIndex] = (f.remote!!.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { f.step() }
        assertFalse(f.removed)
        f.remote = null
        repeat(3) { assertThrows(IllegalStateException::class.java) { f.step() } }
        assertEquals(1, f.uploads)
        assertEquals(ArchivePhase.UPLOAD_UNCERTAIN, f.journal.load("a".repeat(32)).phase)
    }
    @Test fun `account switch stops all cloud mutation and verification`() {
        val f = Fixture(); f.step(); f.wrongAccount = true
        assertThrows(IllegalArgumentException::class.java) { f.step() }
        assertEquals(0, f.uploads)
        f.wrongAccount = false; f.step(); f.wrongAccount = true
        assertThrows(IllegalArgumentException::class.java) { f.step() }
        assertFalse(f.removed)
    }
    @Test fun `local container change and remote version change fail closed`() {
        val f = Fixture(); f.step()
        f.staged!![f.staged!!.lastIndex] = (f.staged!!.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { f.step() }
        assertEquals(0, f.uploads)
        val g = Fixture(); g.step(); g.step(); g.changeVersion = true
        assertThrows(IllegalArgumentException::class.java) { g.step() }
        assertFalse(g.removed)
    }
    @Test fun `interruption after persisted intent cannot repeat upload`() {
        val f = Fixture(); f.step()
        var checks = 0
        val engine = ColdArchive(f.journal, f.staging, f.cloud, { ++checks >= 3 })
        assertThrows(IllegalStateException::class.java) { engine.step("a".repeat(32)) }
        assertEquals(ArchivePhase.UPLOAD_UNCERTAIN, f.journal.load("a".repeat(32)).phase)
        assertEquals(0, f.uploads)
        assertThrows(IllegalStateException::class.java) { f.step() }
        assertEquals(0, f.uploads)
    }
}
