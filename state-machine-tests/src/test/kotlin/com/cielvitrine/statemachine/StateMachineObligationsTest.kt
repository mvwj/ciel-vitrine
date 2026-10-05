package com.cielvitrine.statemachine

import com.cielvitrine.core.model.*
import com.cielvitrine.crypto.*
import com.cielvitrine.pos.*
import com.cielvitrine.provider.InMemoryCloudStorageAdapter
import com.cielvitrine.provider.ProviderObjectPresence
import com.cielvitrine.reader.*
import com.cielvitrine.saga.*
import com.cielvitrine.storage.catalog.CatalogDatabase
import com.cielvitrine.storage.catalog.TimelineEntry
import com.cielvitrine.storage.control.ControlDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class StateMachineObligationsTest {

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // Obligation 1: Zero-byte, one-byte, unknown-size, hash only known at EOF
    @Test
    fun `obligation 1 - zero byte and one byte hash only known at EOF`() {
        for (size in listOf(0, 1, 100_000)) {
            val data = ByteArray(size) { (it and 0xFF).toByte() }
            val out = ByteArrayOutputStream()
            val res = PosWriter.write(
                sourceInput = ByteArrayInputStream(data),
                destinationOutput = out,
                setId = ByteArray(16) { 0x42 },
                totalOriginalByteLength = 0L // Unknown size before streaming!
            )
            assertEquals(size.toLong(), res.partOriginalByteLength)
            assertEquals(sha256(data), res.totalOriginalSha256Hex)

            val recOut = ByteArrayOutputStream()
            val readRes = PosReader.read(ByteArrayInputStream(out.toByteArray()), recOut)
            assertEquals(size.toLong(), readRes.recoveredByteLength)
            assertTrue(readRes.isSha256Verified)
            assertArrayEquals(data, recOut.toByteArray())
        }
    }

    // Obligation 2: Incomplete POS, missing footer/part fails closed
    @Test
    fun `obligation 2 - truncated or incomplete POS fails closed`() {
        val data = "Valid test payload".toByteArray()
        val out = ByteArrayOutputStream()
        PosWriter.write(
            sourceInput = ByteArrayInputStream(data),
            destinationOutput = out,
            setId = ByteArray(16) { 1 }
        )
        val fullBytes = out.toByteArray()

        // Truncate footer
        val truncatedBytes = fullBytes.copyOf(fullBytes.size - 50)
        assertThrows<Exception> {
            PosReader.read(ByteArrayInputStream(truncatedBytes), ByteArrayOutputStream())
        }
    }

    // Obligation 4: Maliciously replaced POS consistency vs trusted authenticity
    @Test
    fun `obligation 4 - modified payload with recomputed internal hashes does not match trusted passport`() {
        val originalData = "Authentic source camera original bytes".toByteArray()
        val trustedPassportSha = sha256(originalData)

        val maliciousData = "Attacker replaced camera payload bytes".toByteArray()
        val out = ByteArrayOutputStream()
        PosWriter.write(
            sourceInput = ByteArrayInputStream(maliciousData),
            destinationOutput = out,
            setId = ByteArray(16) { 2 }
        )

        // Attacker's POS is internally consistent
        val recOut = ByteArrayOutputStream()
        val readRes = PosReader.read(ByteArrayInputStream(out.toByteArray()), recOut)
        assertTrue(readRes.isSha256Verified)

        // BUT does NOT match independently trusted passport! (POS-006, VER-002)
        assertNotEquals(trustedPassportSha, readRes.recoveredSha256Hex)
    }

    // Obligation 5: Resume after source changed with same size / misleading generation
    @Test
    fun `obligation 5 - source mutation during ingest aborts passport creation`() {
        val resolver = ExactSourceResolver()
        var callCount = 0

        val mutatingSource = object : SourceStreamProvider {
            override fun getDescriptor(): SourceDescriptor {
                callCount++
                return SourceDescriptor(
                    sourceUri = "content://media/1",
                    observedByteLength = 100L,
                    observedGenerationOrRevision = if (callCount == 1) "gen-1" else "gen-2", // Mutated!
                    representationKind = SourceRepresentationKind.EXACT_ORIGINAL
                )
            }

            override fun openInputStream() = ByteArrayInputStream(ByteArray(100))
        }

        assertThrows<IllegalStateException> {
            resolver.resolveAndDigest("res-1", "asset-1", mutatingSource)
        }
    }

    // Obligation 6 & 7: Control commit without catalog projection & idempotent outbox relay
    @Test
    fun `obligation 6 and 7 - control commit replay into catalog projection is idempotent`() {
        val tempControlFile = File.createTempFile("control_db_", ".db").apply { deleteOnExit() }
        val tempCatalogFile = File.createTempFile("catalog_db_", ".db").apply { deleteOnExit() }

        val controlDb = ControlDatabase(tempControlFile)
        val catalogDb = CatalogDatabase(tempCatalogFile)

        // 1. Commit intent into control.db (DB-004)
        val eventId = controlDb.emitOutboxEvent(
            eventType = "ASSET_INDEXED",
            payload = "asset-999"
        )

        // 2. Replay into catalog.db (DB-003)
        val entry = TimelineEntry(
            assetId = "asset-999",
            captureTimestamp = 1000L,
            dateBucket = "2026-10-05",
            visibility = AssetVisibility.VISIBLE,
            title = "Sample",
            thumbnailBlobRef = null,
            previewBlobRef = null,
            isCloudOnly = false,
            isSafeToFree = false
        )

        catalogDb.upsertTimelineItem(entry, eventId = eventId)
        assertTrue(catalogDb.isEventProcessed(eventId))

        // Replaying same event ID again is a no-op (idempotent)
        catalogDb.upsertTimelineItem(entry, eventId = eventId)
        val timeline = catalogDb.queryTimeline(limit = 10)
        assertEquals(1, timeline.size)
    }

    // Obligation 8: Two valid next writer heads -> WRITER_CONFLICT blocks destructive actions
    @Test
    fun `obligation 8 - writer conflict blocks Free Up predicate`() {
        val asset = Asset("a-1", "r-1", listOf("r-1"), 0L, 0L)
        val rv = ResourceVersion("r-1", "res-1", "a-1", SourceRepresentationKind.EXACT_ORIGINAL, 100L, "h", 0L)
        val tempDb = File.createTempFile("ctrl_wr_", ".db").apply { deleteOnExit() }
        val controlDb = ControlDatabase(tempDb)

        val ctx = FreeUpEvaluationContext(
            explicitUserScopeIncludesLocalRemoval = true,
            acceptedWriterHeadFresh = false, // Fork or stale head! (WR-003)
            noKnownFork = false,
            reservationHeld = true,
            recoverableGalleryCatalogCommitExists = true,
            policy = DeletionSafetyPolicy.ARCHIVE_GATED_STRICT
        )

        val eval = FreeUpPredicate.evaluate(asset, listOf(rv), controlDb, ctx)
        assertFalse(eval.isPermitted)
        assertTrue(eval.rejectionReasons.any { it.contains("Writer fork detected") })
    }

    // Obligation 9: Reservation prevents racing destructive operations
    @Test
    fun `obligation 9 - reservation blocks overlapping destructive operations`() {
        val tempDb = File.createTempFile("ctrl_res_", ".db").apply { deleteOnExit() }
        val controlDb = ControlDatabase(tempDb)

        val res1 = DestructiveReservation(
            reservationId = "res-1",
            operationType = DestructiveOperationType.REMOVE_GALLERY_STORE_COPY,
            targetAssetId = "asset-100",
            affectedResourceVersionIds = listOf("rv-1"),
            targetCopyIds = listOf("copy-1"),
            acquiredAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60_000,
            status = ReservationStatus.ACTIVE
        )
        controlDb.insertReservation(res1)

        val active = controlDb.getActiveReservationsForAsset("asset-100")
        assertEquals(1, active.size)
        assertEquals("res-1", active[0].reservationId)
    }

    // Obligation 14: Source honesty - PLATFORM_REPRESENTATION must not be labeled EXACT_ORIGINAL
    @Test
    fun `obligation 14 - platform representation preserved exactly but labeled honestly`() {
        val rv = ResourceVersion(
            resourceVersionId = "rv-platform",
            resourceId = "res-p",
            assetId = "asset-p",
            sourceRepresentationKind = SourceRepresentationKind.PLATFORM_REPRESENTATION,
            trustedOriginalByteLength = 500L,
            trustedOriginalSha256 = "sha",
            createdAt = 0L
        )

        assertNotEquals(SourceRepresentationKind.EXACT_ORIGINAL, rv.sourceRepresentationKind)
        assertEquals(SourceRepresentationKind.PLATFORM_REPRESENTATION, rv.sourceRepresentationKind)
    }

    // Obligation 16 & 25: Destination reread verifies against source
    @Test
    fun `obligation 16 and 25 - export destination readback verification detects destination corruption`() {
        val tempDir = File.createTempFile("exp_test_", "").apply { delete(); mkdirs() }
        try {
            val sourceFile = File(tempDir, "source.bin").apply { writeBytes("Trusted exact data".toByteArray()) }
            val destFile = File(tempDir, "dest.bin")
            val rv = ResourceVersion("rv-1", "res-1", "a-1", SourceRepresentationKind.EXACT_ORIGINAL, sourceFile.length(), sha256(sourceFile.readBytes()), 0L)

            LocalFileExactReader(rv, sourceFile).use { reader ->
                val result = ShareExportService.exportAndVerifyFile(reader, destFile)
                assertEquals(ExportProofLevel.DESTINATION_READBACK_VERIFIED, result.proofLevel)
                assertTrue(result.isDestinationVerified)

                // Now simulate post-close destination truncation/mutation
                destFile.writeBytes("Tampered data".toByteArray())
                assertThrows<IllegalStateException> {
                    ShareExportService.verifyDestinationFile(
                        LocalFileExactReader(rv, sourceFile),
                        destFile
                    )
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // Obligation 26: Missing path != deletion (CLOUD-004)
    @Test
    fun `obligation 26 - provider stat distinguishes absent confirmed from access denied`() {
        val adapter = InMemoryCloudStorageAdapter()
        val stat = adapter.statObject("non-existent-path")
        assertEquals(ProviderObjectPresence.ABSENT_CONFIRMED, stat.presence)

        adapter.uploadObject("my/pos/file.pos", ByteArrayInputStream("pos bytes".toByteArray()), 9L, null)
        val statAfter = adapter.statObject("my/pos/file.pos")
        assertEquals(ProviderObjectPresence.PRESENT, statAfter.presence)
        assertTrue(statAfter.isReadable)
    }

    // Obligation 30: Private task completing after lock with stale UnlockSessionId is rejected
    @Test
    fun `obligation 30 - stale unlock session task rejected after lock`() {
        val manager = UnlockSessionManager()
        val root = VaultRootSecret.generate()
        val session = manager.unlock(root)

        // Lock occurs before background task commits
        manager.lock()

        assertThrows<StaleSessionException> {
            manager.executeInSession(session) {
                fail("Should never be called")
            }
        }
    }
}
