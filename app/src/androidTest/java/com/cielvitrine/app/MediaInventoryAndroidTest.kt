package com.cielvitrine.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaInventoryAndroidTest {
    @Test fun snapshotQueuesVisibleOriginalsOfflineExcludesLaterFilesAndPreservesExifBytes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val resolver = context.contentResolver; val created = mutableListOf<Uri>(); val id = ArchiveStore.randomId()
        val requested = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        val newPermissions = requested.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val jpeg = File(context.cacheDir, "inventory-test-${UUID.randomUUID()}.jpg")
        try {
            newPermissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
            ExifInterface(jpeg.path).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "47/1,29/1,52/1"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "19/1,2/1,24/1"); setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                setAttribute(ExifInterface.TAG_MAKE, "Ciel test fixture"); saveAttributes()
            }
            val bytes = jpeg.readBytes()
            fun insert(): Uri {
                val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "ciel-inventory-${UUID.randomUUID()}.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!; created += uri
                resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri
            }
            val first = insert(); val inventory = MediaInventory(context); var batch = inventory.snapshot(); val later = insert()
            val jobs = mutableListOf<ArchiveJob>()
            repeat(100) { if (batch.phase == ScanPhase.DISCOVERING) { val result = inventory.page(batch); batch = result.first; jobs += result.second } }
            assertEquals(ScanPhase.COMPLETE, batch.phase)
            val sourceId = android.content.ContentUris.parseId(first)
            val original = jobs.single { it.sourceStamp!!.volume == MediaStore.VOLUME_EXTERNAL_PRIMARY && it.sourceStamp!!.mediaId == sourceId }.copy(id = id)
            assertNull(original.uid); assertNull(original.rootId)
            assertFalse(jobs.any { it.sourceStamp!!.mediaId == android.content.ContentUris.parseId(later) && it.sourceStamp!!.volume == MediaStore.VOLUME_EXTERNAL_PRIMARY })
            inventory.openOriginal(original).use { assertArrayEquals(bytes, it.readBytes()) }
            val container = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "ciel-export-${UUID.randomUUID()}.cv2")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CielVitrineTests")
            })!!; created += container
            // Materialize the empty destination, as the document picker does before export.
            resolver.openOutputStream(container, "w")!!.use { }
            PortableFiles.packAndReadBack(context, first, container)
            val exported = ByteArrayOutputStream(); resolver.openInputStream(container)!!.use { Pos2.read(it, exported) }
            assertArrayEquals(bytes, exported.toByteArray())
            val staging = AndroidArchiveStaging(context); val proof = staging.prepare(original)
            val restored = ByteArrayOutputStream(); staging.open(id).use { Pos2.read(it, restored, proof.originalBytes, proof.originalSha256) }
            assertArrayEquals(bytes, restored.toByteArray())
            assertArrayEquals(bytes, inventory.openOriginal(original).use { it.readBytes() })
            val changed = original.copy(sourceStamp = original.sourceStamp!!.copy(bytes = bytes.size.toLong() + 1))
            try { inventory.requireUnchanged(changed); fail("Changed passport accepted") } catch (_: IllegalArgumentException) {}
        } finally {
            AndroidArchiveStaging(context).removeOwnedContainer(id)
            created.forEach { resolver.delete(it, null, null) }; jpeg.delete()
            // Revoking a runtime grant kills the instrumentation process. Grants belong to the isolated test device.
        }
    }
}
