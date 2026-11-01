package com.cielvitrine.archive

import com.cielvitrine.pos.Pos2
import com.cielvitrine.pos.Pos2Result
import com.cielvitrine.pos.OriginalTimes
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

@Serializable enum class ArchivePhase { QUEUED, PREPARED, UPLOAD_UNCERTAIN, VERIFIED, SOURCE_ERROR, UNSUPPORTED_SIZE }
@Serializable enum class OriginalZone { COLD, GALLERY, VIEW }
@Serializable enum class ContainerExtension(val suffix: String) { CV2(".cv2"), BIN(".bin") }
@Serializable enum class OriginalTransport { SINGLE, MULTIPART }
@Serializable data class MultipartCommit(val root: ArchiveProof, val parts: Long)
@Serializable data class SourceStamp(val volume: String, val epoch: String, val mediaId: Long,
    val generation: Long?, val modifiedSeconds: Long, val bytes: Long?, val width: Int, val height: Int,
    val durationMillis: Long, val capturedAt: Long?)
@Serializable data class ArchiveProof(val bundleId: String, val originalBytes: Long, val originalSha256: String,
    val containerBytes: Long, val containerSha256: String, val times: OriginalTimes? = null) {
    companion object { fun from(p: Pos2Result) = ArchiveProof(p.bundleId, p.originalBytes, p.originalSha256, p.containerBytes, p.containerSha256, p.times) }
}
@Serializable data class ArchiveJob(val id: String, val uid: String?, val rootId: String?, val source: String,
    val name: String, val mime: String, val sourceBytes: Long?, val phase: ArchivePhase = ArchivePhase.QUEUED,
    val proof: ArchiveProof? = null, val remoteId: String? = null, val remoteModified: String? = null,
    val verifiedAt: Long? = null, val notice: String? = null,
    val revision: Long = 0, val batchId: String? = null, val sourceStamp: SourceStamp? = null,
    val zone: OriginalZone = OriginalZone.COLD, val groupId: String? = null, val coldId: String? = null,
    val deferred: Boolean = false, val createdAt: Long = 0,
    // Missing in older journals: keep their original path, including uncertain uploads.
    val containerExtension: ContainerExtension = ContainerExtension.CV2,
    val transport: OriginalTransport = OriginalTransport.SINGLE, val multipart: MultipartCommit? = null) {
    val path get() = "app:/ciel-vitrine-v2/${if (zone == OriginalZone.COLD) "cold" else "gallery"}/$id${containerExtension.suffix}"
    fun validate() {
        require(id.matches(Regex("[0-9a-f]{32}")) && ((uid == null && rootId == null) ||
            (uid != null && uid.matches(Regex("[0-9]{1,32}")) && !rootId.isNullOrBlank())))
        require(source.length <= 16384 && name.length <= 1024 && mime.length <= 256)
        require(sourceBytes == null || sourceBytes >= 0)
        sourceStamp?.let { require(it.volume.length in 1..256 && it.epoch.length in 1..1024 && it.mediaId >= 0 &&
            (it.generation == null || it.generation >= 0) && (it.bytes == null || it.bytes >= 0) && it.width >= 0 && it.height >= 0 && it.durationMillis >= 0) }
        require(revision >= 0 && createdAt >= 0)
        require(listOfNotNull(batchId, groupId, coldId).all { it.matches(Regex("[0-9a-f]{32}")) })
        if (zone == OriginalZone.GALLERY) require(groupId != null && coldId != null)
        if (phase in setOf(ArchivePhase.PREPARED, ArchivePhase.UPLOAD_UNCERTAIN, ArchivePhase.VERIFIED)) require(proof != null && uid != null && rootId != null)
        proof?.let { require(it.originalBytes >= 0 && it.containerBytes > 0)
            if(transport == OriginalTransport.SINGLE) require(it.originalBytes <= MAX_SINGLE_BYTES && it.containerBytes <= MAX_SINGLE_BYTES + 262144)
            it.times?.validate()
            require(it.bundleId.matches(Regex("[0-9a-f]{32}")) && listOf(it.originalSha256, it.containerSha256).all { h -> h.matches(Regex("[0-9a-f]{64}")) }) }
        if (phase == ArchivePhase.VERIFIED) require(!remoteId.isNullOrBlank() && !remoteModified.isNullOrBlank() && verifiedAt != null)
        multipart?.let { require(transport == OriginalTransport.MULTIPART && it.parts > 0 && proof != null)
            require(it.root.originalBytes in 1..MultipartFormat.MANIFEST_MAX_BYTES && it.root.containerBytes in 1..MultipartFormat.MANIFEST_MAX_BYTES + 262144)
            require(it.root.bundleId == proof.bundleId && it.root.containerSha256 == proof.containerSha256 && it.root.containerBytes == proof.containerBytes)
        }
        if(transport == OriginalTransport.MULTIPART && phase == ArchivePhase.VERIFIED) require(multipart != null)
    }
}

