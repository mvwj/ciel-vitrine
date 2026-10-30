package com.cielvitrine.archive.viewer

import com.cielvitrine.archive.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Logical original-byte address space, pinned to one fully verified root. No local upload journal
 * can authorize a part: its reference is always reached through the authenticated root/page chain. */
class VerifiedMultipartReader(private val job: ArchiveJob, private val cloud: ArchiveCloud,
    private val cache: OwnedViewCache, private val cancel: ViewCancellation = ViewCancellation(),
    private val clearOnClose: Boolean = true,
    private val workBudget: ByteBudget = ByteBudget(64L * 1024 * 1024)): AutoCloseable {
    private var root: MultipartRoot? = null
    private var loadedIndex = -1L
    private var loaded: ByteArray? = null
    private var loadedAllocation: ByteBudget.Reservation? = null
    private val pages = linkedMapOf<String, MultipartPage>()
    private val touched = mutableSetOf<String>()
    private val snapshot = ViewerSnapshot(job.uid!!, job.rootId!!, job.id, job.multipart!!.root.containerSha256,
        "${job.remoteId}:${job.remoteModified}", "multipart-original-v1")
    @Synchronized fun open(): MultipartRoot {
        cancel.check(); job.validate()
        require(job.phase == ArchivePhase.VERIFIED && job.transport == OriginalTransport.MULTIPART)
        cloud.requireAccount(job.uid!!, job.rootId!!)
        root?.let { pinned -> return cancel.publish { pinned } }
        val proof = job.multipart!!.root
        require(proof.originalBytes <= MultipartFormat.MANIFEST_MAX_BYTES && proof.containerBytes <= MultipartFormat.MANIFEST_MAX_BYTES + 262144)
        val remote = cloud.inspect(job.path) ?: error("Root unavailable")
        require(remote.id == job.remoteId && remote.modified == job.remoteModified)
        val ref = MultipartObject(job.id, proof, remote).also { it.validate() }
        return decode(ref) { bytes, _ -> MultipartFormat.root(bytes) }.also { r ->
            val expected = job.proof!!
            require(r.job == job.id && r.name == job.name && r.mime == job.mime && r.zone == job.zone &&
                r.passport.bytes == expected.originalBytes && r.passport.sha == expected.originalSha256 &&
                r.passport.times == expected.times && r.passport.parts == job.multipart!!.parts)
            cancel.publish { root = r }
        }
    }
    private fun <T> decode(ref: MultipartObject, accept: (ByteArray, ByteBudget.Reservation) -> T): T {
        cancel.check(); ref.validate()
        require(ref.proof.originalBytes <= MultipartFormat.PART_BYTES &&
            ref.proof.containerBytes <= MultipartFormat.PART_BYTES.toLong() + 262144)
        val key = snapshot.key("${ref.id}:${ref.proof.containerSha256}:${ref.remote.id}:${ref.remote.modified}")
        // Tracking cleanup must not grow with the duration/size of a video. Older keys can be
        // retired immediately: the cache itself waits for every outstanding lease.
        if (key !in touched && touched.size >= 1024) {
            val oldest = touched.first(); touched.remove(oldest)
            if (clearOnClose) cache.disposeWhenIdle(oldest)
        }
        touched += key
        val lease = cache.acquire(key) ?: if (cloud is RangedArchiveCloud) {
            cache.putResumable(key, ref.proof.containerBytes, cancel, produce = { offset, output ->
                cloud.requireAccount(job.uid!!, job.rootId!!); cancel.check()
                require(cloud.inspect(ref.path(job.zone)) == ref.remote)
                cloud.openRange(ref.path(job.zone), offset, ref.remote).use { response ->
                    cancel.check()
                    // Check the response envelope before even the first read of its body.
                    require(response.remote == ref.remote && response.offset == offset &&
                        response.totalBytes == ref.proof.containerBytes) { "Incorrect container range" }
                    copyContainer(response.input, output, offset, ref.proof.containerBytes)
                }
            }, verify = { file ->
                verifyContainer(file, ref)
                require(cloud.inspect(ref.path(job.zone)) == ref.remote)
                cloud.requireAccount(job.uid!!, job.rootId!!); cancel.check()
            })
        } else run {
            require(cloud.inspect(ref.path(job.zone)) == ref.remote)
            cache.put(key, ref.proof.containerBytes, cancel) { output ->
                // The callback writes only an unpublished owned file. No display sink receives plaintext.
                cloud.readback(ref.path(job.zone)) { input ->
                    val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var bytes = 0L
                    while (true) {
                        cancel.check(); val n = input.read(buffer); if (n < 0) break
                        require(n > 0 && n <= ref.proof.containerBytes - bytes)
                        hash.update(buffer, 0, n); output.write(buffer, 0, n); bytes += n
                    }
                    require(bytes == ref.proof.containerBytes && hex(hash.digest()) == ref.proof.containerSha256)
                }
                require(cloud.inspect(ref.path(job.zone)) == ref.remote)
                cloud.requireAccount(job.uid!!, job.rootId!!); cancel.check()
            }
        }
        try { return workBudget.reserveForRead(32L * 1024 * 1024,cancel).use { scratch ->
            // A cache hit (including one recovered after process death) is not a proof.
            val bytes = try {
                verifyContainer(lease.file, ref)
                val output = ByteArrayOutputStream(ref.proof.originalBytes.toInt())
                lease.file.inputStream().use { MultipartFormat.decodeObject(ref, it, output) }
                output.toByteArray()
            } catch (error: Exception) {
                if (error !is InterruptedIOException) cache.discard(key)
                throw error
            }
            try { cancel.check(); cloud.requireAccount(job.uid!!, job.rootId!!); accept(bytes,scratch) }
            finally { bytes.fill(0) }
        } }
        finally { lease.close() }
    }
    private fun copyContainer(input: InputStream, output: OutputStream, offset: Long, total: Long) {
        val buffer = ByteArray(65536); var bytes = offset
        while (true) {
            cancel.check()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), total - bytes + 1).toInt())
            if (n < 0) {
                if (bytes != total) throw IOException("Truncated container range")
                return
            }
            require(n > 0 && n <= total - bytes) { "Oversized container range" }
            output.write(buffer, 0, n); bytes += n
        }
    }
    private fun verifyContainer(file: File, ref: MultipartObject) {
        try {
            require(file.length() == ref.proof.containerBytes) { "Incorrect cached container length" }
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            var bytes = 0L
            file.inputStream().use { input ->
                while (true) {
                    cancel.check(); val n = input.read(buffer); if (n < 0) break
                    require(n > 0 && n <= ref.proof.containerBytes - bytes)
                    digest.update(buffer, 0, n); bytes += n
                }
            }
            require(bytes == ref.proof.containerBytes && hex(digest.digest()) == ref.proof.containerSha256) {
                "Cached container digest mismatch"
            }
        } catch (error: Exception) {
            // An observed disk integrity fault may have affected other cached units.
            // Admission, cancellation and account errors never enter this verifier catch.
            if(error !is InterruptedIOException)cache.discardAll()
            throw error
        }
    }
    private fun part(index: Long, r: MultipartRoot): MultipartPart {
        var ref: MultipartObject? = r.lastPage; var expectedIndex = r.pages - 1
        while (ref != null) {
            cancel.check()
            val current = ref
            require(current.proof.originalBytes <= MultipartFormat.MANIFEST_MAX_BYTES)
            val key = "${current.id}:${current.proof.containerSha256}"
            val page = pages[key] ?: decode(current) { bytes, _ -> MultipartFormat.page(bytes, r) }.also {
                if (pages.size >= 2) pages.remove(pages.keys.first())
                pages[key] = it
            }
            require(page.index == expectedIndex)
            page.parts.firstOrNull { it.index == index }?.let { return it }
            ref = page.previous; expectedIndex--
        }
        error("Part absent from pinned manifest")
    }
    @Synchronized fun bytes(index: Long): ByteArray {
        cancel.check(); val r = open(); require(index in 0 until r.passport.parts)
        if (index == loadedIndex) return cancel.publish { loaded!! }
        val part = part(index, r).also { it.validate(r.passport) }
        return decode(part.objectRef) { decoded, scratch ->
            require(decoded.size.toLong() == part.bytes && hash(decoded) == part.sha)
            cancel.check()
            val allocation = scratch.detach(decoded.size.toLong())
            val copy = try { decoded.copyOf() } catch (error: Throwable) { allocation.close(); throw error }
            try {
                cloud.requireAccount(job.uid!!, job.rootId!!)
                cancel.publish {
                    loaded?.fill(0); loadedAllocation?.close()
                    loaded = copy; loadedAllocation = allocation; loadedIndex = index
                    copy
                }
            } catch (error: Throwable) { copy.fill(0); allocation.close(); throw error }
        }
    }
    @Synchronized fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        val p = open().passport; require(position in 0..p.bytes)
        if (length == 0) return 0
        if (position == p.bytes) return -1
        val bytes = bytes(position / p.partBytes); val start = (position % p.partBytes).toInt()
        val n = minOf(length, bytes.size - start); require(n > 0)
        cloud.requireAccount(job.uid!!, job.rootId!!)
        return cancel.publish {
            bytes.copyInto(buffer, offset, start, start + n); n
        }
    }
    @Synchronized fun restore(output: OutputStream) {
        val p = open().passport; val hash = MessageDigest.getInstance("SHA-256"); var written = 0L
        for (index in 0 until p.parts) { val bytes = bytes(index); hash.update(bytes); output.write(bytes); written += bytes.size }
        require(written == p.bytes && hex(hash.digest()) == p.sha); cancel.check(); output.flush()
    }
    override fun close() {
        cancel.cancel()
        synchronized(this) {
            loaded?.fill(0); loaded = null; loadedIndex = -1; loadedAllocation?.close(); loadedAllocation = null; pages.clear()
            if (clearOnClose) touched.forEach(cache::disposeWhenIdle)
            touched.clear(); root = null
        }
    }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
