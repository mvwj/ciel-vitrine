package com.cielvitrine.app

import android.app.Application
import android.content.ContentValues
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaBindingAndroidTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private fun item(uri: Uri, key: String?) = MediaItem(uri, "owned.jpg", "image/jpeg", 0, 0, 1, "Test", 1, 1, 1, 0, binding = key)

    @Test fun uriReuseEpochChangesAndLegacyLinksDoNotMatchTheWrongPhoto() {
        val uri = Uri.parse("content://media/external_primary/file/42")
        val key = mediaBinding(uri.toString(), "epoch-a", 100)!!
        val members = setOf(key)
        assertTrue(members.containsMedia(item(uri, key)))
        assertFalse(members.containsMedia(item(uri, mediaBinding(uri.toString(), "epoch-b", 100))))
        assertFalse(members.containsMedia(item(uri, mediaBinding(uri.toString(), "epoch-a", 101))))
        assertFalse(setOf(uri.toString()).containsMedia(item(uri, key)))
        assertNull(mediaBinding(uri.toString(), null, 100))
        assertNull(mediaBinding(uri.toString(), "epoch-a", null))
        assertEquals("0 доступны · 1 требуют проверки или доступа", collectionSummary(members, emptyList()))
        assertTrue(members.containsMedia(item(uri, key))) // Access loss never removed the link.
    }

    @Test fun actualMediaIdentitySurvivesEditsAndExplicitMigrationPreservesOtherLegacyLinks() {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri("external_primary"), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ciel-binding-${UUID.randomUUID()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CielVitrineTests")
        })!!
        val suffix = UUID.randomUUID().toString()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences("$name-$suffix", mode)
        }
        val prefs = app.getSharedPreferences("local_gallery", 0)
        val missing = "content://media/external_primary/file/9223372036854775807"
        val legacy = "[{\"name\":\"Album\",\"members\":[\"$uri\",\"$missing\"]}]"
        try {
            resolver.openOutputStream(uri, "wt")!!.use { it.write(byteArrayOf(1)) }
            val identity = currentMediaBinding(context, uri)!!
            resolver.openOutputStream(uri, "wt")!!.use { it.write(byteArrayOf(2, 3)) }
            assertEquals(identity, currentMediaBinding(context, uri))
            prefs.edit().putStringSet("favorites", setOf(uri.toString(), missing)).putString("albums", legacy).commit()
            lateinit var model: GalleryModel
            instrument.runOnMainSync { model = GalleryModel(app) }
            assertFalse(model.state.value.favorites.containsMedia(item(uri, identity)))
            lateinit var job: Job
            instrument.runOnMainSync { job = model.favorite(item(uri, identity)) }; runBlocking { job.join() }
            assertEquals(setOf(identity, missing), prefs.getStringSet("favorites", emptySet()))
            assertEquals(setOf(uri.toString(), missing), prefs.getStringSet("favorites_before_binding_v2", emptySet()))
            instrument.runOnMainSync { job = model.addToAlbum("Album", item(uri, identity)) }; runBlocking { job.join() }
            assertEquals(setOf(identity, missing), model.state.value.albums.single().members)
            assertEquals(legacy, prefs.getString("albums_before_binding_v2", null))
        } finally { resolver.delete(uri, null, null); context.deleteSharedPreferences("local_gallery-$suffix") }
    }

    @Test fun damagedAlbumDataCannotBeSilentlyOverwritten() {
        val suffix = UUID.randomUUID().toString()
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences("$name-$suffix", mode)
        }
        val prefs = app.getSharedPreferences("local_gallery", 0)
        try {
            prefs.edit().putString("albums", "incomplete-json[").commit()
            lateinit var model: GalleryModel; lateinit var job: Job
            instrument.runOnMainSync { model = GalleryModel(app); job = model.addToAlbum("New", null) }
            runBlocking { job.join() }
            assertEquals("incomplete-json[", prefs.getString("albums", null))
            assertTrue(model.state.value.albums.isEmpty())
            assertNotNull(model.state.value.message)
        } finally { context.deleteSharedPreferences("local_gallery-$suffix") }
    }
}
