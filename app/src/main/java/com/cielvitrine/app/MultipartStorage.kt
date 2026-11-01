package com.cielvitrine.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.security.MessageDigest

/** One small encrypted record per part; neither a file-size-proportional array nor a giant job payload. */
internal class AndroidMultipartStore(context: Context, val job: ArchiveJob,
    base: File = File(context.noBackupFilesDir, "multipart"), alias: String = "ciel.multipart.journal.v1") : MultipartJournal {
    private val directory = File(base, job.id)
    private val cipher = DeviceCipher(alias, "${context.packageName}:multipart-v1")
    private val binding = tileHash("${job.id}:${job.uid}:${job.rootId}:${job.source}:${job.sourceStamp}:${job.name}:${job.mime}:${job.zone}".toByteArray())
    private inline fun <reified T> read(name: String): T? {
        val f = AtomicFile(File(directory, "$name.enc"))
        if(!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return null
        val sealed = f.openRead().use { it.boundedBytes(262144 + 28) }
        val plain = cipher.open("$binding:$name", sealed)
        return try { Json.decodeFromString<T>(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    private inline fun <reified T> write(name: String, value: T) {
        check(directory.isDirectory || directory.mkdirs())
        val plain = Json.encodeToString(value).toByteArray()
        val sealed = try { cipher.seal("$binding:$name", plain) } finally { plain.fill(0) }
        val atomic = AtomicFile(File(directory, "$name.enc")); val output = atomic.startWrite()
        try { output.write(sealed); atomic.finishWrite(output) } catch(e: Exception) { atomic.failWrite(output); throw e }
    }
    init { require(job.id.matches(MultipartFormat.ID) && job.transport == OriginalTransport.MULTIPART) }
    override fun state(): MultipartState? = read<MultipartState>("state")?.also { it.validate() }
    override fun save(state: MultipartState) { state.validate(); write("state", state) }
    override fun part(index: Long): PlannedPart { require(index >= 0); return read<PlannedPart>("part-$index")!!.also { require(it.index == index) } }
    fun partIfExists(index: Long): PlannedPart? { require(index >= 0); return read<PlannedPart>("part-$index")?.also { require(it.index == index) } }
    override fun save(part: PlannedPart) { require(part.index >= 0 && part.offset >= 0 && part.bytes in 0..MultipartFormat.PART_BYTES && part.sha.matches(MultipartFormat.SHA)); write("part-${part.index}", part) }
}

internal fun InputStream.boundedBytes(maximum: Int): ByteArray {
    val out = ByteArrayOutputStream(); val buffer = ByteArray(65536)
    while(true) { val n = read(buffer); if(n < 0) break; require(n > 0 && n <= maximum - out.size()); out.write(buffer, 0, n) }
    return out.toByteArray()
}

internal class AndroidMultipartStaging(private val context: Context, private val job: ArchiveJob,
    private val jobs: ArchiveJournal, private val cloud: ArchiveCloud, private val stopped: () -> Boolean = { false },
    private val directory: File = File(context.noBackupFilesDir, "multipart-staging")) : MultipartStaging {
    private val inventory = MediaInventory(context)
    private val metrics = PerformanceJournal(context)
    private fun stage(id: String): File { require(id.matches(MultipartFormat.ID)); check(directory.isDirectory || directory.mkdirs()); return File(directory, "$id.stage") }
    private fun reserve() {
        check(directory.isDirectory || directory.mkdirs())
        val held = directory.listFiles()?.sumOf { it.length() } ?: 0L
        check(held + MultipartFormat.PART_BYTES + 262144 <= 128L * 1024 * 1024 && directory.usableSpace > MultipartFormat.PART_BYTES + 64L * 1024 * 1024) { "Недостаточно временного места" }
    }
    private fun checkSource() { check(!stopped()); if(job.zone != OriginalZone.GALLERY) inventory.requireUnchanged(job) }
    private fun cold(fullReadback:Boolean=false): ArchiveJob {
        val group = jobs.group(job.groupId!!) ?: error("Resource group missing"); group.validate()
        require(group.declaredComplete && group.coldIds[group.galleryIds.indexOf(job.id)] == job.coldId)
        val members = group.coldIds.map(jobs::load)
        require(members.all { it.phase == ArchivePhase.VERIFIED && it.uid == job.uid && it.rootId == job.rootId })
        members.forEach { val p = it.proof!!; val r = cloud.inspect(it.path)
            require(r != null && r.id == it.remoteId && r.modified == it.remoteModified && r.bytes == p.containerBytes && (r.sha256 == null || r.sha256.equals(p.containerSha256, true)))
            if(fullReadback && it.id!=job.coldId)verifyArchivedOriginal(it,cloud,stopped,context.cacheDir)
        }
        return members.single { it.id == job.coldId }
    }
    private fun coldPart(source: ArchiveJob, index: Long): ByteArray {
        val store = AndroidMultipartStore(context, source); val passport = store.state()!!.passport
        val ref = store.part(index).manifest(passport).objectRef
        val before = cloud.inspect(ref.path(source.zone)); require(before == ref.remote)
        val out = ByteArrayOutputStream()
        cloud.readback(ref.path(source.zone)) { MultipartFormat.decodeObject(ref, it, out) }
        require(cloud.inspect(ref.path(source.zone)) == before)
        return out.toByteArray()
    }
    override fun plan(save: (PlannedPart) -> Unit): MultipartPassport {
        checkSource()
        if(job.zone == OriginalZone.GALLERY) {
            val source = cold(fullReadback=true); val expected = source.proof!!
            if(source.transport == OriginalTransport.SINGLE) {
                val passport = fromSingle(source, 0, expected.originalBytes) { input ->
                    MultipartFormat.scan(input, expected.times) { i,o,n,h -> save(PlannedPart(i,o,n,h)) }
                }
                require(passport.bytes == expected.originalBytes && passport.sha == expected.originalSha256)
                cold(); return passport
            }
            val state = AndroidMultipartStore(context, source).state()!!
            require(state.passport.partBytes == MultipartFormat.PART_BYTES)
            val hash = MessageDigest.getInstance("SHA-256"); var offset = 0L
            for(index in 0 until state.passport.parts) {
                check(!stopped()); val bytes = coldPart(source, index)
                try { hash.update(bytes); save(PlannedPart(index, offset, bytes.size.toLong(), tileHash(bytes))); offset += bytes.size } finally { bytes.fill(0) }
            }
            require(offset == expected.originalBytes && hash.digest().let { tileHex(it) } == expected.originalSha256)
            cold(); return state.passport
        }
        val passport = inventory.openOriginal(job).use { input ->
            val times = OriginalDatePolicy.capture(context, Uri.parse(job.source), input)
            MultipartFormat.scan(stoppable(input), times) { index, offset, bytes, sha -> save(PlannedPart(index, offset, bytes, sha)) }
                .also { require(OriginalDatePolicy.capture(context, Uri.parse(job.source), input) == times) }
        }
        require(job.sourceBytes == null || job.sourceBytes == passport.bytes); checkSource(); return passport
    }
    override fun preparePart(part: PlannedPart, passport: MultipartPassport): MultipartIntent {
        part.validate(passport); checkSource(); reserve()
        val id = ArchiveStore.randomId(); val output = stage(id)
        try {
            val proof = metrics.timed(job.id, "encrypt") {
                FileOutputStream(output).use { sink ->
                    val result = if(job.zone == OriginalZone.GALLERY) {
                        val source = cold(); val expected = source.proof!!; require(expected.originalSha256 == passport.sha && expected.originalBytes == passport.bytes)
                        if(source.transport == OriginalTransport.SINGLE) fromSingle(source, part.offset, part.bytes) {
                            Pos2.write(it, sink, job.name, job.mime, part.bytes, part.bytes, times = passport.times)
                        } else {
                            val bytes = coldPart(source, part.index)
                            try { bytes.inputStream().use { Pos2.write(it, sink, job.name, job.mime, part.bytes, part.bytes, times = passport.times) } }
                            finally { bytes.fill(0) }
                        }
                    } else inventory.openOriginal(job).use { input ->
                        require(OriginalDatePolicy.capture(context, Uri.parse(job.source), input) == passport.times)
                        skipExactly(input, part.offset)
                        val result = Pos2.write(LimitedInput(stoppable(input), part.bytes), sink, job.name, job.mime, part.bytes, part.bytes, times = passport.times)
                        require(OriginalDatePolicy.capture(context, Uri.parse(job.source), input) == passport.times); result
                    }
                    sink.fd.sync(); ArchiveProof.from(result)
                }
            }
            require(proof.originalBytes == part.bytes && proof.originalSha256 == part.sha && proof.times == passport.times)
            output.inputStream().use { require(ArchiveProof.from(Pos2.read(it, ColdArchive.Sink, part.bytes, part.sha, part.bytes)) == proof) }
            checkSource(); return MultipartIntent(id, proof,extension=ContainerExtension.CV2)
        } catch(e: Exception) { output.delete(); throw e }
    }
    override fun prepareManifest(bytes: ByteArray, passport: MultipartPassport, id: String?): MultipartIntent {
        require(bytes.size <= MultipartFormat.MANIFEST_MAX_BYTES); check(!stopped()); reserve()
        val key = id ?: ArchiveStore.randomId(); val output = stage(key)
        if(output.exists()) {
            val recovered = output.inputStream().use { Pos2.read(it, ColdArchive.Sink, bytes.size.toLong(), tileHash(bytes), MultipartFormat.MANIFEST_MAX_BYTES) }
            require(recovered.times == passport.times && recovered.name == "manifest.json" && recovered.mime == "application/vnd.ciel.multipart+json")
            return MultipartIntent(key, ArchiveProof.from(recovered),extension=if(id==job.id)job.containerExtension else ContainerExtension.CV2)
        }
        try {
            val proof = FileOutputStream(output).use { sink ->
                ArchiveProof.from(Pos2.write(bytes.inputStream(), sink, "manifest.json", "application/vnd.ciel.multipart+json", bytes.size.toLong(), MultipartFormat.MANIFEST_MAX_BYTES, times = passport.times)).also { sink.fd.sync() }
            }
            output.inputStream().use { require(ArchiveProof.from(Pos2.read(it, ColdArchive.Sink, proof.originalBytes, proof.originalSha256, MultipartFormat.MANIFEST_MAX_BYTES)) == proof) }
            return MultipartIntent(key, proof,extension=if(id==job.id)job.containerExtension else ContainerExtension.CV2)
        } catch(e: Exception) { output.delete(); throw e }
    }
    override fun open(id: String): InputStream {
        val own=stage(id); val lease=ViewerRuntime.source(Uri.fromFile(own).toString())
        try { return object: FilterInputStream(stoppable(own.inputStream())) {
            override fun close() { try { super.close() } finally { lease.close() } }
        } } catch(error:Exception) { lease.close(); throw error }
    }
    override fun remove(id: String) { check(ViewerRuntime.removeOwned(stage(id))) { "Multipart stage is in use" } }
    private fun <T> fromSingle(source: ArchiveJob, offset: Long, length: Long, consume: (InputStream) -> T): T {
        val expected = source.proof!!; require(offset >= 0 && length >= 0 && offset <= expected.originalBytes-length)
        val before = cloud.inspect(source.path); require(before != null && before.id == source.remoteId && before.modified == source.remoteModified && before.bytes == expected.containerBytes)
        val input = PipedInputStream(65536); val pipe = PipedOutputStream(input)
        val task = java.util.concurrent.FutureTask {
            pipe.use { output ->
                var position = 0L
                val slice = object: OutputStream() {
                    override fun write(b: Int) { write(byteArrayOf(b.toByte())) }
                    override fun write(b: ByteArray, start: Int, count: Int) {
                        check(!stopped()); val left = maxOf(position, offset); val right = minOf(position+count, offset+length)
                        if(right > left) output.write(b, start+(left-position).toInt(), (right-left).toInt())
                        position += count
                    }
                }
                cloud.readback(source.path) { require(ArchiveProof.from(Pos2.read(it, slice, expected.originalBytes, expected.originalSha256, MAX_SINGLE_BYTES)) == expected) }
                require(cloud.inspect(source.path) == before)
            }
        }
        val thread = Thread(task,"ciel-verified-slice").apply { isDaemon=true; start() }
        try { val result = consume(input); task.get(); return result }
        finally { input.close(); pipe.close(); task.cancel(true); thread.join(1000) }
    }
    private fun stoppable(input: InputStream) = object: FilterInputStream(input) {
        override fun read(): Int { check(!stopped()); return `in`.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { check(!stopped()); return `in`.read(b, off, len) }
    }
    private fun skipExactly(input: InputStream, length: Long) {
        var remaining = length; val buffer = ByteArray(65536)
        while(remaining > 0) { check(!stopped()); val skipped = input.skip(remaining)
            if(skipped > 0) remaining -= skipped else { val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt()); require(n > 0); remaining -= n } }
    }
    private class LimitedInput(input: InputStream, private var remaining: Long): FilterInputStream(input) {
        override fun read(): Int { if(remaining == 0L) return -1; val b = `in`.read(); require(b >= 0); remaining--; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int { if(len == 0) return 0; if(remaining == 0L) return -1
            val n = `in`.read(b, off, minOf(remaining, len.toLong()).toInt()); require(n > 0); remaining -= n; return n }
    }
}

private fun tileHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
