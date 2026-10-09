package com.cielvitrine.app

import android.net.Uri
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID

/** Only files created in this run's private cache are ever removed. No personal MediaStore data. */
@RunWith(AndroidJUnit4::class)
class TenPhotoRoundtripAndroidTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    @Test fun tenRealPhotographsRecoverFromIndependentCloudStorageWithoutTheirPhoneSource() {
        val remote = File(context.cacheDir, "test-cloud-${UUID.randomUUID()}").apply { mkdirs() }
        val cloud = object : ArchiveCloud {
            fun file(path: String) = File(remote, path.substringAfterLast('/'))
            override fun requireAccount(uid: String, rootId: String) { require(uid == "123" && rootId == "test-root") }
            override fun ensureColdFolders() {}
            override fun ensureGalleryFolders() {}
            override fun inspect(path: String) = file(path).takeIf { it.exists() }?.let { ArchiveRemote(path, "v1", it.length(), null) }
            override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) {
                val target = file(path); check(!target.exists())
                target.outputStream().use { output -> open().use { it.copyTo(output) } }; check(target.length() == bytes)
            }
            override fun <T> readback(path: String, read: (InputStream) -> T): T = file(path).inputStream().use(read)
        }
        try { exercise(cloud, "123", "test-root", "simulated-cloud") }
        finally { remote.listFiles()?.forEach { it.delete() }; remote.delete() }
    }
    @Test fun tenPhotographsOnRealYandexWhenAccountHasBeenConnected() {
        val saved = YandexSessionStore(context).load()
        println("CIEL_YANDEX_CONNECTED=${saved != null && !saved.appRootId.isNullOrBlank()}")
        assumeTrue("Real Yandex account is not connected; no real-cloud claim", saved != null && !saved.appRootId.isNullOrBlank())
        val cloud = YandexArchiveCloud(context, saved!!)
        cloud.requireAccount(saved.uid, saved.appRootId!!)
        exercise(cloud, saved.uid, saved.appRootId!!, "real-yandex")
    }
    private fun exercise(cloud: ArchiveCloud, uid: String, root: String, mode: String) {
        val fixtures = instrument.context.assets.list("photo-corpus")!!.filter { it.endsWith(".jpg") }.sorted()
        assertEquals("Run scripts/prepare-photo-corpus.ps1 before test APK assembly", 10, fixtures.size)
        val scope = File(context.cacheDir, "photo-run-${UUID.randomUUID()}").apply { mkdirs() }
        val jobs = mutableMapOf<String, ArchiveJob>(); var group: ResourceGroup? = null
        val journal = object : ArchiveJournal {
            override fun load(id: String) = jobs.getValue(id)
            override fun save(job: ArchiveJob) { job.validate(); jobs[job.id] = job }
            override fun group(id: String) = group?.takeIf { it.id == id }
        }
        val staging = AndroidArchiveStaging(context, store = journal, cloud = cloud)
        val report = File(context.filesDir, "roundtrip-$mode.csv")
        report.writeText("name,bytes,sha256,mtime_seconds,mtime_nanos,cold_path,gallery_path,result\n")
        try { fixtures.forEachIndexed { index, name ->
            val original = File(scope, name)
            instrument.context.assets.open("photo-corpus/$name").use { input -> original.outputStream().use { input.copyTo(it) } }
            val expectedBytes = original.readBytes()
            val exif = ExifInterface(original.path)
            val fields = listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE)
            val expectedExif = fields.associateWith { exif.getAttribute(it) }
            val expectedTime = FileTime.from(Instant.ofEpochSecond(1600000000L + index * 86400, 123456000))
            Files.setLastModifiedTime(original.toPath(), expectedTime)
            // Android's java.nio attributes may expose seconds only; compare the actual native observations.
            val observedTime = android.system.Os.stat(original.path).st_mtim
            val cold = ArchiveJob(ArchiveStore.randomId(), uid, root, Uri.fromFile(original).toString(), name, "image/jpeg", original.length(), containerExtension = ContainerExtension.BIN)
            jobs[cold.id] = cold; val engine = ColdArchive(journal, staging, cloud)
            repeat(3) { engine.step(cold.id) }; val archived = journal.load(cold.id)
            assertEquals(ArchivePhase.VERIFIED, archived.phase)
            val afterPacking = android.system.Os.stat(original.path).st_mtim
            assertEquals(observedTime.tv_sec, afterPacking.tv_sec); assertEquals(observedTime.tv_nsec, afterPacking.tv_nsec)
            assertNotNull(archived.proof!!.times)
            assertEquals(observedTime.tv_sec, archived.proof!!.times!!.modifiedSeconds)
            assertEquals(observedTime.tv_nsec, archived.proof!!.times!!.modifiedNanos!!.toLong())
            assertTrue(original.delete()); assertFalse(original.exists())
            val galleryId = ArchiveStore.randomId(); val groupId = ArchiveStore.randomId()
            group = ResourceGroup(groupId, listOf(cold.id), true, name, listOf(galleryId))
            val working = ArchiveJob(galleryId, uid, root, cold.source, name, "image/jpeg", archived.proof!!.originalBytes,
                zone = OriginalZone.GALLERY, groupId = groupId, coldId = cold.id, containerExtension = ContainerExtension.BIN)
            jobs[working.id] = working; repeat(3) { engine.step(working.id) }
            val ready = journal.load(working.id); assertEquals(ArchivePhase.VERIFIED, ready.phase)
            assertEquals(archived.proof!!.times, ready.proof!!.times)
            for (copy in listOf(archived, ready)) {
                val downloaded = File(scope, "${copy.id}.bin")
                cloud.readback(copy.path) { input -> downloaded.outputStream().use { input.copyTo(it) } }
                val output = File(scope, "restored-${copy.id}.jpg").apply { createNewFile() }
                val message = PortableFiles.restoreAndReadBack(context, Uri.fromFile(downloaded), Uri.fromFile(output))
                assertTrue(message, message.contains("восстановлена и проверена"))
                assertArrayEquals(expectedBytes, output.readBytes())
                val restoredTime = android.system.Os.stat(output.path).st_mtim
                assertEquals(observedTime.tv_sec, restoredTime.tv_sec); assertEquals(observedTime.tv_nsec, restoredTime.tv_nsec)
                val restoredExif = ExifInterface(output.path); fields.forEach { assertEquals(it, expectedExif[it], restoredExif.getAttribute(it)) }
            }
            report.appendText("$name,${archived.proof!!.originalBytes},${archived.proof!!.originalSha256},${archived.proof!!.times!!.modifiedSeconds},${archived.proof!!.times!!.modifiedNanos},${archived.path},${ready.path},PASS\n")
            println("CIEL_PHOTO_ROUNDTRIP $mode ${index + 1}/10 PASS $name")
        } } finally {
            jobs.keys.forEach { staging.removeOwnedContainer(it) }
            scope.listFiles()?.forEach { it.delete() }; scope.delete()
        }
    }
}
