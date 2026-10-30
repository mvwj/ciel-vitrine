package com.cielvitrine.archive

import com.cielvitrine.pos.OriginalTimes
import com.cielvitrine.pos.Pos2
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.security.MessageDigest

@Serializable data class MultipartPassport(val bytes: Long, val sha: String, val parts: Long,
    val partBytes: Int = MultipartFormat.PART_BYTES, val times: OriginalTimes? = null) {
    fun validate() {
        require(bytes >= 0 && sha.matches(MultipartFormat.SHA) && partBytes in 65536..MultipartFormat.PART_BYTES)
        require(parts == maxOf(1L, bytes / partBytes + if(bytes % partBytes == 0L) 0 else 1))
        times?.validate()
    }
    fun length(index: Long): Long { validate(); require(index in 0 until parts)
        return minOf(partBytes.toLong(), bytes - Math.multiplyExact(index, partBytes.toLong())) }
}
@Serializable data class MultipartObject(val id: String, val proof: ArchiveProof, val remote: ArchiveRemote) {
    fun validate() {
        require(id.matches(MultipartFormat.ID) && proof.bundleId.matches(MultipartFormat.ID))
        require(proof.originalBytes >= 0 && proof.containerBytes > 0 && proof.originalSha256.matches(MultipartFormat.SHA) && proof.containerSha256.matches(MultipartFormat.SHA))
        require(remote.id.isNotBlank() && remote.id.length <= 4096 && remote.modified.isNotBlank() && remote.modified.length <= 256)
        require(remote.bytes == proof.containerBytes && (remote.sha256 == null || remote.sha256.equals(proof.containerSha256, true)))
    }
    fun path(zone: OriginalZone) = "app:/ciel-vitrine-v2/${if(zone == OriginalZone.COLD) "cold" else "gallery"}/$id.bin"
}
@Serializable data class MultipartPart(val index: Long, val offset: Long, val bytes: Long, val sha: String,
    val objectRef: MultipartObject) {
    fun validate(passport: MultipartPassport) {
        require(index in 0 until passport.parts && offset == Math.multiplyExact(index, passport.partBytes.toLong()))
        require(bytes == passport.length(index) && sha.matches(MultipartFormat.SHA))
        objectRef.validate()
        require(objectRef.proof.originalBytes == bytes && objectRef.proof.originalSha256 == sha && objectRef.proof.times == passport.times)
        require(objectRef.proof.containerBytes <= passport.partBytes.toLong() + 262144)
    }
}
@Serializable data class MultipartPage(val format: String = "CVMP1-page", val job: String, val sha: String,
    val index: Long, val previous: MultipartObject?, val parts: List<MultipartPart>) {
    fun validate(root: MultipartRoot) {
        require(format == "CVMP1-page" && job == root.job && sha == root.passport.sha && index in 0 until root.pages)
        require((index == 0L) == (previous == null)); previous?.validate()
        val first = Math.multiplyExact(index, MultipartFormat.PARTS_PER_PAGE.toLong())
        require(parts.size.toLong() == minOf(MultipartFormat.PARTS_PER_PAGE.toLong(), root.passport.parts - first))
        parts.forEachIndexed { i, part -> require(part.index == first + i); part.validate(root.passport) }
        require(parts.map { it.objectRef.id }.distinct().size == parts.size)
    }
}
@Serializable data class MultipartRoot(val format: String = "CVMP1", val job: String, val name: String,
    val mime: String, val zone: OriginalZone, val passport: MultipartPassport, val lastPage: MultipartObject) {
    val pages: Long get() = passport.parts / MultipartFormat.PARTS_PER_PAGE + if(passport.parts % MultipartFormat.PARTS_PER_PAGE == 0L) 0 else 1
    fun validate() { require(format == "CVMP1" && job.matches(MultipartFormat.ID) && name.length <= 1024 && mime.length <= 256)
        passport.validate(); lastPage.validate(); require(lastPage.proof.originalBytes <= MultipartFormat.MANIFEST_MAX_BYTES) }
}

