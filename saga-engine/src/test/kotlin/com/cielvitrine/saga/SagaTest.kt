package com.cielvitrine.saga

import com.cielvitrine.core.model.*
import com.cielvitrine.storage.control.ControlDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class SagaTest {

    private lateinit var tempDbFile: File
    private lateinit var controlDb: ControlDatabase

    @BeforeEach
    fun setup() {
        tempDbFile = File.createTempFile("control_test_", ".db")
        tempDbFile.deleteOnExit()
        controlDb = ControlDatabase(tempDbFile)
    }

    private fun createDummyPassport(rvId: String): OriginalPassport {
        return OriginalPassport(
            resourceVersionId = rvId,
            exactByteLength = 1000L,
            trustedOriginalSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sourceDescriptorAtStart = SourceDescriptor("uri", 1000L, 0L, null, SourceRepresentationKind.EXACT_ORIGINAL),
            sourceDescriptorAtEnd = SourceDescriptor("uri", 1000L, 0L, null, SourceRepresentationKind.EXACT_ORIGINAL)
        )
    }

    @Test
    fun `test FreeUpPredicate rejects asset when missing Cold Archive evidence under strict policy`() {
        val assetId = UUID.randomUUID().toString()
        val rvId = UUID.randomUUID().toString()

        val asset = Asset(assetId, rvId, listOf(rvId), 0L, 0L)
        val rv = ResourceVersion(
            rvId, "res-1", assetId, SourceRepresentationKind.EXACT_ORIGINAL,
            1000L, "hash", 0L
        )

        controlDb.insertResourceVersion(rv)
        controlDb.insertPassport(createDummyPassport(rvId))

        // Only add Gallery Store copy, NO Cold Archive copy
        val galleryCopy = ExactCopyLocation(
            copyId = "copy-gallery",
            resourceVersionId = rvId,
            role = CopyRole.GALLERY_STORE,
            providerAccountId = "acc-1",
            physicalObjectSetId = "pos-1",
            failureDomainIds = listOf("domain-1"),
            observedRevision = "rev-1",
            state = CopyState.VERIFIED,
            lastObservedAt = System.currentTimeMillis()
        )
        controlDb.upsertCopyLocation(galleryCopy)
        controlDb.insertVerificationEvidence(
            VerificationEvidence(
                evidenceId = "ev-1",
                resourceVersionId = rvId,
                originalByteLength = 1000L,
                trustedOriginalSha256 = "hash",
                copyId = "copy-gallery",
                providerAccountId = "acc-1",
                vaultRootBinding = "root",
                containerFormatVersion = 1,
                containerBytes = 1100L,
                containerSha256 = "cont-hash",
                orderedParts = emptyList(),
                method = VerificationMethod.FULL_RECOVERY_READBACK,
                verifiedAt = System.currentTimeMillis(),
                observedDownloadability = true
            )
        )

        val ctx = FreeUpEvaluationContext(
            explicitUserScopeIncludesLocalRemoval = true,
            acceptedWriterHeadFresh = true,
            noKnownFork = true,
            reservationHeld = true,
            recoverableGalleryCatalogCommitExists = true,
            policy = DeletionSafetyPolicy.ARCHIVE_GATED_STRICT
        )

        val result = FreeUpPredicate.evaluate(asset, listOf(rv), controlDb, ctx)
        assertFalse(result.isPermitted)
        assertTrue(result.rejectionReasons.any { it.contains("lacks verified Cold Archive copy") })
    }

    @Test
    fun `test FreeUpPredicate permits when all copies verified for all resources`() {
        val assetId = UUID.randomUUID().toString()
        val rvId = UUID.randomUUID().toString()

        val asset = Asset(assetId, rvId, listOf(rvId), 0L, 0L)
        val rv = ResourceVersion(
            rvId, "res-1", assetId, SourceRepresentationKind.EXACT_ORIGINAL,
            1000L, "hash", 0L
        )

        controlDb.insertResourceVersion(rv)
        controlDb.insertPassport(createDummyPassport(rvId))

        // Add Gallery Store copy + evidence
        val galleryCopy = ExactCopyLocation(
            "copy-gallery", rvId, CopyRole.GALLERY_STORE, "acc-1", "pos-1",
            listOf("domain-1"), "rev-1", CopyState.VERIFIED, System.currentTimeMillis()
        )
        controlDb.upsertCopyLocation(galleryCopy)
        controlDb.insertVerificationEvidence(
            VerificationEvidence(
                "ev-g", rvId, 1000L, "hash", "copy-gallery", "acc-1", "root",
                1, 1100L, "chash", emptyList(), VerificationMethod.FULL_RECOVERY_READBACK,
                System.currentTimeMillis(), true
            )
        )

        // Add Cold Archive copy + evidence
        val archiveCopy = ExactCopyLocation(
            "copy-archive", rvId, CopyRole.COLD_ARCHIVE, "acc-1", "pos-2",
            listOf("domain-1"), "rev-2", CopyState.VERIFIED, System.currentTimeMillis()
        )
        controlDb.upsertCopyLocation(archiveCopy)
        controlDb.insertVerificationEvidence(
            VerificationEvidence(
                "ev-a", rvId, 1000L, "hash", "copy-archive", "acc-1", "root",
                1, 1100L, "chash2", emptyList(), VerificationMethod.FULL_RECOVERY_READBACK,
                System.currentTimeMillis(), true
            )
        )

        val ctx = FreeUpEvaluationContext(
            explicitUserScopeIncludesLocalRemoval = true,
            acceptedWriterHeadFresh = true,
            noKnownFork = true,
            reservationHeld = true,
            recoverableGalleryCatalogCommitExists = true,
            policy = DeletionSafetyPolicy.ARCHIVE_GATED_STRICT
        )

        val result = FreeUpPredicate.evaluate(asset, listOf(rv), controlDb, ctx)
        assertTrue(result.isPermitted)
        assertTrue(result.rejectionReasons.isEmpty())
    }

    @Test
    fun `test TrashEngine protects last copy after 60 days when not explicitly authorized`() {
        val now = System.currentTimeMillis()
        val trash = TrashEngine.createTrashRecord(
            assetId = "asset-1",
            authorizedPurgeScope = listOf(CopyRole.GALLERY_STORE),
            explicitLastCopyPurgeAuthorized = false,
            trashedAt = now - (61L * 24 * 60 * 60 * 1000) // 61 days ago
        )

        // If no remaining copies outside purge scope:
        val status = TrashEngine.evaluateTrashExpiry(
            trashRecord = trash,
            currentTime = now,
            remainingCopiesExcludingPurgeScope = emptyList()
        )

        // Must transition to EXPIRED_AWAITING_CONFIRMATION, never delete last copy silently (TRASH-003)
        assertEquals(TrashStatus.EXPIRED_AWAITING_CONFIRMATION, status)
    }
}
