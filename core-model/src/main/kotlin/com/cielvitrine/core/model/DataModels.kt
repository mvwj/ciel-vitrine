package com.cielvitrine.core.model

import kotlinx.serialization.Serializable

/**
 * DATA-001: Asset and Resource.
 * An Asset is a gallery-level item owning one or more authoritative Resources.
 * Examples:
 * - JPEG still: 1 resource.
 * - Motion Photo container: 1 resource.
 * - Apple Live Photo: still resource + paired video resource.
 */
@Serializable
data class Asset(
    val assetId: String,
    val primaryResourceId: String,
    val resourceIds: List<String>,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Authoritative Resource owned by an Asset.
 */
@Serializable
data class Resource(
    val resourceId: String,
    val assetId: String,
    val role: ResourceRole,
    val mimeType: String,
    val createdAt: Long
)

enum class ResourceRole {
    PRIMARY_STILL,
    PAIRED_VIDEO,
    AUXILIARY_CONTAINER
}

/**
 * DATA-003: Source representation honesty.
 * A PLATFORM_REPRESENTATION is preserved exactly but must not be advertised as camera/device original.
 */
enum class SourceRepresentationKind {
    EXACT_ORIGINAL,
    USER_SUPPLIED_FILE,
    PLATFORM_REPRESENTATION
}

/**
 * DATA-002: Immutable ResourceVersion.
 * Each accepted byte sequence becomes an immutable ResourceVersion.
 */
@Serializable
data class ResourceVersion(
    val resourceVersionId: String,
    val resourceId: String,
    val assetId: String,
    val sourceRepresentationKind: SourceRepresentationKind,
    val trustedOriginalByteLength: Long,
    val trustedOriginalSha256: String,
    val createdAt: Long,
    val mediaFactsRef: String? = null
)

/**
 * DATA-004: SourceDescriptor before EOF.
 * Cheap descriptor capturing URI/file identity and observed state before reading bytes.
 */
@Serializable
data class SourceDescriptor(
    val sourceUri: String,
    val observedByteLength: Long? = null,
    val observedLastModified: Long? = null,
    val observedGenerationOrRevision: String? = null,
    val representationKind: SourceRepresentationKind
)

/**
 * DATA-004: OriginalPassport after successful EOF.
 * Created only after EOF has been reached, length and SHA-256 confirmed.
 * No destructive action may rely on a candidate whose final passport was not durably committed.
 */
@Serializable
data class OriginalPassport(
    val resourceVersionId: String,
    val exactByteLength: Long,
    val trustedOriginalSha256: String,
    val sourceDescriptorAtStart: SourceDescriptor,
    val sourceDescriptorAtEnd: SourceDescriptor,
    val mediaDiagnostics: Map<String, String> = emptyMap(),
    val committedAt: Long = System.currentTimeMillis()
)
