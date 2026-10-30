package com.cielvitrine.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.security.MessageDigest

/** Runs on Android, actual MultipartSession + ViewerRuntime, fixture cloud, no account or remote network. */
@RunWith(AndroidJUnit4::class)
class ResumeViewerDeviceBenchmark {
    @Test fun R08D_actualPlaybackSessionRetainsRangeCapability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        val original = ByteArray(65536) { (it * 31).toByte() }
        val objects = mutableMapOf<String, ByteArray>(); val remotes = mutableMapOf<String, ArchiveRemote>(); var next = 1
        fun encoded(bytes: ByteArray, objectId: String = (next++).toString(16).padStart(32, '0')): MultipartObject {
            val out = ByteArrayOutputStream(); val proof = ArchiveProof.from(Pos2.write(bytes.inputStream(), out, "fixture.bin", "application/octet-stream"))
            val ref = MultipartObject(objectId, proof, ArchiveRemote(objectId, "v1", proof.containerBytes, proof.containerSha256))
            objects[ref.path(OriginalZone.COLD)] = out.toByteArray(); remotes[ref.path(OriginalZone.COLD)] = ref.remote; return ref
        }
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val part = encoded(original); val partPath = part.path(OriginalZone.COLD)
        val passport = MultipartPassport(original.size.toLong(), hash(original), 1, 65536)
        val page = encoded(MultipartFormat.pageBytes(MultipartPage(job = id, sha = passport.sha, index = 0, previous = null,
            parts = listOf(MultipartPart(0, 0, original.size.toLong(), hash(original), part)))))
        val root = MultipartRoot(job = id, name = "fixture.bin", mime = "application/octet-stream", zone = OriginalZone.COLD, passport = passport, lastPage = page)
        val rootRef = encoded(MultipartFormat.rootBytes(root), id)
        val directory = File(context.cacheDir, "benchmark-$id").apply { mkdirs() }
        val source = File(directory, "local-original").apply { writeBytes(original) }
        val job = ArchiveJob(id, "123", "root", source.absolutePath, root.name, root.mime, original.size.toLong(), ArchivePhase.VERIFIED,
            rootRef.proof.copy(originalBytes = passport.bytes, originalSha256 = passport.sha), rootRef.remote.id, "v1", 1,
            containerExtension = ContainerExtension.BIN, transport = OriginalTransport.MULTIPART, multipart = MultipartCommit(rootRef.proof, 1))
        job.validate() // Positive fixture precondition, before production playback.
        var fault = true; val offsets = mutableListOf<Long>()
        val cloud = object : RangedArchiveCloud {
            override fun requireAccount(uid: String, rootId: String) { require(uid == "123" && rootId == "root") }
            override fun ensureColdFolders() = error("read only")
            override fun inspect(path: String) = remotes[path]
            override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) = error("read only")
            fun body(path: String, offset: Long): InputStream = object : InputStream() {
                val raw = objects.getValue(path); var p = offset.toInt(); var n = 0
                override fun read(): Int {
                    if (path == partPath && fault && n >= 4096) { fault = false; throw IOException("injected disconnect") }
                    if (p == raw.size) return -1
                    n++; return raw[p++].toInt() and 255
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    if (path == partPath && fault && n >= 4096) { fault = false; throw IOException("injected disconnect") }
                    val count = minOf(len, raw.size-p, if (path == partPath && fault) 4096-n else Int.MAX_VALUE)
                    if (count == 0) return -1
                    raw.copyInto(b, off, p, p+count); p += count; n += count; return count
                }
            }
            override fun <T> readback(path: String, read: (InputStream) -> T) = body(path, 0).use(read)
            override fun openRange(path: String, offset: Long, expected: ArchiveRemote): ArchiveRangeResult {
                if (path == partPath) offsets += offset
                return ArchiveRangeResult(remotes.getValue(path), offset, objects.getValue(path).size.toLong(), body(path, offset))
            }
        }
        try {
            val target = ByteArray(37) { 91 }; var failed = false
            try { MultipartSession(context, job, cloud, directory, false).use { it.readAt(0, target, 0, target.size) } }
            catch (_: IOException) { failed = true }
            assertTrue("fault did not execute", failed); assertArrayEquals(ByteArray(37) { 91 }, target)
            MultipartSession(context, job, cloud, directory, false).use { assertEquals(target.size, it.readAt(0, target, 0, target.size)) }
            assertArrayEquals(original.copyOf(37), target); assertTrue("Android session did not resume", offsets.any { it > 0 })
            assertArrayEquals(original, source.readBytes())
        } finally {
            // Only test-owned viewer cache entries and fixture original; never call global clearIdle().
            directory.deleteRecursively()
        }
    }
}
