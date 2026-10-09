package com.cielvitrine.app

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MediaItem(
    val uri: Uri, val name: String, val mime: String, val date: Long,
    val addedSeconds: Long, val id: Long, val folder: String,
    val bytes: Long, val width: Int, val height: Int, val duration: Long,
) { val isVideo get() = mime.startsWith("video/") }

enum class MediaAccess { NONE, SELECTED, FULL, MIXED }
data class LocalAlbum(val name: String, val members: Set<String>)
data class CloudChoice(val provider: String, val archiveFirst: Boolean = true)
data class GalleryState(
    val media: List<MediaItem> = emptyList(), val loading: Boolean = false,
    val hasMore: Boolean = false, val access: MediaAccess = MediaAccess.NONE,
    val favorites: Set<String> = emptySet(), val albums: List<LocalAlbum> = emptyList(),
    val cloud: CloudChoice? = null, val message: String? = null,
    val fileOperationRunning: Boolean = false,
)

class GalleryModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("local_gallery", 0)
    private val mutable = MutableStateFlow(GalleryState(
        favorites = prefs.getStringSet("favorites", emptySet())!!.toSet(),
        albums = readAlbums(),
        cloud = prefs.getString("cloud_provider", null)?.let { CloudChoice(it, true) },
    ))
    val state = mutable.asStateFlow()
    private var scanJob: kotlinx.coroutines.Job? = null
    private val preferenceWrites = Mutex()

    fun access(): MediaAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(getApplication(), p) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT < 33) return if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.FULL else MediaAccess.NONE
        val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
        val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
        return when {
            images && videos -> MediaAccess.FULL
            images || videos -> MediaAccess.MIXED
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.SELECTED
            else -> MediaAccess.NONE
        }
    }

    fun refresh() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, access = access(), media = emptyList(), hasMore = false)
            loadPage(null)
        }
    }

    fun more() {
        if (mutable.value.loading || !mutable.value.hasMore) return
        scanJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true)
            loadPage(mutable.value.media.lastOrNull())
        }
    }

    private suspend fun loadPage(after: MediaItem?) {
        try {
            val page = withContext(Dispatchers.IO) { queryPage(after) }
            mutable.value = mutable.value.copy(media = mutable.value.media + page, loading = false, hasMore = page.size == PAGE_SIZE)
        } catch (e: CancellationException) { throw e
        } catch (e: SecurityException) {
            mutable.value = mutable.value.copy(loading = false, access = access(), message = "Доступ к фото изменился. Выберите доступные файлы ещё раз.")
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(loading = false, message = "Не удалось прочитать медиатеку: ${e.localizedMessage}")
        }
    }

    private fun queryPage(after: MediaItem?): List<MediaItem> {
        if (access() == MediaAccess.NONE) return emptyList()
        val columns = arrayOf("_id", "volume_name", "_display_name", "mime_type", "date_added", "bucket_display_name", "_size", "width", "height", "duration")
        val selection = "media_type IN (1,3)" + if (after != null) " AND (date_added < ? OR (date_added = ? AND _id < ?))" else ""
        val query = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (after != null) putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(after.addedSeconds.toString(), after.addedSeconds.toString(), after.id.toString()))
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_added DESC, _id DESC")
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, PAGE_SIZE)
        }
        val items = mutableListOf<MediaItem>()
        getApplication<Application>().contentResolver.query(MediaStore.Files.getContentUri("external"), columns, query, null)?.use { c ->
            while (c.moveToNext() && items.size < PAGE_SIZE) {
                val id = c.getLong(0)
                val added = c.getLong(4)
                items += MediaItem(ContentUris.withAppendedId(MediaStore.Files.getContentUri(c.getString(1)), id),
                    c.getString(2) ?: "Без имени", c.getString(3) ?: "application/octet-stream", added * 1000,
                    added, id, c.getString(5) ?: "Другие", c.getLong(6), c.getInt(7), c.getInt(8), c.getLong(9))
            }
        }
        return items
    }

    fun favorite(item: MediaItem) = viewModelScope.launch { preferenceWrites.withLock {
        val id = item.uri.toString()
        val updated = mutable.value.favorites.toMutableSet().apply { if (!add(id)) remove(id) }.toSet()
        val saved = withContext(Dispatchers.IO) { prefs.edit().putStringSet("favorites", updated).commit() }
        if (saved) mutable.value = mutable.value.copy(favorites = updated)
        else message("Не удалось сохранить избранное")
    } }

    fun addToAlbum(name: String, item: MediaItem?) = viewModelScope.launch { preferenceWrites.withLock {
        val title = name.trim().take(80)
        if (title.isEmpty()) return@withLock
        val old = mutable.value.albums.firstOrNull { it.name == title }
        val updated = mutable.value.albums.filterNot { it.name == title } + LocalAlbum(title, (old?.members ?: emptySet()) + listOfNotNull(item?.uri?.toString()))
        val payload = JSONArray().apply { updated.forEach { put(JSONObject().put("name", it.name).put("members", JSONArray(it.members.toList()))) } }.toString()
        val saved = withContext(Dispatchers.IO) { prefs.edit().putString("albums", payload).commit() }
        if (saved) mutable.value = mutable.value.copy(albums = updated) else message("Не удалось сохранить альбом")
    } }

    fun chooseCloud(provider: String) = viewModelScope.launch { preferenceWrites.withLock {
        val saved = withContext(Dispatchers.IO) { prefs.edit().putString("cloud_provider", provider).commit() }
        if (saved) mutable.value = mutable.value.copy(cloud = CloudChoice(provider))
    } }

    fun message(text: String?) { mutable.value = mutable.value.copy(message = text) }
    fun portableFile(source: Uri, destination: Uri, restore: Boolean) {
        if (mutable.value.fileOperationRunning) return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(fileOperationRunning = true)
            try {
                val result = withContext(Dispatchers.IO) {
                    if (restore) PortableFiles.restoreAndReadBack(getApplication<Application>(), source, destination)
                    else PortableFiles.packAndReadBack(getApplication<Application>(), source, destination)
                }
                message(result)
            } catch (e: Exception) { message("Операция не подтверждена: ${e.localizedMessage}") }
            finally { mutable.value = mutable.value.copy(fileOperationRunning = false) }
        }
    }
    private fun readAlbums(): List<LocalAlbum> = runCatching {
        val a = JSONArray(prefs.getString("albums", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            val members = o.getJSONArray("members")
            LocalAlbum(o.getString("name"), (0 until members.length()).map { members.getString(it) }.toSet())
        } }
    }.getOrDefault(emptyList())

    companion object { const val PAGE_SIZE = 240 }
}
