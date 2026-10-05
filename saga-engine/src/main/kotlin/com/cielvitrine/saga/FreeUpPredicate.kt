package com.cielvitrine.saga

import com.cielvitrine.core.model.*
import com.cielvitrine.storage.control.ControlDatabase

data class FreeUpEvaluationContext(
    val explicitUserScopeIncludesLocalRemoval: Boolean,
    val acceptedWriterHeadFresh: Boolean,
    val noKnownFork: Boolean,
    val reservationHeld: Boolean,
    val recoverableGalleryCatalogCommitExists: Boolean,
    val policy: DeletionSafetyPolicy
)

data class FreeUpEvaluationResult(
    val isPermitted: Boolean,
    val rejectionReasons: List<String>
)

/**
 * DEL-005: Strict Free Up predicate.
 * DEL-006: Multi-resource all-or-partial semantics.
 */
object FreeUpPredicate {

    fun evaluate(
        asset: Asset,
        resourceVersions: List<ResourceVersion>,
        controlDb: ControlDatabase,
        ctx: FreeUpEvaluationContext
    ): FreeUpEvaluationResult {
        val reasons = mutableListOf<String>()

        if (!ctx.explicitUserScopeIncludesLocalRemoval) {
            reasons.add("User scope does not explicitly include local removal")
        }
        if (!ctx.acceptedWriterHeadFresh) {
            reasons.add("Accepted writer head is stale")
        }
        if (!ctx.noKnownFork) {
            reasons.add("Writer fork detected (WRITER_CONFLICT)")
        }
        if (!ctx.reservationHeld) {
            reasons.add("Durable reservation is not held")
        }
        if (!ctx.recoverableGalleryCatalogCommitExists) {
            reasons.add("Recoverable gallery catalog commit does not exist")
        }

        // Verify EVERY authoritative resource version
        if (resourceVersions.isEmpty()) {
            reasons.add("Asset has no associated resource versions")
        }

        for (rv in resourceVersions) {
            val copies = controlDb.getCopiesForVersion(rv.resourceVersionId)
            val passport = controlDb.getPassport(rv.resourceVersionId)

            if (passport == null) {
                reasons.add("ResourceVersion ${rv.resourceVersionId} has no durably committed passport")
                continue
            }

            val galleryCopy = copies.firstOrNull { it.role == CopyRole.GALLERY_STORE && it.state == CopyState.VERIFIED }
            val archiveCopy = copies.firstOrNull { it.role == CopyRole.COLD_ARCHIVE && it.state == CopyState.VERIFIED }

            if (galleryCopy == null) {
                reasons.add("ResourceVersion ${rv.resourceVersionId} lacks verified Gallery Store copy")
            } else {
                val galleryEvidence = controlDb.getLatestEvidence(rv.resourceVersionId, galleryCopy.copyId)
                if (galleryEvidence == null || !galleryEvidence.observedDownloadability) {
                    reasons.add("ResourceVersion ${rv.resourceVersionId} lacks readable Gallery Store evidence")
                }
            }

            if (ctx.policy == DeletionSafetyPolicy.ARCHIVE_GATED_STRICT) {
                if (archiveCopy == null) {
                    reasons.add("ResourceVersion ${rv.resourceVersionId} lacks verified Cold Archive copy")
                } else {
                    val archiveEvidence = controlDb.getLatestEvidence(rv.resourceVersionId, archiveCopy.copyId)
                    if (archiveEvidence == null || !archiveEvidence.observedDownloadability) {
                        reasons.add("ResourceVersion ${rv.resourceVersionId} lacks readable Cold Archive evidence")
                    }
                }
            }
        }

        return FreeUpEvaluationResult(
            isPermitted = reasons.isEmpty(),
            rejectionReasons = reasons
        )
    }
}