@Serializable data class ResourceGroup(val id: String, val coldIds: List<String>, val declaredComplete: Boolean,
    val label: String, val galleryIds: List<String> = emptyList()) {
    fun validate() {
        require(id.matches(Regex("[0-9a-f]{32}")) && coldIds.isNotEmpty() && coldIds.size <= 32 && coldIds.distinct().size == coldIds.size)
        require((coldIds + galleryIds).all { it.matches(Regex("[0-9a-f]{32}")) } && label.length <= 1024)
        require(galleryIds.isEmpty() || galleryIds.size == coldIds.size)
        require(galleryIds.distinct().size == galleryIds.size && coldIds.intersect(galleryIds.toSet()).isEmpty())
    }
}
interface ArchiveJournal {
    fun load(id: String): ArchiveJob
    fun save(job: ArchiveJob)
    fun group(id: String): ResourceGroup? = null
}
interface ArchiveStaging {
    /** Must fsync/commit the owned local container and fully read it back before returning. */
    fun prepare(job: ArchiveJob): ArchiveProof
    fun open(id: String): InputStream
    fun removeOwnedContainer(id: String)
}
@Serializable data class ArchiveRemote(val id: String, val modified: String, val bytes: Long, val sha256: String?)
interface ArchiveCloud {
    fun requireAccount(uid: String, rootId: String)
    fun ensureColdFolders()
    fun ensureGalleryFolders() { error("Gallery capability unavailable") }
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
        require(job.transport == OriginalTransport.SINGLE)
        check(!stopped())
        if (job.phase == ArchivePhase.VERIFIED) { staging.removeOwnedContainer(id); return job }
        if (job.phase in setOf(ArchivePhase.SOURCE_ERROR, ArchivePhase.UNSUPPORTED_SIZE) || job.deferred) return job
        cloud.requireAccount(job.uid ?: error("Account not bound"), job.rootId ?: error("Root not bound"))
        if (job.zone == OriginalZone.GALLERY) requireGroup(job, fullReadback = job.phase == ArchivePhase.QUEUED)
        when (job.phase) {
            ArchivePhase.QUEUED -> {
                val proof = staging.prepare(job)
                if (job.zone == OriginalZone.GALLERY) {
                    val cold = journal.load(job.coldId!!).proof!!
                    require(proof.originalBytes == cold.originalBytes && proof.originalSha256 == cold.originalSha256 && proof.times == cold.times)
                }
                check(!stopped())
                job = job.copy(phase = ArchivePhase.PREPARED, proof = proof, notice = null)
                journal.save(job)
            }
            ArchivePhase.PREPARED -> {
                if (job.zone == OriginalZone.COLD) cloud.ensureColdFolders() else cloud.ensureGalleryFolders()
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
                if (job.zone == OriginalZone.GALLERY) {
                    requireGroup(job, fullReadback = false)
                    val group = journal.group(job.groupId!!)!!
                    require(group.coldIds.map(journal::load).none { it.remoteId == after.id })
                    require(group.galleryIds.filter { it != job.id }.map(journal::load).none { it.remoteId == after.id })
                    val cold = journal.load(job.coldId!!).proof!!
                    require(proof.bundleId != cold.bundleId && proof.containerSha256 != cold.containerSha256 && proof.times == cold.times)
                }
                cloud.requireAccount(job.uid!!, job.rootId!!)
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
    private fun requireGroup(job: ArchiveJob, fullReadback: Boolean) {
        val group = journal.group(job.groupId!!) ?: error("Resource group missing")
        group.validate()
        require(group.declaredComplete && job.coldId in group.coldIds && job.id in group.galleryIds)
        require(group.coldIds[group.galleryIds.indexOf(job.id)] == job.coldId)
        val members = group.coldIds.map(journal::load)
        require(members.all { it.zone == OriginalZone.COLD && it.phase == ArchivePhase.VERIFIED && it.uid == job.uid && it.rootId == job.rootId })
        val source = members.single { it.id == job.coldId }.proof!!
        job.proof?.let { require(it.originalBytes == source.originalBytes && it.originalSha256 == source.originalSha256) }
        for (member in members) {
            val expected = member.proof!!
            val before = cloud.inspect(member.path) ?: error("Archive member unavailable")
            require(before.id == member.remoteId && before.modified == member.remoteModified && before.bytes == expected.containerBytes &&
                (before.sha256 == null || before.sha256.equals(expected.containerSha256, true)))
            if (fullReadback) {
                verifyArchivedOriginal(member,cloud,stopped)
                require(cloud.inspect(member.path) == before)
            }
        }
    }
    companion object {
        const val MAX_BYTES = MAX_SINGLE_BYTES
        val Sink: OutputStream = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
    }
}
/** Compatibility budget for existing single-object journals, never a new-source admission limit. */
const val MAX_SINGLE_BYTES = 128L * 1024 * 1024
