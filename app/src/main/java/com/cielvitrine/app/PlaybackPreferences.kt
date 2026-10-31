package com.cielvitrine.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@kotlinx.serialization.Serializable internal enum class VideoQuality(val label: String, val edge: Int) {
    AUTO("Авто", 0), ORIGINAL("Оригинал", 0), P720("720p",720), P1080("1080p", 1080), P1440("1440p · 2K", 1440), P2160("2160p · 4K", 2160)
}
internal class PlaybackPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)
    var quality: VideoQuality
        get() = VideoQuality.entries.firstOrNull { it.name == preferences.getString("quality", "AUTO") } ?: VideoQuality.AUTO
        set(value) { preferences.edit().putString("quality", value.name).apply() }
    var clearAfterViewing: Boolean
        get() = preferences.getBoolean("clearAfterViewing", true)
        set(value) { preferences.edit().putBoolean("clearAfterViewing", value).apply() }
}
internal val viewCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
