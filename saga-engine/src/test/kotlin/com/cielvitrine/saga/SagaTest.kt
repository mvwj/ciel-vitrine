package com.cielvitrine.saga

import com.cielvitrine.core.model.*
import com.cielvitrine.storage.control.ControlDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SagaTest {
    private lateinit var file: File
    private lateinit var db: ControlDatabase
    private val hash = "ab".repeat(32)
    private val now = 1_000_000L
    private val asset = Asset("a", "still", listOf("still", "motion"), 0, 0)
    private val versions = listOf("still", "motion").map { ResourceVersion("v-$it", it, "a", SourceRepresentationKind.EXACT_ORIGINAL, 1000, hash, 0) }
    private fun context() = FreeUpEvaluationContext(true, true, true, true, true, DeletionSafetyPolicy.ARCHIVE_GATED_STRICT,
        ResourceSetRevision("set", "a", versions.map { it.resourceVersionId }, 1, true, now), "account", "vault", now)
    private fun evidence(v: ResourceVersion, role: CopyRole, id: String = "${v.resourceVersionId}-$role") = VerificationEvidence(
        "e-$id", v.resourceVersionId, 1000, hash, id, "account", "vault", 1, 1300, hash,
        listOf(PartEvidence("part-$id", 0, 1000, 1300, hash, "revision")), VerificationMethod.FULL_RECOVERY_READBACK, now, true,
        physicalObjectSetId = "physical-$id", observedCopyRevision = "revision")

    @BeforeEach fun setup() {
        file = File.createTempFile("safety-", ".db").apply { deleteOnExit() }
        db = ControlDatabase(file)
        versions.forEach { v ->
            db.insertResourceVersion(v)
            val source = SourceDescriptor("content://source/${v.resourceId}", 1000, 0, "g1", SourceRepresentationKind.EXACT_ORIGINAL)
            db.insertPassport(OriginalPassport(v.resourceVersionId, 1000, hash, source, source, mapOf("colorSpace" to "Display P3"), now))
            listOf(CopyRole.GALLERY_STORE, CopyRole.COLD_ARCHIVE).forEach { role ->
                val e = evidence(v, role)
                db.upsertCopyLocation(ExactCopyLocation(e.copyId, v.resourceVersionId, role, "account", e.physicalObjectSetId!!, listOf("same-provider"), "revision", CopyState.VERIFIED, now))
                db.insertVerificationEvidence(e)
            }
        }
    }

    @Test fun `complete independent physical copies permit preflight`() { assertTrue(FreeUpPredicate.evaluate(asset, versions, db, context()).isPermitted) }
    @Test fun `all proof and passport fields survive reopening database`() {
        val reopened = ControlDatabase(file)
        assertEquals(evidence(versions[0], CopyRole.COLD_ARCHIVE), reopened.getLatestEvidence("v-still", "v-still-COLD_ARCHIVE"))
        assertEquals("Display P3", reopened.getPassport("v-still")!!.mediaDiagnostics["colorSpace"])
        assertEquals("content://source/still", reopened.getPassport("v-still")!!.sourceDescriptorAtEnd.sourceUri)
    }
    @Test fun `missing paired video blocks whole live photo`() { assertFalse(FreeUpPredicate.evaluate(asset, versions.take(1), db, context()).isPermitted) }
    @Test fun `substituted source digest in readable evidence fails closed`() {
        val e = evidence(versions[0], CopyRole.COLD_ARCHIVE).copy(evidenceId = "bad", trustedOriginalSha256 = "cd".repeat(32), verifiedAt = now + 1)
        db.insertVerificationEvidence(e)
        assertFalse(FreeUpPredicate.evaluate(asset, versions, db, context().copy(now = now + 1)).isPermitted)
    }
    @Test fun `stale and invalidated proofs fail closed`() {
        assertFalse(FreeUpPredicate.evaluate(asset, versions, db, context().copy(now = now + 60_001)).isPermitted)
        val copy = db.getCopiesForVersion("v-still").first { it.role == CopyRole.COLD_ARCHIVE }
        db.upsertCopyLocation(copy.copy(invalidationGeneration = 1))
        assertFalse(FreeUpPredicate.evaluate(asset, versions, db, context()).isPermitted)
    }
    @Test fun `same physical object cannot count as two copies`() {
        val v = versions[0]
        val gallery = db.getCopiesForVersion(v.resourceVersionId).first { it.role == CopyRole.GALLERY_STORE }
        val archive = db.getCopiesForVersion(v.resourceVersionId).first { it.role == CopyRole.COLD_ARCHIVE }
        db.upsertCopyLocation(archive.copy(physicalObjectSetId = gallery.physicalObjectSetId))
        db.insertVerificationEvidence(evidence(v, CopyRole.COLD_ARCHIVE).copy(evidenceId = "same", physicalObjectSetId = gallery.physicalObjectSetId, verifiedAt = now + 1))
        assertFalse(FreeUpPredicate.evaluate(asset, versions, db, context().copy(now = now + 1)).isPermitted)
    }
    @Test fun `old prototype evidence cannot authorize removal`() {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c -> c.createStatement().use { it.execute("UPDATE verification_evidence SET evidence_json = NULL") } }
        assertFalse(FreeUpPredicate.evaluate(asset, versions, db, context()).isPermitted)
    }
    @Test fun `two database instances cannot reserve same version concurrently`() {
        val barrier = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map { i -> pool.submit<Boolean> {
                val other = ControlDatabase(file)
                barrier.await()
                other.tryAcquireReservation(DestructiveReservation("r$i", DestructiveOperationType.FREE_UP_DEVICE, "asset-$i", listOf("v-still"), emptyList(), now, now + 60000, status = ReservationStatus.ACTIVE))
            } }
            barrier.countDown()
            assertEquals(1, attempts.count { it.get() })
        } finally { pool.shutdownNow() }
    }
    @Test fun `uncertain platform deletion retains executing reservation`() {
        val platform = object : PlatformDeletionAdapter {
            override fun deleteLocalSource(uri: String): Boolean = throw IllegalStateException("Lost response after dispatch")
            override fun verifySourceAbsent(uri: String) = false
        }
        val result = DestructiveSagaEngine(db, platform).executeFreeUpDevice(asset, versions, versions.map { "content://source/${it.resourceId}" }, context())
        assertFalse(result.isSuccess)
        assertEquals(ReservationStatus.EXECUTING, db.getActiveReservationsForAsset("a").single().status)
        assertTrue(db.getUnrelayedOutboxEvents().isEmpty())
    }
    @Test fun `unrelated deletion URI is rejected before platform call`() {
        val platform = object : PlatformDeletionAdapter {
            override fun deleteLocalSource(uri: String): Boolean = error("Must not call")
            override fun verifySourceAbsent(uri: String) = false
        }
        assertFalse(DestructiveSagaEngine(db, platform).executeFreeUpDevice(asset, versions, listOf("content://unrelated"), context()).isSuccess)
    }
    @Test fun `trash does not purge restored records or last compound resource`() {
        val trash = TrashEngine.createTrashRecord("a", trashedAt = 0)
        assertEquals(TrashStatus.EXPIRED_AWAITING_CONFIRMATION, TrashEngine.evaluateTrashExpiry(trash, Long.MAX_VALUE, db.getCopiesForVersion("v-still"), versions.map { it.resourceVersionId }.toSet()))
        assertEquals(TrashStatus.RESTORED, TrashEngine.evaluateTrashExpiry(trash.copy(status = TrashStatus.RESTORED), Long.MAX_VALUE, emptyList()))
    }
    @Test fun `successful deletion commits terminal state with outbox`() {
        val platform = object : PlatformDeletionAdapter {
            override fun deleteLocalSource(uri: String) = true
            override fun verifySourceAbsent(uri: String) = true
        }
        assertTrue(DestructiveSagaEngine(db, platform).executeFreeUpDevice(asset, versions, versions.map { "content://source/${it.resourceId}" }, context()).isSuccess)
        assertTrue(db.getActiveReservationsForAsset("a").isEmpty())
        assertEquals(1, db.getUnrelayedOutboxEvents().size)
    }
}
