package com.cielvitrine.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.min

/** MediaStore's thumbnail cache can outlive a source edit. Images use the current descriptor. */
internal object CurrentThumbnail {
    private data class Key(val uri:String,val binding:String,val generation:Long,val modified:Long,val bytes:Long)
    private val slots=Semaphore(3)
    private val cache=object:LruCache<Key,Bitmap>(16*1024*1024) {
        override fun sizeOf(key:Key,value:Bitmap)=value.allocationByteCount
    }
    suspend fun load(context:Context,item:MediaItem):Bitmap = slots.withPermit {
        item.previewId?.let{id -> return@withPermit ArchiveStore(context).use{store ->
            val plan=store.protection(id) ?: error("Миниатюра недоступна")
            require(plan.workingId==item.cloudId)
            ProtectedThumbnail(context).load(store.load(id),plan.previewSha ?: error("Миниатюра недоступна"))
        }}
        if(item.isVideo)return@withPermit context.contentResolver.loadThumbnail(item.uri,Size(320,320),null)
        // Permission is checked even on a cache hit; a revoked grant cannot serve a new request.
        openDisplayOriginal(context,item.uri).close()
        val key=if(item.binding!=null && item.generation!=null)
            Key(item.uri.toString(),item.binding,item.generation,item.modifiedSeconds,item.bytes) else null
        key?.let { cache.get(it) }?.let { return@withPermit it }
        val bitmap=ImageDecoder.decodeBitmap(displaySource(context,item.uri)) { decoder,info,_ ->
            val ratio=min(1.0,320.0/maxOf(info.size.width,info.size.height).coerceAtLeast(1))
            decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),(info.size.height*ratio).toInt().coerceAtLeast(1))
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        key?.let { cache.put(it,bitmap) }
        bitmap
    }
}
