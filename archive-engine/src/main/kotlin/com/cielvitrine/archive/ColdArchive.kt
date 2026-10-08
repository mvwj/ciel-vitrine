package com.cielvitrine.archive

import com.cielvitrine.pos.Pos2
import com.cielvitrine.pos.Pos2Result
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

@Serializable enum class ArchivePhase { QUEUED, PREPARED, UPLOAD_UNCERTAIN, VERIFIED, SOURCE_ERROR }
@Serializable data class ArchiveProof(val bundleId: String, val originalBytes: Long, val originalSha256: String,
    val containerBytes: Long, val containerSha256: String) {
    companion object { fun from(p: Pos2Result) = ArchiveProof(p.bundleId, p.originalBytes, p.originalSha256, p.containerBytes, p.containerSha256) }
}
@Serializable data class ArchiveJob(val id: String, val uid: String, val rootId: String, val source: String,
    val name: String, val mime: String, val sourceBytes: Long?, val phase: ArchivePhase = ArchivePhase.QUEUED,
    val proof: ArchiveProof? = null, val remoteId: String? = null, val remoteModified: String? = null,
    val verifiedAt: Long? = null, val notice: String? = null) {
    val path get() = "app:/ciel-vitrine-v2/cold/$id.cv2"
    fun validate() {
        require(id.matches(Regex("[0-9a-f]{32}")) && uid.matches(Regex("[0-9]{1,32}")) && rootId.isNotBlank())
        require(source.length <= 16384 && name.length <= 1024 && mime.length <= 256)
        require(sourceBytes == null || sourceBytes in 0..ColdArchive.MAX_BYTES)
        if (phase in setOf(ArchivePhase.PREPARED, ArchivePhase.UPLOAD_UNCERTAIN, ArchivePhase.VERIFIED)) require(proof != null)
        proof?.let { require(it.originalBytes in 0..ColdArchive.MAX_BYTES && it.containerBytes in 1..(ColdArchive.MAX_BYTES + 262144))
            require(it.bundleId.matches(Regex("[0-9a-f]{32}")) && listOf(it.originalSha256, it.containerSha256).all { h -> h.matches(Regex("[0-9a-f]{64}")) }) }
        if (phase == ArchivePhase.VERIFIED) require(!remoteId.isNullOrBlank() && !remoteModified.isNullOrBlank() && verifiedAt != null)
    }
}

interface ArchiveJournal { fun load(id: String): ArchiveJob; fun save(job: ArchiveJob) }
interface ArchiveStaging {
    /** Must fsync/commit the owned local container and fully read it back before returning. */
    fun prepare(job: ArchiveJob): ArchiveProof
    fun open(id: String): InputStream
    fun removeOwnedContainer(id: String)
}
data class ArchiveRemote(val id: String, val modified: String, val bytes: Long, val sha256: String?)
interface ArchiveCloud {
    fun requireAccount(uid: String, rootId: String)
    fun ensureColdFolders()
    /** Null means unavailable at expected path, not proof that a known object was deleted. */
    fun inspect(path: String): ArchiveRemote?
    fun uploadNew(path: String, bytes: Long, open: () -> InputStream)
    fun <T> readback(path: String, read: (InputStream) -> T): T
}

/** Additive only. A VERIFIED archive here is not authority for freeing space or deleting any source. */
class ColdArchive(private val journal: ArchiveJournal, private val staging: ArchiveStaging, private val cloud: ArchiveCloud,
    private val stopped: () -> Boolean = { false }, private val now: () -> Long = System::currentTimeMillis) {
    /** Caller holds an interprocess job lock. One phase per call allows safe process restart. */
    fun step(id: String): ArchiveJob {
        var job = journal.load(id).also { it.validate() }
        check(!stopped())
        if (job.phase == ArchivePhase.VERIFIED) { staging.removeOwnedContainer(id); return job }
        if (job.phase == ArchivePhase.SOURCE_ERROR) return job
        cloud.requireAccount(job.uid, job.rootId)
        when (job.phase) {
            ArchivePhase.QUEUED -> {
                val proof = staging.prepare(job)
                check(!stopped())
                job = job.copy(phase = ArchivePhase.PREPARED, proof = proof, notice = null)
                journal.save(job)
            }
            ArchivePhase.PREPARED -> {
                cloud.ensureColdFolders()
                // Check the staging copy again before exposing it to the network.
                val proof = job.proof!!
                val recovered = staging.open(id).use { Pos2.read(it, Sink, proof.originalBytes, proof.originalSha256, MAX_BYTES) }
                check(ArchiveProof.from(recovered) == proof)
                check(!stopped())
                // Durable intent BEFORE a PUT. After any uncertain outcome only readback is allowed.
                job = job.copy(phase = ArchivePhase.UPLOAD_UNCERTAIN, notice = "Проверяем результат загрузки")
                journal.save(job)
                if (cloud.inspect(job.path) == null) {
                    check(!stopped())
                    cloud.uploadNew(job.path, proof.containerBytes) { staging.open(id) }
                }
            }
            ArchivePhase.UPLOAD_UNCERTAIN -> {
                val proof = job.proof!!
                val before = cloud.inspect(job.path) ?: error("Object not observed; do not repeat PUT")
                require(before.bytes == proof.containerBytes && (before.sha256 == null || before.sha256.equals(proof.containerSha256, true)))
                val recovered = cloud.readback(job.path) { Pos2.read(it, Sink, proof.originalBytes, proof.originalSha256, MAX_BYTES) }
                require(ArchiveProof.from(recovered) == proof)
                val after = cloud.inspect(job.path) ?: error("Object disappeared during verification")
                require(before == after)
                cloud.requireAccount(job.uid, job.rootId)
                check(!stopped())
                job = job.copy(phase = ArchivePhase.VERIFIED, remoteId = after.id, remoteModified = after.modified,
                    verifiedAt = now(), notice = null)
                journal.save(job)
                staging.removeOwnedContainer(id)
            }
            else -> Unit
        }
        return job
    }
    companion object {
        // Temporary single-object prototype limit. No multipart or Live Photo grouping yet.
        const val MAX_BYTES = 128L * 1024 * 1024
        val Sink: OutputStream = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
    }
}
