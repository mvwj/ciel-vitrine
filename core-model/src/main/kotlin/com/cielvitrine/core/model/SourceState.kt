package com.cielvitrine.core.model

import kotlinx.serialization.Serializable

/**
 * DATA-005: Source presence states.
 * Loss of visibility or access is NOT deletion.
 */
enum class SourcePresence {
    PRESENT,
    UNKNOWN,
    ACCESS_REVOKED,
    PARTIAL_SCOPE,
    VOLUME_OFFLINE,
    PROVIDER_UNAVAILABLE,
    ABSENT_CONFIRMED
}

/**
 * DATA-006: Source binding and MediaStore epoch.
 * Includes scope and provider DB epoch to prevent ID reuse from impersonating old sources.
 */
@Serializable
data class SourceBinding(
    val resourceId: String,
    val authority: String,
    val volumeOrCollection: String,
    val providerDatabaseEpochOrVersion: String?,
    val providerItemId: String,
    val accessScopeIdentity: String,
    val observedGenerationOrRevision: String?,
    val representationKind: SourceRepresentationKind
)

/**
 * DATA-007: Compound resource-set revision.
 * Multi-resource assets have an explicit resource-set revision/commit group.
 * Destructive predicates apply to the complete current resource-set revision.
 */
@Serializable
data class ResourceSetRevision(
    val revisionId: String,
    val assetId: String,
    val resourceVersionIds: List<String>,
    val revisionNumber: Long,
    val isCommitted: Boolean,
    val committedAt: Long? = null
)
