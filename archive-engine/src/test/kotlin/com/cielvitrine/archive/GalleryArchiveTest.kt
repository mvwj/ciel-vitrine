package com.cielvitrine.archive

import com.cielvitrine.pos.Pos2
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

@Timeout(10)
class GalleryArchiveTest {
    private class Fixture {
        val jobs = mutableMapOf<String, ArchiveJob>()
        val objects = mutableMapOf<String, ByteArray>()
        val source = ByteArray(2 * 1024 * 1024 + 17) { (it * 13).toByte() }
        val coldIds = listOf("a".repeat(32), "b".repeat(32))
        val galleryIds = listOf("c".repeat(32), "d".repeat(32))
        val group = ResourceGroup("e".repeat(32), coldIds, true, "Live", galleryIds)
        var prepared = 0
        var reads = 0
        var uploads = 0
        var aliasColdObject = false
        var staged: ByteArray? = null
        init {
            for ((i, id) in coldIds.withIndex()) {
                val bytes = if (i == 0) source else byteArrayOf(1, 2, 3)
                val out = ByteArrayOutputStream(); val p = ArchiveProof.from(Pos2.write(bytes.inputStream(), out, "file", "application/octet-stream", bytes.size.toLong()))
                val cold = ArchiveJob(id, "123", "root", "unused-phone-uri", "file", "application/octet-stream", bytes.size.toLong(),
                    ArchivePhase.VERIFIED, p, "cold-$id", "v1", 1)
                jobs[id] = cold; objects[cold.path] = out.toByteArray()
                jobs[galleryIds[i]] = ArchiveJob(galleryIds[i], "123", "root", "unused-phone-uri", "file", "application/octet-stream", bytes.size.toLong(),
                    zone = OriginalZone.GALLERY, groupId = group.id, coldId = id)
            }
        }
        val journal = object : ArchiveJournal {
            override fun load(id: String) = jobs.getValue(id)
            override fun save(job: ArchiveJob) { job.validate(); jobs[job.id] = job }
            override fun group(id: String) = group.takeIf { it.id == id }
        }
        val cloud = object : ArchiveCloud {
            override fun requireAccount(uid: String, rootId: String) { require(uid == "123" && rootId == "root") }
            override fun ensureColdFolders() {}
            override fun ensureGalleryFolders() {}
            override fun inspect(path: String): ArchiveRemote? = objects[path]?.let { bytes ->
                val id = jobs.values.single { it.path == path }.id
                ArchiveRemote(if (id in coldIds || aliasColdObject) "cold-${if (id in coldIds) id else coldIds[0]}" else "gallery-$id", "v1", bytes.size.toLong(), null)
            }
            override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) { uploads++; require(path !in objects); objects[path] = open().use { it.readBytes() } }
            override fun <T> readback(path: String, read: (InputStream) -> T): T { reads++; return objects.getValue(path).inputStream().use(read) }
        }
        val staging = object : ArchiveStaging {
            override fun prepare(job: ArchiveJob): ArchiveProof {
                prepared++; val cold = jobs.getValue(job.coldId!!)
                val out = ByteArrayOutputStream()
                val proof = OriginalRepack.write({ objects.getValue(cold.path).inputStream() }, out, cold.proof!!, job.name, job.mime)
                staged = out.toByteArray(); return ArchiveProof.from(proof)
            }
            override fun open(id: String) = staged!!.inputStream()
            override fun removeOwnedContainer(id: String) { staged = null }
        }
        fun step() = ColdArchive(journal, staging, cloud).step(galleryIds[0])
    }
    @Test fun `working copy waits for entire set and fully reads all cold resources first`() {
        val f = Fixture(); val second = f.jobs.getValue(f.coldIds[1]); f.jobs[second.id] = second.copy(phase = ArchivePhase.QUEUED)
        assertThrows(IllegalArgumentException::class.java) { f.step() }; assertEquals(0, f.prepared); assertEquals(0, f.uploads)
        f.jobs[second.id] = second
        assertEquals(ArchivePhase.PREPARED, f.step().phase); assertEquals(2, f.reads)
        f.step(); assertEquals(ArchivePhase.VERIFIED, f.step().phase)
        val working = f.jobs.getValue(f.galleryIds[0]); val cold = f.jobs.getValue(f.coldIds[0])
        assertEquals(cold.proof!!.originalSha256, working.proof!!.originalSha256)
        assertNotEquals(cold.proof!!.bundleId, working.proof!!.bundleId)
        assertNotEquals(cold.remoteId, working.remoteId)
        assertTrue(f.objects.containsKey(cold.path)); assertEquals(1, f.uploads)
    }
    @Test fun `corrupted companion prevents any working upload`() {
        val f = Fixture(); val bytes = f.objects.getValue(f.jobs.getValue(f.coldIds[1]).path); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { f.step() }; assertEquals(0, f.prepared); assertEquals(0, f.uploads)
    }
    @Test fun `alias to a cold physical object cannot become a verified working copy`() {
        val f = Fixture(); f.step(); f.step(); f.aliasColdObject = true
        assertThrows(IllegalArgumentException::class.java) { f.step() }
        assertEquals(ArchivePhase.UPLOAD_UNCERTAIN, f.jobs.getValue(f.galleryIds[0]).phase)
        assertNotNull(f.staged)
    }
    @Test fun `repack cannot accept plaintext when original authenticated footer fails`() {
        val bytes = ByteArray(1024 * 1024 + 1) { 42 }; val old = ByteArrayOutputStream()
        val proof = ArchiveProof.from(Pos2.write(bytes.inputStream(), old, "raw", "image/dng", bytes.size.toLong()))
        val damaged = old.toByteArray(); damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { OriginalRepack.write({ damaged.inputStream() }, ByteArrayOutputStream(), proof, "raw", "image/dng") }
        val good = ByteArrayOutputStream(); val rebuilt = OriginalRepack.write({ old.toByteArray().inputStream() }, good, proof, "raw", "image/dng")
        val restored = ByteArrayOutputStream(); Pos2.read(good.toByteArray().inputStream(), restored, rebuilt.originalBytes, rebuilt.originalSha256)
        assertArrayEquals(bytes, restored.toByteArray())
    }
    @Test fun `failed destination does not leave repack producer blocked`() {
        val old = ByteArrayOutputStream(); val bytes = ByteArray(3 * 1024 * 1024)
        val proof = ArchiveProof.from(Pos2.write(bytes.inputStream(), old, "video", "video/mp4", bytes.size.toLong()))
        val broken = object : OutputStream() { override fun write(b: Int) { throw java.io.IOException("closed destination") } }
        assertThrows(Exception::class.java) { OriginalRepack.write({ old.toByteArray().inputStream() }, broken, proof, "video", "video/mp4") }
    }
}
