package com.cielvitrine.core.model

import kotlinx.serialization.Serializable

/**
 * DEL-002, DEL-009: Durable reservation for destructive operations.
 * Covers affected ResourceVersions and exact copy locations.
 */
@Serializable
data class DestructiveReservation(
    val reservationId: String,
    val operationType: DestructiveOperationType,
    val targetAssetId: String,
    val affectedResourceVersionIds: List<String>,
    val targetCopyIds: List<String>,
    val acquiredAt: Long,
    val expiresAt: Long,
    val cancellationGeneration: Long = 0,
    val status: ReservationStatus
)

enum class DestructiveOperationType {
    FREE_UP_DEVICE,
    REMOVE_GALLERY_STORE_COPY,
    REMOVE_COLD_ARCHIVE_COPY,
    DELETE_FROM_GALLERY_AND_DEVICE,
    ERASE_ALL_KNOWN_COPIES,
    TRASH_EXPIRY_PURGE
}

enum class ReservationStatus {
    ACTIVE,
    EXECUTING,
    COMPLETED,
    CANCELLED,
    EXPIRED
}

/**
 * TRASH-001..006: 60-day Gallery Trash Record.
 * Freezes authorized future purge scope and last-copy authorization.
 */
@Serializable
data class TrashRecord(
    val trashId: String,
    val assetId: String,
    val trashedAt: Long,
    val retainUntil: Long, // trashedAt + 60 days
    val authorizedPurgeScope: List<CopyRole>,
    val explicitLastCopyPurgeAuthorized: Boolean,
    val status: TrashStatus
)

enum class TrashStatus {
    IN_TRASH,
    RESTORED,
    EXPIRED_AWAITING_CONFIRMATION,
    PURGE_DISPATCHED,
    PURGED
}

/**
 * WR-001..003: Writer coordination and fork management.
 */
@Serializable
data class WriterHead(
    val writerIdentity: String,
    val epoch: Long,
    val sequenceNumber: Long,
    val headCommitHash: String,
    val signedAt: Long
)

enum class WriterStatus {
    ACTIVE_HEAD,
    TAKEOVER_IN_PROGRESS,
    WRITER_CONFLICT,
    OFFLINE
}

/**
 * UX-004, UX-005: Backup exclusion and deletion tombstones.
 */
@Serializable
data class BackupExclusion(
    val exclusionId: String,
    val targetRole: CopyRole, // GALLERY_STORE or COLD_ARCHIVE
    val resourceId: String?,
    val sourceUri: String?,
    val contentSha256: String?,
    val createdAt: Long
)

/**
 * UX-001, UX-006: Independent user-visible state projections.
 */
@Serializable
data class AssetItemProjection(
    val assetId: String,
    val visibility: AssetVisibility,
    val hasLocalSource: Boolean,
    val galleryStoreCopyState: CopyState?,
    val coldArchiveCopyState: CopyState?,
    val isSafeToFreeDevice: Boolean,
    val remoteViewReady: Boolean,
    val localPreviewReady: Boolean,
    val offlineOriginalReady: Boolean
)

enum class AssetVisibility {
    VISIBLE,
    ARCHIVED,
    TRASHED
}
