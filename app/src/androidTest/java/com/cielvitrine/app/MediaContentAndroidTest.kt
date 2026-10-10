package com.cielvitrine.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaContentAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Before fun keepTestWindowAwake() { compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun item(file: File, mime: String, generation: Long = 1) = MediaItem(Uri.fromFile(file), file.name, mime, 0, 0, 1, "Test", file.length(), 128, 96, 0, generation=generation)
    private fun waitForLoad() { compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-loading").fetchSemanticsNodes().isEmpty() } }
    @Test fun aReusedUriWithANewBindingReloadsThePhotoEvenWithUnchangedOtherMetadata() {
        val dir = File(context.cacheDir, "binding-viewer-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, "same.png")
        fun writeColor(color: Int) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        try {
            writeColor(android.graphics.Color.RED)
            val first = item(file, "image/png").copy(binding = "old-row")
            val shown = mutableStateOf(first)
            compose.setContent { MaterialTheme { PhotoContent(shown.value, Modifier.size(128.dp)) } }
            waitForLoad()
            fun pixels() = compose.onNodeWithTag("photo-image").captureToImage().toPixelMap()
            assertTrue(pixels()[32,32].red > .9f)
            writeColor(android.graphics.Color.BLUE)
            compose.runOnUiThread { shown.value = first.copy(binding = "new-row") }
            waitForLoad()
            assertTrue(pixels()[32,32].blue > .9f)
        } finally { dir.deleteRecursively() }
    }
    @Test fun generatedImagesOpenInTheRealViewerAndAnimationsAdvance() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val fixtures = org.json.JSONObject(instrument.context.assets.open("safety-corpus/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("fixtures")
        val scope = File(context.cacheDir,"viewer-formats-${UUID.randomUUID()}").apply { mkdirs() }
        val model = mutableStateOf<MediaItem?>(null)
        compose.setContent { MaterialTheme { model.value?.let { PhotoContent(it,Modifier.size(128.dp,128.dp)) } } }
        try {
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index)
                if (fixture.getString("kind") != "image") continue
                val file = File(scope,fixture.getString("name"))
                instrument.context.assets.open("safety-corpus/${file.name}").use { input -> file.outputStream().use { input.copyTo(it) } }
                compose.runOnIdle { model.value = item(file,fixture.getString("mime")) }
                waitForLoad(); compose.onNodeWithTag("media-error").assertDoesNotExist()
                if (fixture.optBoolean("animated")) {
                    var red = false; var green = false
                    compose.waitUntil(10_000) {
                        val pixels = compose.onNodeWithTag("photo-image").captureToImage().toPixelMap()
                        // ContentScale.Fit places 128x96 content within this square.
                        val pixel = pixels[pixels.width/8,pixels.height/3]
                        red = red || (pixel.red > .8f && pixel.green < .2f)
                        green = green || (pixel.green > .8f && pixel.red < .2f)
                        red && green
                    }
                }
            }
        } finally { scope.listFiles()?.forEach { it.delete() }; scope.delete() }
    }
    @Test fun generatedVideosPlayToTheEndInTheRealViewer() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val scope = File(context.cacheDir,"viewer-videos-${UUID.randomUUID()}").apply { mkdirs() }
        val model = mutableStateOf<MediaItem?>(null)
        compose.setContent { MaterialTheme { model.value?.let { VideoContent(it,Modifier.fillMaxSize()) } } }
        fun findPlayer(view: android.view.View): androidx.media3.ui.PlayerView? {
            if (view is androidx.media3.ui.PlayerView) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
            return null
        }
        try {
            for (name in listOf("h264.mp4","hevc.mp4","av1.mp4")) {
                val file = File(scope,name)
                instrument.context.assets.open("safety-corpus/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                compose.runOnIdle { model.value = item(file,"video/mp4") }
                waitForLoad(); compose.onNodeWithTag("media-error").assertDoesNotExist()
                lateinit var player: androidx.media3.common.Player
                compose.runOnUiThread { player = findPlayer(compose.activity.window.decorView)!!.player!!; player.play() }
                compose.waitUntil(15_000) {
                    var ended = false
                    compose.runOnUiThread { assertNull(name,player.playerError); ended = player.playbackState == androidx.media3.common.Player.STATE_ENDED }
                    ended
                }
                compose.runOnUiThread { assertTrue(name,player.currentPosition >= 900); assertEquals(160,player.videoSize.width); assertEquals(96,player.videoSize.height) }
            }
        } finally { scope.listFiles()?.forEach { it.delete() }; scope.delete() }
    }
    @Test fun imageErrorIsVisibleRetryCanOpenChangedFileWithoutStaleCache() {
        val file = File(context.cacheDir,"viewer-fixture-${UUID.randomUUID()}.png").apply { writeText("Not an image") }
        try {
            compose.setContent { MaterialTheme { PhotoContent(item(file,"image/png"),Modifier.fillMaxSize()) } }
            waitForLoad(); compose.onNodeWithTag("media-error").assertIsDisplayed()
            val bitmap = Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            compose.onNodeWithText("Повторить").performClick(); waitForLoad()
            compose.onNodeWithTag("media-error").assertDoesNotExist()
            val pixels=compose.onNodeWithTag("photo-image").captureToImage().toPixelMap()
            val center=pixels[pixels.width/2,pixels.height/2]; assertTrue(center.blue > .9f && center.red < .1f)
            assertTrue(file.length() > 100)
        } finally { file.delete() }
    }
    @Test fun rotatedImageIsRenderedWithTheExpectedCornerAndVideoFailureIsExplicit() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val file=File(context.cacheDir,"orientation-${UUID.randomUUID()}.jpg")
        try {
            instrument.context.assets.open("safety-corpus/orientation-6.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
            compose.setContent { MaterialTheme { PhotoContent(item(file,"image/jpeg"),Modifier.size(96.dp,128.dp)) } }
            waitForLoad(); compose.onNodeWithTag("media-error").assertDoesNotExist()
            val pixels=compose.onNodeWithTag("photo-image").captureToImage().toPixelMap()
            val corner=pixels[pixels.width/8,pixels.height/8]; assertTrue("Orientation 6 must put blue at top left",corner.blue > .8f && corner.red < .2f)
        } finally { file.delete() }
    }
    @Test fun corruptVideoDoesNotPresentAnEmptySuccessfulViewer() {
        val file=File(context.cacheDir,"video-failure-${UUID.randomUUID()}.mp4").apply { writeText("Invalid MP4") }
        try {
            val original=file.readBytes()
            compose.setContent { MaterialTheme { VideoContent(item(file,"video/mp4"),Modifier.fillMaxSize()) } }
            waitForLoad(); compose.onNodeWithTag("media-error").assertIsDisplayed()
            compose.onNodeWithText("Повторить").assertIsDisplayed()
            assertArrayEquals(original,file.readBytes())
        } finally { file.delete() }
    }
    @Test fun editedThumbnailWithTheSameUriIsReloadedWhenItsGenerationChanges() {
        val resolver=context.contentResolver
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"ciel-thumb-${UUID.randomUUID()}.jpg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/CielVitrineTests")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=resolver.insert(android.provider.MediaStore.Images.Media.getContentUri("external_primary"),values)!!
        fun write(color:Int) {
            val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            resolver.openOutputStream(uri,"wt")!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) }; bitmap.recycle()
        }
        try {
            write(android.graphics.Color.RED)
            resolver.update(uri,android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING,0) },null,null)
            val model=mutableStateOf(MediaItem(uri,"fixture.jpg","image/jpeg",0,0,1,"Test",1,32,32,0,generation=1))
            compose.setContent { MaterialTheme { MediaThumb(model.value,Modifier.size(128.dp).testTag("fixture-thumb")) } }
            fun center()=compose.onNodeWithTag("fixture-thumb").captureToImage().toPixelMap().let { it[it.width/2,it.height/2] }
            compose.waitUntil(15_000) { center().red > .9f && center().blue < .1f }
            write(android.graphics.Color.BLUE)
            resolver.update(uri,android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DATE_MODIFIED,System.currentTimeMillis()/1000+1) },null,null)
            compose.runOnIdle { model.value=model.value.copy(generation=2) }
            compose.waitUntil(15_000) { center().blue > .9f && center().red < .1f }
        } finally { resolver.delete(uri,null,null) }
    }
}
