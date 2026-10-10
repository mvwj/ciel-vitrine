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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

data class MediaItem(
    val uri: Uri, val name: String, val mime: String, val date: Long,
    val addedSeconds: Long, val id: Long, val folder: String,
    val bytes: Long, val width: Int, val height: Int, val duration: Long,
    val modifiedSeconds: Long = 0, val generation: Long? = null,
    val binding: String? = null,
) { val isVideo get() = mime.startsWith("video/") }

enum class MediaAccess { NONE, SELECTED, FULL, MIXED }
data class LocalAlbum(val name: String, val members: Set<String>)
data class CloudChoice(val provider: String, val archiveFirst: Boolean = true)
data class GalleryState(
    val media: List<MediaItem> = emptyList(), val loading: Boolean = false,
    val access: MediaAccess = MediaAccess.NONE,
    val favorites: Set<String> = emptySet(), val albums: List<LocalAlbum> = emptyList(),
    val cloud: CloudChoice? = null, val message: String? = null,
    val fileOperationRunning: Boolean = false,
)

class GalleryModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("local_gallery", 0)
    private val initialAlbums = readAlbums()
    private val mutable = MutableStateFlow(GalleryState(
        favorites = prefs.getStringSet("favorites", emptySet())!!.toSet(),
        albums = initialAlbums.getOrDefault(emptyList()),
        message = if (initialAlbums.isFailure) "Не удалось прочитать альбомы. Сохранённые записи оставлены без изменений." else null,
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
            val granted = access()
            mutable.value = mutable.value.copy(loading = true, access = granted,
                media = if (granted != mutable.value.access || granted == MediaAccess.NONE) emptyList() else mutable.value.media)
            loadLibrary()
        }
    }

    private suspend fun loadLibrary() {
        try {
            val library = withContext(Dispatchers.IO) {
                val taskContext = currentCoroutineContext()
                queryLibrary { taskContext.ensureActive() }
            }
            mutable.value = mutable.value.copy(media = library, loading = false, access = access())
        } catch (e: CancellationException) { throw e
        } catch (e: SecurityException) {
            mutable.value = mutable.value.copy(media = emptyList(), loading = false, access = access(), message = "Доступ к фото изменился. Выберите доступные файлы ещё раз.")
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(loading = false, message = "Не удалось прочитать медиатеку: ${e.localizedMessage}")
        }
    }

    /** Metadata only: the whole date index is needed for direct scrolling to an old day.
     * Media bytes and thumbnails are loaded only for visible cells. */
    internal fun queryLibrary(checkCancelled: () -> Unit = {}): List<MediaItem> {
        if (access() == MediaAccess.NONE) return emptyList()
        val columns = arrayOf("_id", "volume_name", "_display_name", "mime_type", "date_added", "bucket_display_name", "_size", "width", "height", "duration", "datetaken", "date_modified") +
            if (Build.VERSION.SDK_INT >= 30) arrayOf("generation_modified", "generation_added") else emptyArray()
        val selection = "media_type IN (1,3) AND is_pending=0" + if (Build.VERSION.SDK_INT >= 30) " AND is_trashed=0" else ""
        val query = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_added DESC, _id DESC")
        }
        val items = mutableListOf<MediaItem>()
        val app = getApplication<Application>()
        val epochs = if (Build.VERSION.SDK_INT >= 30) MediaStore.getExternalVolumeNames(app).associateWith { volume ->
            runCatching { MediaStore.getVersion(app, volume) }.getOrNull()
        } else emptyMap()
        val strings = mutableMapOf<String, String>()
        fun shared(value: String): String = strings.getOrPut(value) { value }
        getApplication<Application>().contentResolver.query(MediaStore.Files.getContentUri("external"), columns, query, null)?.use { c ->
            while (c.moveToNext()) {
                checkCancelled()
                val id = c.getLong(0)
                val added = c.getLong(4)
                val volume = shared(c.getString(1))
                val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(volume), id)
                items += MediaItem(uri,
                    c.getString(2) ?: "Без имени", shared(c.getString(3) ?: "application/octet-stream"),
                    galleryDate(if (c.isNull(10)) null else c.getLong(10), if (c.isNull(11)) null else c.getLong(11), added),
                    added, id, shared(c.getString(5) ?: "Другие"), c.getLong(6), c.getInt(7), c.getInt(8), c.getLong(9),
                    c.getLong(11), if (Build.VERSION.SDK_INT >= 30 && !c.isNull(12)) c.getLong(12) else null,
                    mediaBinding(uri.toString(), epochs[volume], if (Build.VERSION.SDK_INT >= 30 && !c.isNull(13)) c.getLong(13) else null))
            }
        } ?: error("Медиатека недоступна")
        if (Build.VERSION.SDK_INT >= 30) epochs.forEach { (volume, epoch) ->
            if (epoch != null) check(runCatching { MediaStore.getVersion(app, volume) }.getOrNull() == epoch) {
                "Медиатека изменилась во время чтения. Откройте галерею повторно."
            }
        }
        return items.sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.addedSeconds }.thenBy { it.uri.toString() })
    }

    fun favorite(item: MediaItem) = viewModelScope.launch { preferenceWrites.withLock {
        val id = verifiedBinding(item) ?: return@withLock
        val updated = mutable.value.favorites.toMutableSet().apply {
            if (!add(id)) remove(id)
            remove(item.uri.toString()) // Explicit user choice confirms this one legacy link.
        }.toSet()
        val saved = withContext(Dispatchers.IO) {
            val edit = prefs.edit().putStringSet("favorites", updated)
            if (!prefs.contains("favorites_before_binding_v2")) edit.putStringSet("favorites_before_binding_v2", mutable.value.favorites)
            edit.commit()
        }
        if (saved) mutable.value = mutable.value.copy(favorites = updated)
        else message("Не удалось сохранить избранное")
    } }

    fun addToAlbum(name: String, item: MediaItem?) = viewModelScope.launch { preferenceWrites.withLock {
        val title = name.trim().take(80)
        if (title.isEmpty()) return@withLock
        if (readAlbums().isFailure) { message("Альбомы не прочитаны. Запись остановлена, чтобы сохранить прежние данные."); return@withLock }
        val binding = if (item == null) null else verifiedBinding(item) ?: return@withLock
        val old = mutable.value.albums.firstOrNull { it.name == title }
        val members = (old?.members ?: emptySet()).toMutableSet().apply {
            binding?.let { add(it); remove(item!!.uri.toString()) }
        }.toSet()
        val updated = mutable.value.albums.filterNot { it.name == title } + LocalAlbum(title, members)
        val payload = JSONArray().apply { updated.forEach { put(JSONObject().put("name", it.name).put("members", JSONArray(it.members.toList()))) } }.toString()
        val saved = withContext(Dispatchers.IO) {
            val edit = prefs.edit().putString("albums", payload)
            if (!prefs.contains("albums_before_binding_v2")) edit.putString("albums_before_binding_v2", prefs.getString("albums", "[]"))
            edit.commit()
        }
        if (saved) mutable.value = mutable.value.copy(albums = updated) else message("Не удалось сохранить альбом")
    } }

    private suspend fun verifiedBinding(item: MediaItem): String? {
        val binding = withContext(Dispatchers.IO) {
            runCatching { currentMediaBinding(getApplication<Application>(), item.uri) }.getOrNull()
        }
        if (binding == null || binding != item.binding) {
            message(if (Build.VERSION.SDK_INT < 30) "Для безопасной привязки фото к альбомам нужен Android 11 или новее."
                else "Привязка фото изменилась или доступ закрыт. Откройте снимок заново.")
            refresh()
            return null
        }
        return binding
    }

    fun chooseCloud(provider: String) = viewModelScope.launch { preferenceWrites.withLock {
        val saved = withContext(Dispatchers.IO) { prefs.edit().putString("cloud_provider", provider).commit() }
        if (saved) mutable.value = mutable.value.copy(cloud = CloudChoice(provider))
    } }

    fun message(text: String?) { mutable.value = mutable.value.copy(message = text) }
    suspend fun inspectRecovery(source: Uri): RecoveryInspection {
        check(!mutable.value.fileOperationRunning) { "Дождитесь окончания текущей операции" }
        mutable.value = mutable.value.copy(fileOperationRunning = true)
        return try { withContext(Dispatchers.IO) { PortableFiles.inspectRecovery(getApplication<Application>().contentResolver, source) } }
        finally { mutable.value = mutable.value.copy(fileOperationRunning = false) }
    }

    fun portableFile(source: Uri, destination: Uri, restore: Boolean, expectedContainerSha256: String? = null) {
        if (mutable.value.fileOperationRunning) return
        if (restore && expectedContainerSha256 == null) { message("Выберите архив заново для проверки"); return }
        mutable.value = mutable.value.copy(fileOperationRunning = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (restore) PortableFiles.restoreAndReadBack(getApplication<Application>(), source, destination, expectedContainerSha256)
                    else PortableFiles.packAndReadBack(getApplication<Application>(), source, destination)
                }
                message(result)
                refresh()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message("Операция не подтверждена: ${e.localizedMessage}") }
            finally { mutable.value = mutable.value.copy(fileOperationRunning = false) }
        }
    }
    private fun readAlbums(): Result<List<LocalAlbum>> = runCatching {
        val a = JSONArray(prefs.getString("albums", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            val members = o.getJSONArray("members")
            LocalAlbum(o.getString("name"), (0 until members.length()).map { members.getString(it) }.toSet())
        } }
    }

}

/** DATE_TAKEN is milliseconds; filesystem/index fallback dates are seconds. */
internal fun galleryDate(takenMillis: Long?, modifiedSeconds: Long?, addedSeconds: Long): Long {
    if (takenMillis != null && takenMillis > 0) return takenMillis
    val seconds = modifiedSeconds?.takeIf { it > 0 } ?: addedSeconds.coerceAtLeast(0)
    return Math.multiplyExact(seconds, 1000)
}
