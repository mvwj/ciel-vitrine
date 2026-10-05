package com.cielvitrine.saga

import com.cielvitrine.core.model.*
import java.util.UUID

object TrashEngine {

    const val TRASH_RETENTION_MILLIS: Long = 60L * 24 * 60 * 60 * 1000 // 60 days

    fun createTrashRecord(
        assetId: String,
        authorizedPurgeScope: List<CopyRole> = listOf(CopyRole.GALLERY_STORE),
        explicitLastCopyPurgeAuthorized: Boolean = false,
        trashedAt: Long = System.currentTimeMillis()
    ): TrashRecord {
        return TrashRecord(
            trashId = UUID.randomUUID().toString(),
            assetId = assetId,
            trashedAt = trashedAt,
            retainUntil = trashedAt + TRASH_RETENTION_MILLIS,
            authorizedPurgeScope = authorizedPurgeScope,
            explicitLastCopyPurgeAuthorized = explicitLastCopyPurgeAuthorized,
            status = TrashStatus.IN_TRASH
        )
    }

    /**
     * TRASH-003: Expiry safety check.
     */
    fun evaluateTrashExpiry(
        trashRecord: TrashRecord,
        currentTime: Long,
        remainingCopiesExcludingPurgeScope: List<ExactCopyLocation>
    ): TrashStatus {
        if (currentTime < trashRecord.retainUntil) {
            return TrashStatus.IN_TRASH // Retention period has not expired yet
        }

        // 60 days reached:
        val hasRemainingExactCopy = remainingCopiesExcludingPurgeScope.any {
            it.state == CopyState.VERIFIED || it.state == CopyState.COMMITTED
        }

        if (!hasRemainingExactCopy && !trashRecord.explicitLastCopyPurgeAuthorized) {
            // TRASH-003: Would remove last copy and not explicitly authorized -> block and await confirmation
            return TrashStatus.EXPIRED_AWAITING_CONFIRMATION
        }

        return TrashStatus.PURGE_DISPATCHED
    }
}
