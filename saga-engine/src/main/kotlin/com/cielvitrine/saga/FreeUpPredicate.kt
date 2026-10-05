package com.cielvitrine.saga

import com.cielvitrine.core.model.*
import com.cielvitrine.storage.control.ControlDatabase

data class FreeUpEvaluationContext(
    val explicitUserScopeIncludesLocalRemoval: Boolean,
    val acceptedWriterHeadFresh: Boolean,
    val noKnownFork: Boolean,
    val reservationHeld: Boolean,
    val recoverableGalleryCatalogCommitExists: Boolean,
    val policy: DeletionSafetyPolicy,
    val currentResourceSet: ResourceSetRevision? = null,
    val expectedProviderAccountId: String = "",
    val expectedVaultRootBinding: String = "",
    val now: Long = System.currentTimeMillis(),
    val maxEvidenceAgeMillis: Long = 60_000,
)

data class FreeUpEvaluationResult(val isPermitted: Boolean, val rejectionReasons: List<String>)

/** Conservative prototype preflight. It is not wired to Android deletion. */
object FreeUpPredicate {
    private val sha256 = Regex("[0-9a-fA-F]{64}")

    fun evaluate(asset: Asset, resourceVersions: List<ResourceVersion>, controlDb: ControlDatabase, ctx: FreeUpEvaluationContext): FreeUpEvaluationResult {
        val reasons = mutableListOf<String>()
        if (!ctx.explicitUserScopeIncludesLocalRemoval) reasons += "User scope does not explicitly include local removal"
        if (!ctx.acceptedWriterHeadFresh) reasons += "Accepted writer head is stale"
        if (!ctx.noKnownFork) reasons += "Writer fork detected (WRITER_CONFLICT)"
        if (!ctx.reservationHeld) reasons += "Durable reservation is not held"
        if (!ctx.recoverableGalleryCatalogCommitExists) reasons += "Recoverable gallery catalog commit does not exist"
        if (ctx.policy == DeletionSafetyPolicy.DEVELOPMENT_UNCHECKED) reasons += "Unchecked policy cannot authorize deletion"
        if (ctx.expectedProviderAccountId.isBlank() || ctx.expectedVaultRootBinding.isBlank()) reasons += "Vault/account binding is missing"
        if (ctx.maxEvidenceAgeMillis <= 0) reasons += "Invalid evidence freshness policy"
        val set = ctx.currentResourceSet
        val ids = resourceVersions.map { it.resourceVersionId }
        if (set == null || !set.isCommitted || set.assetId != asset.assetId || set.resourceVersionIds.toSet() != ids.toSet() || ids.isEmpty() || ids.distinct().size != ids.size || set.resourceVersionIds.distinct().size != set.resourceVersionIds.size) reasons += "Complete committed resource-set revision is required"
        if (resourceVersions.map { it.resourceId }.toSet() != asset.resourceIds.toSet() || resourceVersions.any { it.assetId != asset.assetId } || asset.primaryResourceId !in asset.resourceIds) reasons += "Resource versions do not cover this asset"
        for (rv in resourceVersions) {
            val passport = controlDb.getPassport(rv.resourceVersionId)
            if (passport == null || passport.resourceVersionId != rv.resourceVersionId || passport.exactByteLength != rv.trustedOriginalByteLength || !passport.trustedOriginalSha256.equals(rv.trustedOriginalSha256, true) || !sha256.matches(rv.trustedOriginalSha256)) {
                reasons += "ResourceVersion ${rv.resourceVersionId} has no matching durably committed passport"
                continue
            }
            if (rv.sourceRepresentationKind == SourceRepresentationKind.PLATFORM_REPRESENTATION || passport.sourceDescriptorAtStart != passport.sourceDescriptorAtEnd || passport.sourceDescriptorAtStart.sourceUri.isBlank()) reasons += "Source original/version is not stable for ${rv.resourceVersionId}"
            val copies = controlDb.getCopiesForVersion(rv.resourceVersionId)
            val roles = if (ctx.policy == DeletionSafetyPolicy.ARCHIVE_GATED_STRICT) listOf(CopyRole.GALLERY_STORE, CopyRole.COLD_ARCHIVE) else listOf(CopyRole.GALLERY_STORE)
            val accepted = roles.mapNotNull { role ->
                val copy = copies.firstOrNull { copy ->
                    val e = controlDb.getLatestEvidence(rv.resourceVersionId, copy.copyId)
                    copy.role == role && copy.state == CopyState.VERIFIED && copy.providerAccountId == ctx.expectedProviderAccountId && copy.observedRevision != null && e != null && evidenceMatches(rv, passport, copy, e, ctx)
                }
                if (copy == null) reasons += "ResourceVersion ${rv.resourceVersionId} lacks verified ${if (role == CopyRole.COLD_ARCHIVE) "Cold Archive" else "Gallery Store"} copy with bound readback evidence"
                copy
            }
            if (accepted.map { it.physicalObjectSetId }.distinct().size != accepted.size) reasons += "Gallery and Archive share the same physical object"
        }
        return FreeUpEvaluationResult(reasons.isEmpty(), reasons)
    }

    private fun evidenceMatches(rv: ResourceVersion, passport: OriginalPassport, copy: ExactCopyLocation, e: VerificationEvidence, ctx: FreeUpEvaluationContext): Boolean {
        if (!e.observedDownloadability || e.method != VerificationMethod.FULL_RECOVERY_READBACK || e.containerFormatVersion != 1 ||
            e.resourceVersionId != rv.resourceVersionId || e.copyId != copy.copyId || e.providerAccountId != copy.providerAccountId || e.vaultRootBinding != ctx.expectedVaultRootBinding ||
            e.originalByteLength != passport.exactByteLength || !e.trustedOriginalSha256.equals(passport.trustedOriginalSha256, true) ||
            e.physicalObjectSetId != copy.physicalObjectSetId || e.observedCopyRevision != copy.observedRevision || e.invalidationGeneration != copy.invalidationGeneration ||
            e.verifiedAt > ctx.now || ctx.now - e.verifiedAt > ctx.maxEvidenceAgeMillis || !sha256.matches(e.containerSha256) || e.containerBytes <= 0 || e.orderedParts.isEmpty()) return false
        var offset = 0L
        var bytes = 0L
        if (e.orderedParts.map { it.partIdentity }.distinct().size != e.orderedParts.size) return false
        for (part in e.orderedParts) {
            if (part.partIdentity.isBlank() || part.startOffset != offset || part.endOffset < offset || part.size <= 0 || !sha256.matches(part.digest) || part.observedRevision.isNullOrBlank()) return false
            offset = part.endOffset
            bytes = try { Math.addExact(bytes, part.size) } catch (_: ArithmeticException) { return false }
        }
        return offset == passport.exactByteLength && bytes == e.containerBytes
    }
}
