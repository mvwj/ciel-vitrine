package com.cielvitrine.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArchiveStoreAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(test: (File, String) -> Unit) {
        val alias = "ciel-test-archive-${UUID.randomUUID()}"; val directory = File(context.noBackupFilesDir, alias)
        try { test(directory, alias) } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias); deleteEntry("$alias.index"); deleteEntry("$alias.legacy") }
        }
    }
    private fun job(id: String = "a".repeat(32)) = ArchiveJob(id, null, null, "content://private/source", "private-name.heic", "image/heic", 123)
    @Test fun largeQueuedFileYieldsToNewFilesAndCursorSurvivesReopenWithoutChangingProofs() = isolated { directory,alias ->
        ArchiveStore(context,directory,alias).use { store ->
            store.save(job().copy(transport=OriginalTransport.MULTIPART,containerExtension=ContainerExtension.CV2))
            store.save(job("b".repeat(32)))
            assertEquals("a".repeat(32),store.next()!!.id)
            assertEquals("a".repeat(32),store.next()!!.id) // a status read is not a dequeue
            val original=store.load("a".repeat(32));store.yieldAfterStep(original.id)
            assertEquals(original,store.load(original.id));assertEquals("b".repeat(32),store.next()!!.id)
        }
        ArchiveStore(context,directory,alias).use { store ->
            assertEquals("b".repeat(32),store.next()!!.id);store.yieldAfterStep("b".repeat(32))
            assertEquals("a".repeat(32),store.next()!!.id)
            val proof=ArchiveProof("f".repeat(32),123,"d".repeat(64),900,"e".repeat(64))
            val next=store.load("b".repeat(32));store.save(next.copy(uid="123",rootId="root",proof=proof,phase=ArchivePhase.UPLOAD_UNCERTAIN))
            assertEquals(next.id,store.next()!!.id)
            store.save(store.load(next.id).copy(deferred=true));assertEquals("a".repeat(32),store.next()!!.id)
        }
    }
    @Test fun encryptedRowsSurviveRecreationAndRejectTamperIdentitySwapAndStaleWrites() = isolated { directory, alias ->
        ArchiveStore(context, directory, alias).use { store ->
            store.save(job()); val saved = store.load(job().id)
            assertEquals(job().copy(revision = 1), saved)
            try { store.save(job()); fail("Stale write accepted") } catch (_: IllegalArgumentException) {}
            store.save(saved.copy(notice = "updated")); store.save(job("b".repeat(32)))
        }
        ArchiveStore(context, directory, alias).use { store ->
            assertEquals("updated", store.load(job().id).notice)
            SQLiteDatabase.openDatabase(File(directory, "archive-control.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                val payload = raw.rawQuery("SELECT payload FROM jobs WHERE id=?", arrayOf(job().id)).use { it.moveToFirst(); it.getBlob(0) }
                assertFalse(payload.toString(Charsets.ISO_8859_1).contains("private-name"))
                raw.execSQL("UPDATE jobs SET payload=? WHERE id=?", arrayOf(payload, "b".repeat(32)))
                try { store.load("b".repeat(32)); fail("Identity swap accepted") } catch (_: Exception) {}
                payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
                raw.execSQL("UPDATE jobs SET payload=? WHERE id=?", arrayOf(payload, job().id))
                try { store.load(job().id); fail("Tamper accepted") } catch (_: Exception) {}
            }
        }
    }
    @Test fun scanPagesAndCursorCommitAtomicallyAndInventoryExceedsOldThousandFileLimit() = isolated { directory, alias ->
        ArchiveStore(context, directory, alias).use { store ->
            val batch = ArchiveBatch("c".repeat(32), "FULL", listOf(VolumeScan("external_primary", "epoch", 1440, 1)))
            store.createBatch(batch)
            fun member(n: Int) = job(n.toString(16).padStart(32, '0')).copy(batchId = batch.id,
                sourceStamp = SourceStamp("external_primary", "epoch", n.toLong(), 1, 1, 123, 100, 100, 0, null))
            for (page in 0 until 12) {
                val current = store.batch(batch.id)
                val next = current.copy(volumes = current.volumes.map { it.copy(afterId = ((page + 1) * 120).toLong()) })
                store.commitPage(next, (page * 120 + 1..(page + 1) * 120).map(::member))
            }
            assertEquals(1440L, store.counts().total)
            assertEquals(30, store.page().size)
            val first = store.page(); val second = store.page(beforeRow = first.last().first)
            assertTrue(first.map { it.second.id }.intersect(second.map { it.second.id }.toSet()).isEmpty())
            try { store.commitPage(batch, listOf(member(1500))); fail("Stale page committed") } catch (_: IllegalArgumentException) {}
            assertEquals(1440L, store.counts().total)
            val current = store.batch(batch.id)
            store.commitPage(current, listOf(member(1)))
            assertEquals(1440L, store.counts().total)
            assertEquals(1440L, store.batch(batch.id).volumes.single().afterId)
        }
    }
    @Test fun legacyMigrationIsIdempotentAndPreservesEncryptedSourceRecords() = isolated { directory, alias ->
        val legacy = LegacyArchiveStore(context, File(directory, "legacy"), "$alias.legacy")
        legacy.save(job().copy(uid = "123", rootId = "root"))
        val original = File(legacy.directory, "${job().id}.enc").readBytes()
        ArchiveStore(context, directory, alias).use { store ->
            store.importLegacy(legacy); store.importLegacy(legacy)
            assertEquals(1L, store.counts().total)
            assertEquals("123", store.load(job().id).uid)
            assertArrayEquals(original, File(legacy.directory, "${job().id}.enc").readBytes())
        }
    }
    @Test fun missingPrivateKeyCannotCreateReplacementOverExistingDatabase() = isolated { directory, alias ->
        ArchiveStore(context, directory, alias).use { it.save(job()) }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        ArchiveStore(context, directory, alias).use { store ->
            try { store.save(job("b".repeat(32))); fail("Existing database rebound to a new key") } catch (_: IllegalStateException) {}
        }
        assertFalse(keys.containsAlias(alias))
    }
    @Test fun missingPrivateIndexCannotSilentlyDuplicateAnExistingInventoryPage() = isolated { directory, alias ->
        val batch = ArchiveBatch("c".repeat(32), "FULL", listOf(VolumeScan("external_primary", "epoch", 1, 1)))
        ArchiveStore(context, directory, alias).use { store ->
            store.createBatch(batch)
            store.commitPage(store.batch(batch.id), listOf(job().copy(batchId = batch.id,
                sourceStamp = SourceStamp("external_primary", "epoch", 1, 1, 1, 123, 100, 100, 0, null))))
        }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$alias.index") }
        ArchiveStore(context, directory, alias).use { store ->
            try { store.counts(); fail("Inventory rebound to a new index key") } catch (_: IllegalStateException) {}
        }
        assertFalse(keys.containsAlias("$alias.index")); assertTrue(keys.containsAlias(alias))
    }
    @Test fun gallerySetRequiresEveryArchiveMemberVerifiedOnSameAccount() = isolated { directory, alias ->
        ArchiveStore(context, directory, alias).use { store ->
            val proof = ArchiveProof("f".repeat(32), 123, "d".repeat(64), 900, "e".repeat(64))
            val a = job().copy(uid = "123", rootId = "root", phase = ArchivePhase.VERIFIED, proof = proof,
                remoteId = "object-a", remoteModified = "v1", verifiedAt = 1)
            val b = a.copy(id = "b".repeat(32), phase = ArchivePhase.QUEUED, proof = null, remoteId = null, remoteModified = null, verifiedAt = null)
            store.save(a); store.save(b)
            try { store.createGallery(listOf(a.id, b.id), "Live"); fail("Incomplete archive accepted") } catch (_: IllegalArgumentException) {}
            assertEquals(2L, store.counts().total)
            store.save(store.load(b.id).copy(phase = ArchivePhase.VERIFIED, proof = proof, remoteId = "object-b", remoteModified = "v1", verifiedAt = 1, uid = "456"))
            try { store.createGallery(listOf(a.id, b.id), "Live"); fail("Mixed account set accepted") } catch (_: IllegalArgumentException) {}
            store.save(store.load(b.id).copy(uid = "123"))
            val group = store.createGallery(listOf(a.id, b.id), "Live")
            assertEquals(group, store.group(group.id))
            assertEquals(2L, store.counts().gallery)
            assertTrue(group.galleryIds.map(store::load).all { it.phase == ArchivePhase.QUEUED && it.zone == OriginalZone.GALLERY })
            store.setPaused(true); assertTrue(store.paused())
        }
    }
}
