package com.cielvitrine.archive.viewer

import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest

/** Real POS2 encryption and production reader/cache. No replacement implementation. */
class ResumeContractBenchmark {
    class Fixture(
        val directory: File = Files.createTempDirectory("ciel-resume-contract-").toFile(),
        val persistent: Boolean = false
    ) : AutoCloseable {
        init { directory.mkdirs() }
        val cacheDir = File(directory, "cache")
        val original = ByteArray(65536) { ((it * 31 + it / 257) and 255).toByte() }
        val objects = mutableMapOf<String, ByteArray>()
        val remotes = mutableMapOf<String, ArchiveRemote>()
        val offsets = mutableListOf<Long>()
        var next = 1
        var account = true
        var fault = false
        var ignoreRange = false
        var ignoredBodyReads = 0
        var corruptRange = false
        var transferred = 0L
        var onPartRead: (() -> Unit)? = null
        var onPartOpen: (() -> Unit)? = null
        fun encoded(bytes: ByteArray, id: String = (next++).toString(16).padStart(32, '0')): MultipartObject {
            val meta = File(directory, "fixture-$id.json")
            if (persistent && meta.exists()) {
                val ref = kotlinx.serialization.json.Json.decodeFromString(MultipartObject.serializer(), meta.readText())
                val raw = File(directory, "fixture-$id.bin").readBytes()
                require(sha(raw) == ref.proof.containerSha256 && sha(bytes) == ref.proof.originalSha256)
                objects[ref.path(OriginalZone.COLD)] = raw; remotes[ref.path(OriginalZone.COLD)] = ref.remote
                return ref
            }
            val out = ByteArrayOutputStream()
            val proof = ArchiveProof.from(Pos2.write(bytes.inputStream(), out, "fixture.bin", "application/octet-stream"))
            val remote = ArchiveRemote(id, "v1", proof.containerBytes, proof.containerSha256)
            val ref = MultipartObject(id, proof, remote)
            objects[ref.path(OriginalZone.COLD)] = out.toByteArray(); remotes[ref.path(OriginalZone.COLD)] = remote
            if (persistent) {
                File(directory, "fixture-$id.bin").writeBytes(out.toByteArray())
                meta.writeText(kotlinx.serialization.json.Json.encodeToString(MultipartObject.serializer(), ref))
            }
            return ref
        }
        val originalFile = File(directory, "original.bin").apply { if (!exists()) writeBytes(original) }
        val partRef = encoded(original)
        val partPath = partRef.path(OriginalZone.COLD)
        val passport = MultipartPassport(original.size.toLong(), sha(original), 1, 65536)
        val pageRef = encoded(MultipartFormat.pageBytes(MultipartPage(job = JOB, sha = passport.sha, index = 0,
            previous = null, parts = listOf(MultipartPart(0, 0, original.size.toLong(), sha(original), partRef)))))
        val root = MultipartRoot(job = JOB, name = "fixture.bin", mime = "application/octet-stream",
            zone = OriginalZone.COLD, passport = passport, lastPage = pageRef)
        val rootRef = encoded(MultipartFormat.rootBytes(root), JOB)
        val job = ArchiveJob(JOB, "123", "root", originalFile.absolutePath, root.name, root.mime, original.size.toLong(),
            ArchivePhase.VERIFIED, rootRef.proof.copy(originalBytes = passport.bytes, originalSha256 = passport.sha),
            rootRef.remote.id, rootRef.remote.modified, 1, containerExtension = ContainerExtension.BIN,
            transport = OriginalTransport.MULTIPART, multipart = MultipartCommit(rootRef.proof, 1))
        val cloud = object : RangedArchiveCloud {
            override fun requireAccount(uid: String, rootId: String) { require(account && uid == "123" && rootId == "root") }
            override fun ensureColdFolders() = error("read only")
            override fun inspect(path: String) = remotes[path]
            override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) = error("read only")
            override fun <T> readback(path: String, read: (InputStream) -> T): T = body(path, 0).use(read)
            override fun openRange(path: String, offset: Long, expected: ArchiveRemote): ArchiveRangeResult {
                if (path == partPath) offsets += offset
                val ignored = path == partPath && offset > 0 && ignoreRange
                val returned = if (ignored) 0L else offset
                val input = if (ignored) object : InputStream() {
                    override fun read(): Int { ignoredBodyReads++; throw IOException("invalid range body must not be read") }
                } else body(path, offset)
                return ArchiveRangeResult(remotes.getValue(path), returned, objects.getValue(path).size.toLong(), input)
            }
        }
        private fun body(path: String, offset: Long): InputStream {
            if (path == partPath) onPartOpen?.invoke()
            val bytes = objects.getValue(path).copyOf()
            if (path == partPath && offset > 0 && corruptRange) bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            return object : InputStream() {
                var position = offset.toInt(); var delivered = 0
                override fun read(): Int {
                    if (path == partPath) {
                        if (fault && delivered >= 4096) { fault = false; throw IOException("injected connection loss") }
                        onPartRead?.invoke(); transferred += if (position < bytes.size) 1 else 0
                    }
                    if (position >= bytes.size) return -1
                    delivered++; return bytes[position++].toInt() and 255
                }
                override fun read(buffer: ByteArray, start: Int, count: Int): Int {
                    if (count == 0) return 0
                    var n = 0
                    while (n < count) {
                        // Do not hide a fault after a short read in InputStream's default implementation.
                        if (path == partPath && fault && delivered >= 4096) {
                            if (n > 0) return n
                            fault = false; throw IOException("injected connection loss")
                        }
                        val byte = read(); if (byte < 0) return if (n == 0) -1 else n
                        buffer[start + n++] = byte.toByte()
                    }
                    return n
                }
            }
        }
        fun reader(cache: OwnedViewCache = OwnedViewCache(cacheDir, 16L * 1024 * 1024),
                   token: ViewCancellation = ViewCancellation(), cloudInput: ArchiveCloud = cloud,
                   budget: ByteBudget = ByteBudget(64L * 1024 * 1024)) =
            VerifiedMultipartReader(job, cloudInput, cache, token, false, budget)
        fun interrupt() {
            fault = true; val target = ByteArray(37) { 91 }
            reader().use { r -> assertThrows(IOException::class.java) { r.readAt(0, target, 0, target.size) } }
            assertArrayEquals(ByteArray(37) { 91 }, target)
            assertEquals(4096L, transferred, "fault must happen after a real nonempty encrypted prefix")
        }
        fun readExact(): ByteArray {
            val target = ByteArray(original.size)
            reader().use { assertEquals(target.size, it.readAt(0, target, 0, target.size)) }
            assertArrayEquals(original, target); return target
        }
        fun assertResumed() = assertTrue(offsets.any { it > 0 }, "production reader must request retained encrypted prefix offset")
        override fun close() { if (!persistent) directory.deleteRecursively() }
        companion object { const val JOB = "ffffffffffffffffffffffffffffffff" }
    }
    @Test fun R01_restartResumesAuthenticatedContainer(): Unit = Fixture().use { f ->
        f.interrupt(); f.readExact(); f.assertResumed()
        assertTrue(f.transferred <= f.objects.getValue(f.partPath).size + 1024L, "no complete redundant container download")
    }
    @Test fun R02_ignoredRangeIsRejectedBeforeBody(): Unit = Fixture().use { f ->
        f.interrupt(); f.ignoreRange = true
        // A safe failure or a verified clean restart is allowed; invalid body consumption is not.
        val target = ByteArray(37) { 91 }
        try { f.reader().use { it.readAt(0, target, 0, target.size) } }
        catch (_: Exception) { assertArrayEquals(ByteArray(37) { 91 }, target) }
        assertTrue(target.contentEquals(ByteArray(37) { 91 }) || target.contentEquals(f.original.copyOf(37)))
        f.assertResumed(); assertEquals(0, f.ignoredBodyReads)
        f.ignoreRange = false; f.readExact()
    }
    @Test fun R03_corruptResumedBytesNeverPublish(): Unit = Fixture().use { f ->
        f.interrupt(); f.corruptRange = true; val target = ByteArray(37) { 91 }
        try { f.reader().use { it.readAt(0, target, 0, target.size) } }
        catch (_: Exception) { assertArrayEquals(ByteArray(37) { 91 }, target) }
        f.assertResumed()
        assertTrue(target.contentEquals(ByteArray(37) { 91 }) || target.contentEquals(f.original.copyOf(37)))
        f.corruptRange = false; f.readExact()
    }
    @Test fun R04_accountChangeCannotPublish(): Unit = Fixture().use { f ->
        val target = ByteArray(37) { 91 }; f.onPartRead = { f.account = false }
        f.reader().use { assertThrows(Exception::class.java) { it.readAt(0, target, 0, target.size) } }
        assertArrayEquals(ByteArray(37) { 91 }, target); assertEquals(ArchivePhase.VERIFIED, f.job.phase)
        f.onPartRead = null; f.account = true; f.readExact()
    }
    @Test fun R05_cancelDuringResumeReleasesWorkBudget(): Unit = Fixture().use { f ->
        f.interrupt(); val token = ViewCancellation(); val budget = ByteBudget(64L * 1024 * 1024)
        val target = ByteArray(37) { 91 }; f.onPartRead = { token.cancel() }
        f.reader(token = token, budget = budget).use { assertThrows(InterruptedIOException::class.java) { it.readAt(0, target, 0, target.size) } }
        f.assertResumed(); assertArrayEquals(ByteArray(37) { 91 }, target); assertEquals(0L, budget.used())
        f.onPartRead = null; f.readExact()
    }
    @Test fun R06_changedRevisionCannotMixContainers(): Unit = Fixture().use { f ->
        f.interrupt(); val old = f.remotes.getValue(f.partPath)
        f.remotes[f.partPath] = old.copy(modified = "v2"); val target = ByteArray(37) { 91 }
        f.reader().use { assertThrows(Exception::class.java) { it.readAt(0, target, 0, target.size) } }
        assertArrayEquals(ByteArray(37) { 91 }, target)
        f.remotes[f.partPath] = old; f.readExact()
    }
    @Test fun R07_legacyCloudRemainsSupported(): Unit = Fixture().use { f ->
        val legacy = object : ArchiveCloud by f.cloud {} // deliberately does not expose the ranged interface
        val target = ByteArray(f.original.size)
        f.reader(cloudInput = legacy).use { assertEquals(target.size, it.readAt(0, target, 0, target.size)) }
        assertArrayEquals(f.original, target); assertTrue(f.offsets.isEmpty())
    }
    companion object { private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
}
