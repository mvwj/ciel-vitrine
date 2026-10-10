package com.cielvitrine.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray

/** Row identity, independent of edits. An unproven legacy URI never matches a current row. */
internal fun mediaBinding(uri: String, epoch: String?, generationAdded: Long?): String? {
    if (epoch.isNullOrEmpty() || generationAdded == null || generationAdded < 0) return null
    return "media-v2:" + JSONArray(listOf(uri, epoch, generationAdded)).toString()
}

internal fun currentMediaBinding(context: Context, uri: Uri): String? {
    if (Build.VERSION.SDK_INT < 30 || uri.authority != MediaStore.AUTHORITY) return null
    val volume = MediaStore.getVolumeName(uri)
    val epoch = MediaStore.getVersion(context, volume)
    val added = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.GENERATION_ADDED), null, null, null)
        ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    if (MediaStore.getVersion(context, volume) != epoch) return null
    return mediaBinding(uri.toString(), epoch, added)
}

internal fun Set<String>.containsMedia(item: MediaItem): Boolean = item.binding?.let { it in this } == true

internal fun collectionSummary(members: Set<String>, media: List<MediaItem>): String {
    val available = media.count { members.containsMedia(it) }
    val unavailable = members.size - available
    return if (unavailable == 0) "$available файлов" else "$available доступны · $unavailable требуют проверки или доступа"
}
