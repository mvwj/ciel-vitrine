package com.cielvitrine.core.model

import kotlinx.serialization.Serializable

/**
 * COPY-001: ExactCopyLocation.
 * Tracks physical replicas vs failure domains.
 */
@Serializable
data class ExactCopyLocation(
    val copyId: String,
    val resourceVersionId: String,
    val role: CopyRole,
    val providerAccountId: String?,
    val physicalObjectSetId: String,
    val failureDomainIds: List<String>,
    val observedRevision: String?,
    val state: CopyState,
    val lastObservedAt: Long
)

enum class CopyRole {
    LOCAL_SOURCE,
    GALLERY_STORE,
    COLD_ARCHIVE
}

enum class CopyState {
    STAGING,
    COMMITTED,
    VERIFIED,
    MARKED_FOR_PURGE,
    ABSENT
}

/**
 * VER-001: VerificationEvidence.
 * Observation of remote validity bound to revision, length, and trusted original passport digest.
 */
@Serializable
data class VerificationEvidence(
    val evidenceId: String,
    val resourceVersionId: String,
    val originalByteLength: Long,
    val trustedOriginalSha256: String,
    val copyId: String,
    val providerAccountId: String,
    val vaultRootBinding: String,
    val containerFormatVersion: Int,
    val containerBytes: Long,
    val containerSha256: String,
    val orderedParts: List<PartEvidence>,
    val method: VerificationMethod,
    val verifiedAt: Long,
    val observedDownloadability: Boolean,
    val invalidationGeneration: Long = 0
)

@Serializable
data class PartEvidence(
    val partIdentity: String,
    val startOffset: Long,
    val endOffset: Long,
    val size: Long,
    val digest: String,
    val observedRevision: String?
)

enum class VerificationMethod {
    SERVER_DIGEST,
    FULL_CONTAINER_READBACK,
    FULL_RECOVERY_READBACK,
    VERIFIED_SERVER_COPY
}

/**
 * UP-002: Deletion safety policies.
 */
enum class DeletionSafetyPolicy {
    ARCHIVE_GATED_STRICT,
    GALLERY_STORE_ONLY,
    DEVELOPMENT_UNCHECKED
}
