package com.cielvitrine.app

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Android-provider behavior, including actual destination reread and alias protection. */
@RunWith(AndroidJUnit4::class)
class PortableFilesAndroidTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val owned = mutableListOf<Uri>()
    private fun document(bytes: ByteArray = byteArrayOf()): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ciel-test-${UUID.randomUUID()}.bin")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CielVitrineTests")
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri("external_primary"), values)!!
        owned += uri
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        return uri
    }
    private fun bytes(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
    @After fun cleanOnlyOwnedFixtures() { owned.forEach { resolver.delete(it, null, null) } }

    @Test fun exactRoundTripThroughAndroidProviderWithoutKey() {
        val original = ByteArray(131079) { (it * 31).toByte() }
        val source = document(original)
        val container = document()
        val restored = document()
        assertTrue(PortableFiles.packAndReadBack(resolver, source, container).contains("SHA-256"))
        assertTrue(PortableFiles.restoreAndReadBack(resolver, container, restored).contains("SHA-256"))
        assertArrayEquals(original, bytes(source))
        assertArrayEquals(original, bytes(restored))
    }
    @Test fun aliasesCannotTruncateTheSource() {
        val original = "Source must survive an alias".toByteArray()
        val source = document(original)
        val alias = ContentUris.withAppendedId(MediaStore.Downloads.getContentUri("external"), ContentUris.parseId(source))
        try { PortableFiles.packAndReadBack(resolver, source, alias); fail("Alias accepted") }
        catch (_: IllegalArgumentException) {}
        assertArrayEquals(original, bytes(source))
    }
    @Test fun corruptFooterCannotProduceSuccessAndSourceSurvives() {
        val source = document("Exact sample".toByteArray())
        val container = document()
        PortableFiles.packAndReadBack(resolver, source, container)
        val corrupt = bytes(container).dropLast(1).toByteArray()
        resolver.openOutputStream(container, "wt")!!.use { it.write(corrupt) }
        val destination = document()
        try { PortableFiles.restoreAndReadBack(resolver, container, destination); fail("Truncated footer accepted") }
        catch (_: IllegalStateException) {}
        assertArrayEquals("Exact sample".toByteArray(), bytes(source))
    }
    @Test fun archiveStagingCommitsAndRereadsBeforeUploadAndKeepsSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceBytes = ByteArray(65537) { (it * 43).toByte() }
        val source = document(sourceBytes)
        val id = UUID.randomUUID().toString().replace("-", "")
        val staging = AndroidArchiveStaging(context)
        val job = com.cielvitrine.archive.ArchiveJob(id, "123", "test-root", source.toString(), "test.heic", "image/heic", sourceBytes.size.toLong())
        try {
            val proof = staging.prepare(job)
            val result = staging.open(id).use { com.cielvitrine.pos.Pos2.read(it, com.cielvitrine.archive.ColdArchive.Sink, proof.originalBytes, proof.originalSha256) }
            assertEquals(proof, com.cielvitrine.archive.ArchiveProof.from(result))
            assertArrayEquals(sourceBytes, bytes(source))
            try { staging.prepare(job.copy(sourceBytes = sourceBytes.size.toLong() + 1)); fail("Changed source length accepted") }
            catch (_: IllegalArgumentException) {}
            assertArrayEquals(sourceBytes, bytes(source))
        } finally { staging.removeOwnedContainer(id) }
    }
}
