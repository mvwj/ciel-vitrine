package com.cielvitrine.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.cielvitrine.archive.*
import java.io.*

/** Exact original byte ranges supplied to Media3; authentication completes before a part is exposed. */
internal class MultipartSession(private val context: Context, val job: ArchiveJob,
    private val cloud: ArchiveCloud = PlaybackArchiveCloud(context),
    private val directory: File = File(context.cacheDir, "video-sessions/${ArchiveStore.randomId()}"),
    private val clearOnClose: Boolean = PlaybackPreferences(context).clearAfterViewing) : AutoCloseable {
    private val cancel = com.cielvitrine.archive.viewer.ViewCancellation()
    private val reader = com.cielvitrine.archive.viewer.VerifiedMultipartReader(job, cloud, ViewerRuntime.cache(context), cancel, clearOnClose, ViewerRuntime.multipartBudget)
    private val sourceLease = ViewerRuntime.source(job.source)
    fun open(): MultipartRoot = reader.open()
    fun bytes(index: Long): ByteArray = reader.bytes(index)
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = reader.readAt(position, buffer, offset, length)
    fun restore(output: OutputStream) = reader.restore(output)
    override fun close() { try { reader.close() } finally { sourceLease.close() } }

}

private class PlaybackArchiveCloud(context: Context): RangedArchiveCloud {
    private val context = context.applicationContext
    private val session = YandexSessionStore(context).load() ?: error("Подключите Яндекс")
    private val delegate = YandexArchiveCloud(context, session)
    private var checked = false
    override fun requireAccount(uid: String, rootId: String) {
        val current = YandexSessionStore(context).load() ?: error("Подключите прежний аккаунт")
        require(current.uid == uid && current.appRootId == rootId && current.token == session.token)
        if(!checked) { delegate.requireAccount(uid, rootId); checked = true }
    }
    override fun ensureColdFolders() = error("Playback cannot upload")
    override fun inspect(path: String) = delegate.inspect(path)
    override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) = error("Playback cannot upload")
    override fun <T> readback(path: String, read: (InputStream) -> T) = delegate.readback(path, read)
    override fun openRange(path: String, offset: Long, expected: ArchiveRemote) = delegate.openRange(path, offset, expected)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MultipartDataSource(private val session: MultipartSession): BaseDataSource(true) {
    private var uri: Uri? = null
    private var position = 0L
    private var left = 0L
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        require(!opened) { "DataSource already open" }
        transferInitializing(dataSpec)
        val root = session.open(); require(dataSpec.position in 0..root.passport.bytes)
        uri = dataSpec.uri; position = dataSpec.position
        left = if(dataSpec.length == C.LENGTH_UNSET.toLong()) root.passport.bytes-position else minOf(dataSpec.length, root.passport.bytes-position)
        opened = true; transferStarted(dataSpec); return left
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size-length)
        require(opened); if(length == 0) return 0; if(left == 0L) return C.RESULT_END_OF_INPUT
        val n = session.readAt(position, buffer, offset, minOf(length.toLong(), left).toInt())
        require(n > 0); position += n; left -= n; bytesTransferred(n); return n
    }
    override fun getUri(): Uri? = uri
    override fun close() { uri = null; if(opened) { opened = false; transferEnded() } }
    class Factory(private val session: MultipartSession): DataSource.Factory { override fun createDataSource(): DataSource = MultipartDataSource(session) }
}
