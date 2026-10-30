package com.cielvitrine.app

import android.content.Context
import android.net.Uri
import com.cielvitrine.archive.ArchiveJob
import com.cielvitrine.archive.viewer.*
import java.io.File

/** Shared process-wide limits. Media3 codecs/native decoder buffers are outside imageBudget. */
internal object ViewerRuntime {
    val retrievals = RetrievalGate(4)
    val imageBudget = ByteBudget(64L * 1024 * 1024)
    val resources = ResourceLeases()
    // Authenticated multipart/plaintext working arrays are separate from decoded image memory.
    // A reader reserves its resident part and decode scratch before allocating either.
    val multipartBudget = ByteBudget(64L * 1024 * 1024)
    private var owned: OwnedViewCache? = null
    @Synchronized fun cache(context: Context): OwnedViewCache = owned ?: OwnedViewCache(
        // Android may expose /data/user/0 through a system alias to /data/data.
        // Resolve the OS-provided private root, then keep the owned child strict.
        File(context.applicationContext.cacheDir.canonicalFile, "viewer-owned-v2")).also { owned = it }
    fun snapshot(job: ArchiveJob, representation: String): ViewerSnapshot = ViewerSnapshot(
        job.uid!!, job.rootId!!, job.id, job.proof!!.containerSha256,
        "${job.remoteId}:${job.remoteModified}", representation)
    private fun sourceKey(source: String): String {
        val uri = Uri.parse(source)
        return if (uri.scheme == "file") Uri.fromFile(File(requireNotNull(uri.path)).canonicalFile).toString() else source
    }
    fun source(identity: String): AutoCloseable = resources.acquire(sourceKey(identity))
    /** Caller must establish ownership (a journal-bound staging/export file), never a user URI. */
    fun removeOwned(file: File): Boolean = resources.removeOwned(sourceKey(Uri.fromFile(file).toString())) {
        check(!file.exists() || file.delete()) { "Owned file removal failed" }
    }
}

/** The gainmap is another native raster and is included in the same image reservation. */
internal fun android.graphics.Bitmap.recycleViewerRaster() {
    if(isRecycled)return
    val gain=if(android.os.Build.VERSION.SDK_INT>=34 && hasGainmap())gainmap?.gainmapContents else null
    recycle()
    if(gain!=null && gain!==this && !gain.isRecycled)gain.recycle()
}
