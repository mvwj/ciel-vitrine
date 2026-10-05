package com.cielvitrine.core.model

/** Scheduling only. VERIFIED is emitted by readback, never by HTTP upload success. */
enum class BackupStep { UPLOAD_COLD_ARCHIVE, VERIFY_COLD_ARCHIVE, UPLOAD_GALLERY, VERIFY_GALLERY, PUBLISH_GALLERY, READY, WAIT_FOR_SOURCE, WAIT_FOR_COMPOUND_SET }

data class ResourceBackupProgress(
    val resourceVersionId: String,
    val sourceAccessible: Boolean,
    val coldArchive: CopyState? = null,
    val gallery: CopyState? = null,
    val privateCatalogCommitted: Boolean = false,
)

object BackupPlanner {
    fun next(set: ResourceSetRevision, resources: List<ResourceBackupProgress>, archiveOnly: Boolean = false): BackupStep {
        if (!set.isCommitted || set.resourceVersionIds.isEmpty() || set.resourceVersionIds.distinct().size != set.resourceVersionIds.size || resources.map { it.resourceVersionId }.toSet() != set.resourceVersionIds.toSet() || resources.map { it.resourceVersionId }.distinct().size != resources.size) return BackupStep.WAIT_FOR_COMPOUND_SET
        val missingArchive = resources.filter { it.coldArchive !in listOf(CopyState.COMMITTED, CopyState.VERIFIED) }
        if (missingArchive.isNotEmpty()) return if (missingArchive.all { it.sourceAccessible }) BackupStep.UPLOAD_COLD_ARCHIVE else BackupStep.WAIT_FOR_SOURCE
        if (resources.any { it.coldArchive != CopyState.VERIFIED }) return BackupStep.VERIFY_COLD_ARCHIVE
        if (archiveOnly) return BackupStep.READY
        if (resources.any { it.gallery !in listOf(CopyState.COMMITTED, CopyState.VERIFIED) }) return BackupStep.UPLOAD_GALLERY
        if (resources.any { it.gallery != CopyState.VERIFIED }) return BackupStep.VERIFY_GALLERY
        if (resources.any { !it.privateCatalogCommitted }) return BackupStep.PUBLISH_GALLERY
        return BackupStep.READY
    }
}
