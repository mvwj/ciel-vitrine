package com.cielvitrine.saga

import com.cielvitrine.core.model.*
import com.cielvitrine.storage.control.ControlDatabase
import java.util.UUID

interface PlatformDeletionAdapter {
    fun deleteLocalSource(uri: String): Boolean
    fun verifySourceAbsent(uri: String): Boolean
}

data class SagaExecutionResult(
    val reservationId: String,
    val isSuccess: Boolean,
    val failureReason: String? = null
)

/**
 * DEL-001..DEL-004: Unified destructive operation saga engine.
 */
class DestructiveSagaEngine(
    private val controlDb: ControlDatabase,
    private val platformAdapter: PlatformDeletionAdapter
) {

    fun executeFreeUpDevice(
        asset: Asset,
        resourceVersions: List<ResourceVersion>,
        localSourceUris: List<String>,
        ctx: FreeUpEvaluationContext
    ): SagaExecutionResult {
        val reservationId = UUID.randomUUID().toString()
        val rvIds = resourceVersions.map { it.resourceVersionId }

        // Step 1 & 2: Durably acquire reservation (DEL-002, DB-004)
        val reservation = DestructiveReservation(
            reservationId = reservationId,
            operationType = DestructiveOperationType.FREE_UP_DEVICE,
            targetAssetId = asset.assetId,
            affectedResourceVersionIds = rvIds,
            targetCopyIds = emptyList(),
            acquiredAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600_000, // 10 minutes
            status = ReservationStatus.ACTIVE
        )
        controlDb.insertReservation(reservation)

        try {
            // Step 3-6: Evaluate strict safety predicate (DEL-005, DEL-006)
            val eval = FreeUpPredicate.evaluate(
                asset = asset,
                resourceVersions = resourceVersions,
                controlDb = controlDb,
                ctx = ctx.copy(reservationHeld = true)
            )

            if (!eval.isPermitted) {
                controlDb.updateReservationStatus(reservationId, ReservationStatus.CANCELLED)
                return SagaExecutionResult(
                    reservationId = reservationId,
                    isSuccess = false,
                    failureReason = "Safety preflight failed: ${eval.rejectionReasons.joinToString("; ")}"
                )
            }

            controlDb.updateReservationStatus(reservationId, ReservationStatus.EXECUTING)

            // Step 7: Execute deletion step via platform adapter
            for (uri in localSourceUris) {
                val ok = platformAdapter.deleteLocalSource(uri)
                if (!ok) {
                    controlDb.updateReservationStatus(reservationId, ReservationStatus.CANCELLED)
                    return SagaExecutionResult(
                        reservationId = reservationId,
                        isSuccess = false,
                        failureReason = "Platform failed to delete local source: $uri"
                    )
                }

                // Step 8: Reconcile actual result - no inferred deletion (DEL-004)
                val isConfirmedAbsent = platformAdapter.verifySourceAbsent(uri)
                if (!isConfirmedAbsent) {
                    return SagaExecutionResult(
                        reservationId = reservationId,
                        isSuccess = false,
                        failureReason = "Reconciliation failed: source still present after delete call for $uri"
                    )
                }
            }

            // Step 9: Commit observed result
            controlDb.emitOutboxEvent(
                eventType = "LOCAL_SOURCE_FREED",
                payload = """{"assetId": "${asset.assetId}"}"""
            )

            // Step 10: Terminal release
            controlDb.updateReservationStatus(reservationId, ReservationStatus.COMPLETED)
            return SagaExecutionResult(reservationId = reservationId, isSuccess = true)

        } catch (e: Exception) {
            controlDb.updateReservationStatus(reservationId, ReservationStatus.CANCELLED)
            return SagaExecutionResult(
                reservationId = reservationId,
                isSuccess = false,
                failureReason = e.message
            )
        }
    }
}
