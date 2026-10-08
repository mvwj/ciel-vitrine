package com.cielvitrine.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.ArchiveJob
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArchiveStoreAndroidTest {
    @Test fun encryptedJournalSurvivesRecreationAndDetectsTamperAndIdentitySwap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "ciel-test-archive-${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, alias)
        try {
            val store = ArchiveStore(context, directory, alias)
            val job = ArchiveJob("a".repeat(32), "123", "root-123", "content://private/source", "private-name.heic", "image/heic", 123)
            store.save(job)
            val file = File(directory, "${job.id}.enc")
            val encrypted = file.readBytes()
            assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("private-name"))
            assertEquals(job, ArchiveStore(context, directory, alias).load(job.id))
            store.save(job)
            assertFalse(encrypted.contentEquals(file.readBytes()))
            val otherId = "b".repeat(32)
            val other = File(directory, "$otherId.enc")
            other.writeBytes(file.readBytes())
            try { store.load(otherId); fail("Journal identity substitution accepted") } catch (_: Exception) {}
            val tampered = file.readBytes(); tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte(); file.writeBytes(tampered)
            try { store.load(job.id); fail("Tampered journal accepted") } catch (_: Exception) {}
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
