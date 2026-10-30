package com.cielvitrine.archive.viewer

import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Deterministic encrypted byte fixtures; these are not claims about native MP4 decoding. */
class VerifiedMultipartReaderTest {
    private class Fixture(private val length: Int = 65536 * 65 + 13): AutoCloseable {
        val directory = Files.createTempDirectory("verified-reader-").toFile()
        val cache = OwnedViewCache(File(directory, "cache"), 16L * 1024 * 1024)
        val original = ByteArray(length) { ((it * 31 + it / 65536) and 255).toByte() }
        val objects = mutableMapOf<String, ByteArray>()
        val remotes = mutableMapOf<String, ArchiveRemote>()
        val reads = mutableListOf<String>()
        var transferred = 0L
        var account = true
        var afterRead: ((String) -> Unit)? = null
        var next = 1
        val cloud = object: ArchiveCloud {
            override fun requireAccount(uid: String, rootId: String) { require(account && uid == "123" && rootId == "root") }
            override fun ensureColdFolders() = error("read only")
            override fun inspect(path: String) = remotes[path]
            override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) = error("read only")
            override fun <T> readback(path: String, read: (InputStream) -> T): T {
                reads += path
                val bytes = objects.getValue(path)
                transferred += bytes.size
                val result = read(bytes.inputStream())
                afterRead?.invoke(path)
                return result
            }
        }
        fun encoded(bytes: ByteArray, id: String = (next++).toString(16).padStart(32, '0')): MultipartObject {
            val output = ByteArrayOutputStream()
            val proof = ArchiveProof.from(Pos2.write(bytes.inputStream(), output, "fixture.bin", "application/octet-stream"))
            val remote = ArchiveRemote(id, "v1", proof.containerBytes, proof.containerSha256)
            val ref = MultipartObject(id, proof, remote)
            objects[ref.path(OriginalZone.COLD)] = output.toByteArray()
            remotes[ref.path(OriginalZone.COLD)] = remote
            return ref
        }
        val passport = MultipartPassport(original.size.toLong(), hash(original), maxOf(1L, (length + 65535L) / 65536), 65536)
        val parts = (0 until passport.parts).map { index ->
            val start = (index * passport.partBytes).toInt()
            val bytes = original.copyOfRange(start, start + passport.length(index).toInt())
            MultipartPart(index, start.toLong(), bytes.size.toLong(), hash(bytes), encoded(bytes))
        }
        var last: MultipartObject? = null
        init {
            parts.chunked(64).forEachIndexed { index, pageParts ->
                last = encoded(MultipartFormat.pageBytes(MultipartPage(job = JOB, sha = passport.sha,
                    index = index.toLong(), previous = last, parts = pageParts)))
            }
        }
        val root = MultipartRoot(job = JOB, name = "fixture.bin", mime = "application/octet-stream",
            zone = OriginalZone.COLD, passport = passport, lastPage = last!!)
        val rootRef = encoded(MultipartFormat.rootBytes(root), JOB)
        val job = ArchiveJob(JOB, "123", "root", "local-source", root.name, root.mime, length.toLong(),
            ArchivePhase.VERIFIED, rootRef.proof.copy(originalBytes = passport.bytes, originalSha256 = passport.sha),
            rootRef.remote.id, rootRef.remote.modified, 1, containerExtension = ContainerExtension.BIN,
            transport = OriginalTransport.MULTIPART, multipart = MultipartCommit(rootRef.proof, passport.parts))
        fun reader(clear: Boolean = false, budget: ByteBudget = ByteBudget(64L * 1024 * 1024)) =
            VerifiedMultipartReader(job, cloud, cache, clearOnClose = clear, workBudget = budget)
        override fun close() { directory.deleteRecursively() }
        companion object { const val JOB = "ffffffffffffffffffffffffffffffff" }
    }
    @Test fun tailIndexAndSeekReadOnlyReferencedUnitsWithExactEof(): Unit = Fixture().use { f ->
        val budget = ByteBudget(64L * 1024 * 1024)
        f.reader(budget = budget).use { reader ->
            val tail = ByteArray(13)
            assertEquals(13, reader.readAt(f.passport.bytes - 13, tail, 0, 13))
            assertArrayEquals(f.original.takeLast(13).toByteArray(), tail)
            assertEquals(3, f.reads.size) // root, final page and final part; no entire original fetch
            assertTrue(f.transferred < f.original.size / 4)
            val buffer = ByteArray(19)
            assertEquals(7, reader.readAt(65529, buffer, 0, 19))
            assertArrayEquals(f.original.copyOfRange(65529, 65536), buffer.take(7).toByteArray())
            assertEquals(19, reader.readAt(65536, buffer, 0, 19))
            assertArrayEquals(f.original.copyOfRange(65536, 65555), buffer)
            assertEquals(-1, reader.readAt(f.passport.bytes, buffer, 0, 1))
            assertEquals(0, reader.readAt(f.passport.bytes, buffer, 0, 0))
            assertTrue(budget.used() > 0)
        }
        assertEquals(0L, budget.used())
    }
    @Test fun memoryAdmissionFailureDoesNotDiscardAnAuthenticatedCacheEntry(): Unit = Fixture(65549).use { f ->
        f.reader().use { it.readAt(65536, ByteArray(13), 0, 13) }
        val reads = f.reads.size
        val budget = ByteBudget(64L * 1024 * 1024)
        val occupied = budget.reserve(budget.maximum)
        try {
            f.reader(budget = budget).use { reader ->
                assertThrows(IllegalStateException::class.java) { reader.readAt(65536, ByteArray(13), 0, 13) }
            }
        } finally { occupied.close() }
        f.reader(budget = budget).use { assertEquals(13, it.readAt(65536, ByteArray(13), 0, 13)) }
        assertEquals(reads, f.reads.size)
        assertEquals(0L, budget.used())
    }
    @Test fun mixedResourcePreflightReadsMultipartSiblingsAndRejectsTheirDamage() {
        for(corrupt in listOf(false,true))Fixture(65549).use { f ->
            val data=byteArrayOf(1,2,3)
            val ref=f.encoded(data)
            val single=f.job.copy(id=ref.id,sourceBytes=data.size.toLong(),transport=OriginalTransport.SINGLE,
                multipart=null,proof=ref.proof,remoteId=ref.remote.id,remoteModified=ref.remote.modified)
            val galleryId="e".repeat(32);val groupId="d".repeat(32)
            var gallery=single.copy(id=galleryId,zone=OriginalZone.GALLERY,groupId=groupId,coldId=single.id,
                phase=ArchivePhase.QUEUED,proof=null,remoteId=null,remoteModified=null,verifiedAt=null)
            val group=ResourceGroup(groupId,listOf(single.id,f.job.id),true,"mixed",listOf(galleryId,"c".repeat(32)))
            val journal=object:ArchiveJournal {
                override fun load(id:String)=when(id){single.id->single;f.job.id->f.job;galleryId->gallery;else->error("Unknown fixture")}
                override fun save(job:ArchiveJob){gallery=job}
                override fun group(id:String)=group.also{require(id==groupId)}
            }
            var prepared=false
            val staging=object:ArchiveStaging {
                override fun prepare(job:ArchiveJob):ArchiveProof {
                    prepared=true;return ArchiveProof.from(Pos2.write(data.inputStream(),ByteArrayOutputStream(),job.name,job.mime))
                }
                override fun open(id:String):InputStream=error("No PUT expected")
                override fun removeOwnedContainer(id:String)=error("No cleanup expected")
            }
            if(corrupt)f.objects.getValue(f.parts.last().objectRef.path(OriginalZone.COLD)).let{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
            val engine=ColdArchive(journal,staging,f.cloud)
            if(corrupt) {
                assertThrows(Exception::class.java){engine.step(galleryId)}
                assertFalse(prepared);assertEquals(ArchivePhase.QUEUED,gallery.phase)
            } else {
                assertEquals(ArchivePhase.PREPARED,engine.step(galleryId).phase)
                assertTrue(f.parts.all{it.objectRef.path(OriginalZone.COLD) in f.reads})
            }
        }
    }
    @Test fun tamperOrRevisionChangeNeverReachesDisplayBuffer() {
        for (changeRevision in listOf(false, true)) Fixture(65549).use { f ->
            val path = f.parts.last().objectRef.path(OriginalZone.COLD)
            if (changeRevision) f.afterRead = { if (it == path) f.remotes[path] = f.remotes.getValue(path).copy(modified = "v2") }
            else f.objects.getValue(path).let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val target = ByteArray(13) { 91 }
            f.reader().use { reader -> assertThrows(Exception::class.java) { reader.readAt(65536, target, 0, 13) } }
            assertArrayEquals(ByteArray(13) { 91 }, target)
            assertEquals(ArchivePhase.VERIFIED, f.job.phase) // viewing never changes archive authority
        }
    }
    @Test fun corruptedWarmCacheCannotBypassAuthenticationAndAccountIsStillRequired(): Unit = Fixture(65549).use { f ->
        f.reader().use { it.readAt(65536, ByteArray(13), 0, 13) }
        val n = f.reads.size
        val part = f.parts.last().objectRef
        val snapshot = ViewerSnapshot(f.job.uid!!, f.job.rootId!!, f.job.id,
            f.job.multipart!!.root.containerSha256, "${f.job.remoteId}:${f.job.remoteModified}", "multipart-original-v1")
        f.cache.acquire(snapshot.key("${part.id}:${part.proof.containerSha256}:${part.remote.id}:${part.remote.modified}"))!!.use {
            RandomAccessFile(it.file, "rw").use { file -> file.seek(file.length() - 1); val byte=file.read(); file.seek(file.length() - 1); file.write(byte xor 1) }
        }
        f.reader().use { reader -> assertThrows(Exception::class.java) { reader.readAt(65536, ByteArray(13), 0, 13) } }
        assertEquals(n, f.reads.size)
        f.account = false
        f.reader().use { reader -> assertThrows(Exception::class.java) { reader.open() } }
    }
    @Test fun closeCancelsAReadThatIgnoresInterruptionAndReleasesEveryReservation(): Unit = Fixture(65549).use { f ->
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val token = ViewCancellation(); val budget = ByteBudget(64L * 1024 * 1024)
        val reader = VerifiedMultipartReader(f.job, f.cloud, f.cache, token, true, budget)
        val path = f.parts.last().objectRef.path(OriginalZone.COLD)
        f.afterRead = { if (it == path) { entered.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)) } }
        val pool = Executors.newSingleThreadExecutor()
        val target = ByteArray(13) { 42 }
        try {
            val future = pool.submit { assertThrows(InterruptedIOException::class.java) { reader.readAt(65536, target, 0, 13) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); token.cancel(); finish.countDown()
            future.get(5, TimeUnit.SECONDS); reader.close()
            assertArrayEquals(ByteArray(13) { 42 }, target); assertEquals(0L, budget.used()); assertEquals(0L, f.cache.used())
        } finally { finish.countDown(); reader.close(); pool.shutdownNow() }
    }
    @Test fun completeRestoreAndRestartKeepExactOriginalAndPinnedCache(): Unit = Fixture(65549).use { f ->
        val restored = ByteArrayOutputStream()
        f.reader().use { it.restore(restored) }
        assertArrayEquals(f.original, restored.toByteArray())
        val restarted = OwnedViewCache(File(f.directory, "cache"), 16L * 1024 * 1024)
        val reads = f.reads.size
        VerifiedMultipartReader(f.job, f.cloud, restarted).use { it.restore(ByteArrayOutputStream()) }
        assertEquals(reads, f.reads.size)
    }
    @Test fun insufficientWorkingMemoryFailsBeforeAnyPartIsExposed(): Unit = Fixture(65549).use { f ->
        val budget = ByteBudget(1)
        f.reader(budget = budget).use { reader -> assertThrows(IllegalStateException::class.java) { reader.open() } }
        assertEquals(0L, budget.used())
    }
    @Test fun positionsPastIntRangeFetchTheTailWithoutDownloadingEarlierParts(): Unit = Fixture(65549).use { f ->
        val offset=5L*1024*1024*1024
        val passport=MultipartPassport(offset+13,"a".repeat(64),641)
        val last=f.parts.last().copy(index=640,offset=offset)
        val page=f.encoded(MultipartFormat.pageBytes(MultipartPage(job=f.job.id,sha=passport.sha,
            index=10,previous=f.root.lastPage,parts=listOf(last))))
        val root=f.root.copy(passport=passport,lastPage=page)
        val ref=f.encoded(MultipartFormat.rootBytes(root),f.job.id)
        val job=f.job.copy(sourceBytes=passport.bytes,proof=ref.proof.copy(originalBytes=passport.bytes,originalSha256=passport.sha),
            remoteId=ref.remote.id,remoteModified=ref.remote.modified,multipart=MultipartCommit(ref.proof,641))
        VerifiedMultipartReader(job,f.cloud,f.cache).use { reader ->
            val target=ByteArray(13)
            assertEquals(13,reader.readAt(offset,target,0,13))
            assertArrayEquals(f.original.takeLast(13).toByteArray(),target)
            assertEquals(3,f.reads.size)
        }
    }
    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