/** CVMP1 indexes independent standard CVPOS3 objects; no media is re-encoded. */
object MultipartFormat {
    const val PART_BYTES = 8 * 1024 * 1024
    const val PARTS_PER_PAGE = 64
    const val MANIFEST_MAX_BYTES = 262144L
    val ID = Regex("[0-9a-f]{32}")
    val SHA = Regex("[0-9a-f]{64}")
    private val json = Json { encodeDefaults = true }
    fun rootBytes(root: MultipartRoot): ByteArray = json.encodeToString(root.also { it.validate() }).toByteArray().also { require(it.size <= MANIFEST_MAX_BYTES) }
    fun pageBytes(page: MultipartPage): ByteArray = json.encodeToString(page).toByteArray().also { require(it.size <= MANIFEST_MAX_BYTES) }
    fun root(bytes: ByteArray): MultipartRoot {
        require(bytes.size <= MANIFEST_MAX_BYTES)
        return json.decodeFromString<MultipartRoot>(bytes.toString(Charsets.UTF_8)).also {
            it.validate(); require(rootBytes(it).contentEquals(bytes)) { "Non-canonical or duplicate root fields" }
        }
    }
    fun page(bytes: ByteArray, root: MultipartRoot): MultipartPage {
        require(bytes.size <= MANIFEST_MAX_BYTES)
        return json.decodeFromString<MultipartPage>(bytes.toString(Charsets.UTF_8)).also {
            it.validate(root); require(pageBytes(it).contentEquals(bytes)) { "Non-canonical or duplicate page fields" }
        }
    }
    fun scan(input: InputStream, times: OriginalTimes?, partBytes: Int = PART_BYTES,
        onPart: (index: Long, offset: Long, bytes: Long, sha: String) -> Unit): MultipartPassport {
        require(partBytes in 65536..PART_BYTES)
        val whole = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        var total = 0L; var index = 0L
        while(true) {
            val part = MessageDigest.getInstance("SHA-256"); var bytes = 0L
            while(bytes < partBytes) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), partBytes - bytes).toInt())
                if(n < 0) break
                require(n > 0); part.update(buffer, 0, n); whole.update(buffer, 0, n); bytes += n
            }
            if(bytes == 0L && index != 0L) break
            onPart(index, total, bytes, part.digest().hex()); index = Math.addExact(index, 1); total = Math.addExact(total, bytes)
            if(bytes < partBytes) break
        }
        return MultipartPassport(total, whole.digest().hex(), index, partBytes, times).also { it.validate() }
    }
    fun decodeObject(ref: MultipartObject, input: InputStream, output: OutputStream) {
        ref.validate()
        val result = Pos2.read(input, output, ref.proof.originalBytes, ref.proof.originalSha256, maxOf(PART_BYTES.toLong(), MANIFEST_MAX_BYTES))
        require(ArchiveProof.from(result) == ref.proof)
    }
    fun readPage(ref: MultipartObject, root: MultipartRoot, open: (MultipartObject) -> InputStream): MultipartPage {
        require(ref.proof.originalBytes <= MANIFEST_MAX_BYTES)
        val bytes = ByteArrayOutputStream()
        open(ref).use { decodeObject(ref, it, bytes) }
        return page(bytes.toByteArray(), root)
    }
    /** Write only to an owned temporary destination. Caller publishes it after this succeeds. */
    fun restore(root: MultipartRoot, open: (MultipartObject) -> InputStream, output: RandomAccessFile) {
        root.validate(); require(output.length() == 0L)
        var ref: MultipartObject? = root.lastPage; var pageIndex = root.pages - 1; var position = root.passport.bytes
        while(ref != null) {
            val page = readPage(ref, root, open); require(page.index == pageIndex)
            for(part in page.parts.asReversed()) {
                require(part.offset == position - part.bytes); output.seek(part.offset)
                val sink = object: OutputStream() {
                    override fun write(b: Int) { output.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { output.write(b, off, len) }
                }
                open(part.objectRef).use { decodeObject(part.objectRef, it, sink) }; position = part.offset
            }
            ref = page.previous; pageIndex--
        }
        require(pageIndex == -1L && position == 0L && output.length() == root.passport.bytes)
        output.seek(0); val hash = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while(true) { val n = output.read(bytes); if(n < 0) break; require(n > 0); hash.update(bytes, 0, n) }
        require(hash.digest().hex() == root.passport.sha) { "Whole restored original mismatch" }
        output.fd.sync()
    }
    fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
