package com.cielvitrine.app

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.pos.Pos2
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID

/** Safe on a connected phone: private generated fixtures only, no grants/session/catalog changes. */
@RunWith(AndroidJUnit4::class)
class MediaSafetyAndroidTest {
    @get:org.junit.Rule val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @org.junit.Before fun keepTestWindowAwake() {
        activity.scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun appearance(file: File, fixture: JSONObject): String? {
        if (fixture.getString("kind") == "archive-only") return null
        val start = android.os.SystemClock.elapsedRealtimeNanos()
        val bitmap = if (fixture.getString("kind") == "video") {
            MediaMetadataRetriever().use { it.setDataSource(file.path); it.getFrameAtTime(0) ?: error("No video frame") }
        } else {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        }
        try {
            assertEquals(fixture.getInt("width"), bitmap.width); assertEquals(fixture.getInt("height"), bitmap.height)
            if (fixture.optBoolean("animated")) assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) is AnimatedImageDrawable)
            if (fixture.optBoolean("alpha")) assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
            if (fixture.has("orientation")) {
                val pixel = bitmap.getPixel(8, 8)
                val expected = when (fixture.getInt("orientation")) { 6 -> android.graphics.Color.BLUE; 8 -> android.graphics.Color.GREEN; else -> android.graphics.Color.RED }
                assertTrue(kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(expected)) < 25)
                assertTrue(kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(expected)) < 25)
                assertTrue(kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(expected)) < 25)
            }
            val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val digest = MessageDigest.getInstance("SHA-256")
            for (pixel in pixels) digest.update(java.nio.ByteBuffer.allocate(4).putInt(pixel).array())
            fixture.put("last_decode_ms", (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1e6)
            fixture.put("decoded_color_space", bitmap.colorSpace?.name ?: "unknown")
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally { bitmap.recycle() }
    }
    @Test fun generatedModernFormatsRecoverExactBytesDatesAndDecodedAppearance() {
        val manifest = JSONObject(instrument.context.assets.open("safety-corpus/manifest.json").bufferedReader().use { it.readText() })
        val fixtures = manifest.getJSONArray("fixtures"); assertEquals(14, fixtures.length())
        val scope = File(context.cacheDir, "safety-run-${UUID.randomUUID()}").apply { mkdirs() }
        val results = JSONArray()
        try {
            for (index in 0 until fixtures.length()) {
                val f = fixtures.getJSONObject(index); val name = f.getString("name")
                val source = File(scope, name)
                instrument.context.assets.open("safety-corpus/$name").use { input -> source.outputStream().use { input.copyTo(it) } }
                val original = source.readBytes(); assertEquals(f.getString("sha256"), hash(original))
                val before = appearance(source, f)
                Files.setLastModifiedTime(source.toPath(), FileTime.from(Instant.ofEpochSecond(1500000000L + index, 987654000)))
                val sourceTime = Os.stat(source.path).st_mtim
                val container = File(scope, "$name.bin").apply { createNewFile() }
                PortableFiles.packAndReadBack(context, Uri.fromFile(source), Uri.fromFile(container))
                val proof = container.inputStream().use { Pos2.read(it, com.cielvitrine.archive.ColdArchive.Sink, original.size.toLong(), hash(original)) }
                assertEquals(name, proof.name); assertEquals(f.getString("mime"),proof.mime)
                assertEquals(sourceTime.tv_sec, proof.times!!.modifiedSeconds)
                assertEquals(sourceTime.tv_nsec, proof.times!!.modifiedNanos!!.toLong())
                assertArrayEquals(original, source.readBytes()); assertTrue(source.delete())
                val restored = File(scope, "restored-$name").apply { createNewFile() }
                val message = PortableFiles.restoreAndReadBack(context, Uri.fromFile(container), Uri.fromFile(restored))
                assertTrue(message, message.contains("восстановлена и проверена"))
                assertArrayEquals(original, restored.readBytes()); val restoredTime = Os.stat(restored.path).st_mtim
                assertEquals(sourceTime.tv_sec, restoredTime.tv_sec); assertEquals(sourceTime.tv_nsec, restoredTime.tv_nsec)
                assertEquals(before, appearance(restored, f))
                f.put("result", "PASS"); f.put("pixel_sha256", before ?: JSONObject.NULL); results.put(f)
                instrument.sendStatus(2, android.os.Bundle().apply { putString("ciel_safety", "$name:PASS") })
            }
            File(context.filesDir, "media-safety-results.json").writeText(JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT).put("model", android.os.Build.MODEL).put("fixtures", results).toString(2))
        } finally { scope.listFiles()?.forEach { it.delete() }; scope.delete() }
    }
    @Test fun badContainerDoesNotTouchAnExistingDestinationAndAliasesCannotTruncateSource() {
        val scope = File(context.cacheDir, "restore-failure-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = File(scope,"original.jpg").apply { writeBytes(ByteArray(65537) { it.toByte() }) }
            val container = File(scope,"container.bin").apply { createNewFile() }
            PortableFiles.packAndReadBack(context, Uri.fromFile(original), Uri.fromFile(container))
            val broken = container.readBytes().dropLast(1).toByteArray(); container.writeBytes(broken)
            val sentinel = "Existing destination must survive invalid input".toByteArray()
            val destination = File(scope,"destination.jpg").apply { writeBytes(sentinel) }
            try { PortableFiles.restoreAndReadBack(context, Uri.fromFile(container), Uri.fromFile(destination)); fail("Incomplete archive accepted") }
            catch (_: Exception) {}
            assertArrayEquals(sentinel, destination.readBytes()); assertArrayEquals(broken, container.readBytes())
            // Android SELinux denies creating hard links. A second path to the same inode
            // still exercises FD identity protection without needing forbidden filesystem operations.
            val alias = File(scope,"./original.jpg")
            assertNotEquals(Uri.fromFile(original),Uri.fromFile(alias))
            val snapshot = original.readBytes()
            try { PortableFiles.packAndReadBack(context, Uri.fromFile(original), Uri.fromFile(alias)); fail("File alias accepted") }
            catch (_: IllegalArgumentException) {}
            assertArrayEquals(snapshot, original.readBytes()); assertArrayEquals(snapshot, alias.readBytes())
        } finally { scope.listFiles()?.forEach { it.delete() }; scope.delete() }
    }
    @Test fun validExportAndRestoreCannotOverwriteAnExistingFile() {
        val scope = File(context.cacheDir,"existing-destination-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = File(scope,"original.jpg").apply { writeBytes(ByteArray(128) { it.toByte() }) }
            val sentinel = "Existing user file".toByteArray()
            val destination = File(scope,"existing.jpg").apply { writeBytes(sentinel) }
            try { PortableFiles.packAndReadBack(context,Uri.fromFile(original),Uri.fromFile(destination)); fail("Existing file overwritten by export") }
            catch (_: IllegalStateException) {}
            assertArrayEquals(sentinel,destination.readBytes())
            val container = File(scope,"container.bin").apply { createNewFile() }
            PortableFiles.packAndReadBack(context,Uri.fromFile(original),Uri.fromFile(container))
            try { PortableFiles.restoreAndReadBack(context,Uri.fromFile(container),Uri.fromFile(destination)); fail("Existing file overwritten by restoration") }
            catch (_: IllegalStateException) {}
            assertArrayEquals(sentinel,destination.readBytes()); assertEquals(128,original.length().toInt())
        } finally { scope.listFiles()?.forEach { it.delete() }; scope.delete() }
    }
}
