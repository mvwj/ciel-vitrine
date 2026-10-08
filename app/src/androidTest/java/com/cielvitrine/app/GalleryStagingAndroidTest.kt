package com.cielvitrine.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class GalleryStagingAndroidTest {
    @Test fun workingContainerComesFromCloudWithoutPhoneAccessAndBadFooterPreservesCommittedStage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = ByteArray(131079) { (it * 11).toByte() }; val encoded = ByteArrayOutputStream()
        val proof = ArchiveProof.from(Pos2.write(original.inputStream(), encoded, "live.mov", "video/quicktime", original.size.toLong()))
        var cloudBytes = encoded.toByteArray()
        val cold = ArchiveJob(ArchiveStore.randomId(), "123", "root", "content://unavailable-phone/source", "live.mov", "video/quicktime", original.size.toLong(),
            ArchivePhase.VERIFIED, proof, "cold-object", "v1", 1)
        val working = ArchiveJob(ArchiveStore.randomId(), cold.uid, cold.rootId, cold.source, cold.name, cold.mime, cold.sourceBytes,
            zone = OriginalZone.GALLERY, groupId = ArchiveStore.randomId(), coldId = cold.id)
        val journal = object : ArchiveJournal {
            override fun load(id: String) = cold.also { require(id == cold.id) }
            override fun save(job: ArchiveJob) { error("Staging cannot modify the journal") }
        }
        val provider = object : ArchiveCloud {
            override fun requireAccount(uid: String, rootId: String) {}
            override fun ensureColdFolders() {}
            override fun inspect(path: String) = ArchiveRemote("cold-object", "v1", cloudBytes.size.toLong(), null).also { require(path == cold.path) }
            override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) { error("Staging cannot upload") }
            override fun <T> readback(path: String, read: (InputStream) -> T): T { require(path == cold.path); return cloudBytes.inputStream().use(read) }
        }
        val staging = AndroidArchiveStaging(context, store = journal, cloud = provider)
        try {
            val made = staging.prepare(working)
            assertEquals(proof.originalSha256, made.originalSha256); assertNotEquals(proof.bundleId, made.bundleId)
            val restored = ByteArrayOutputStream(); staging.open(working.id).use { Pos2.read(it, restored, made.originalBytes, made.originalSha256) }
            assertArrayEquals(original, restored.toByteArray())
            cloudBytes[cloudBytes.lastIndex] = (cloudBytes.last().toInt() xor 1).toByte()
            try { staging.prepare(working); fail("Bad cold footer accepted") } catch (_: Exception) {}
            val stillGood = staging.open(working.id).use { Pos2.read(it, ColdArchive.Sink, made.originalBytes, made.originalSha256) }
            assertEquals(made, ArchiveProof.from(stillGood))
            for (suffix in listOf(".tmp", ".download")) assertFalse(File(context.noBackupFilesDir, "cold-staging/${working.id}$suffix").exists())
        } finally { staging.removeOwnedContainer(working.id) }
    }
}
