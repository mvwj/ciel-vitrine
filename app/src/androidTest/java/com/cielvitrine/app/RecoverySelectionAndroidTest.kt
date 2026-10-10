package com.cielvitrine.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.pos.Pos2
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only private generated fixtures; never changes account, grants or gallery preferences. */
@RunWith(AndroidJUnit4::class)
class RecoverySelectionAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun scope() = File(context.cacheDir, "recovery-selection-${UUID.randomUUID()}").apply { mkdirs() }

    @Test fun authenticatedNamesAndTypesReachTheSaveContractAndExactRestoration() {
        val dir = scope()
        try {
            for ((name, mime) in listOf("Фото 🏞.jpg" to "image/jpeg", "Original.heic" to "image/heic", "animation.gif" to "image/gif", "video.mp4" to "video/mp4")) {
                val original = ByteArray(65537) { (it * 19).toByte() }
                val archive = File(dir, "archive.bin")
                archive.outputStream().use { Pos2.write(original.inputStream(), it, name, mime) }
                val verified = PortableFiles.inspectRecovery(context.contentResolver, Uri.fromFile(archive))
                val contract = OriginalDocumentContract()
                val intent = contract.createIntent(context, OriginalDocument(verified.name, verified.mime))
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertEquals(name, intent.getStringExtra(Intent.EXTRA_TITLE))
                assertEquals(mime, intent.type)
                val output = File(dir, name).apply { createNewFile() }
                val destination = contract.parseResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(output)))!!
                assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(destination)))
                PortableFiles.restoreAndReadBack(context, Uri.fromFile(archive), destination, verified.containerSha256)
                assertArrayEquals(original, output.readBytes())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun aDifferentValidContainerAfterSelectionCannotTouchTheDestination() {
        val dir = scope()
        try {
            val archive = File(dir, "archive.bin")
            archive.outputStream().use { Pos2.write(byteArrayOf(1, 2).inputStream(), it, "first.jpg", "image/jpeg") }
            val verified = PortableFiles.inspectRecovery(context.contentResolver, Uri.fromFile(archive))
            archive.outputStream().use { Pos2.write(byteArrayOf(3, 4).inputStream(), it, "second.mp4", "video/mp4") }
            val changed = archive.readBytes()
            val destination = File(dir, "first.jpg").apply { createNewFile() }
            try {
                PortableFiles.restoreAndReadBack(context, Uri.fromFile(archive), Uri.fromFile(destination), verified.containerSha256)
                fail("Changed container accepted")
            } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Архив изменился")) }
            assertTrue(destination.exists())
            assertEquals(0, destination.length())
            assertArrayEquals(changed, archive.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun incompleteContainersCannotSupplyPickerMetadata() {
        val dir = scope()
        try {
            val archive = File(dir, "archive.bin")
            archive.outputStream().use { Pos2.write(byteArrayOf(1).inputStream(), it, "photo.jpg", "image/jpeg") }
            val bad = archive.readBytes().dropLast(1).toByteArray(); archive.writeBytes(bad)
            try { PortableFiles.inspectRecovery(context.contentResolver, Uri.fromFile(archive)); fail("Unverified metadata accepted") }
            catch (_: IllegalStateException) { }
            assertArrayEquals(bad, archive.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun unsafeNamesAndInvalidMimeAreRejectedWithoutChangingStoredMetadata() {
        for (name in listOf("../photo.jpg", "folder\\photo.jpg", ".", "..", "\u0000photo.jpg")) {
            try { OriginalDocumentContract().createIntent(context, OriginalDocument(name, "image/jpeg")); fail("Unsafe name accepted") }
            catch (_: IllegalArgumentException) { }
        }
        try { OriginalDocumentContract().createIntent(context, OriginalDocument("photo.jpg", "image/*")); fail("Wildcard accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
