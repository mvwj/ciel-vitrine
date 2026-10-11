package com.cielvitrine.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import kotlin.math.*

internal fun openDisplayOriginal(context: Context, uri: Uri): InputStream {
    if (uri.authority != MediaStore.AUTHORITY || Build.VERSION.SDK_INT < 31)
        return context.contentResolver.openInputStream(uri) ?: error("Файл недоступен")
    val descriptor = context.contentResolver.openTypedAssetFileDescriptor(uri, "*/*", Bundle().apply {
        putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true)
    }) ?: error("Оригинальный формат недоступен")
    return OriginalInputStream(descriptor)
}
internal fun displaySource(context:Context,uri:Uri):ImageDecoder.Source =
    if(uri.scheme=="file")ImageDecoder.createSource(java.io.File(uri.path ?: error("Файл недоступен")))
    else if(Build.VERSION.SDK_INT>=31 && uri.authority==MediaStore.AUTHORITY) ImageDecoder.createSource(java.util.concurrent.Callable {
        context.contentResolver.openTypedAssetFileDescriptor(uri,"*/*",Bundle().apply {
            putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT,true)
        }) ?: error("Файл недоступен")
    }) else ImageDecoder.createSource(context.contentResolver,uri)

internal fun photoOrientation(width: Int, height: Int, orientation: Int): Matrix = Matrix().apply {
    val w = width.toFloat(); val h = height.toFloat()
    setValues(when (orientation) {
        2 -> floatArrayOf(-1f,0f,w, 0f,1f,0f, 0f,0f,1f)
        3 -> floatArrayOf(-1f,0f,w, 0f,-1f,h, 0f,0f,1f)
        4 -> floatArrayOf(1f,0f,0f, 0f,-1f,h, 0f,0f,1f)
        5 -> floatArrayOf(0f,1f,0f, 1f,0f,0f, 0f,0f,1f)
        6 -> floatArrayOf(0f,-1f,h, 1f,0f,0f, 0f,0f,1f)
        7 -> floatArrayOf(0f,-1f,h, -1f,0f,w, 0f,0f,1f)
        8 -> floatArrayOf(0f,1f,0f, -1f,0f,w, 0f,0f,1f)
        else -> floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
    })
}

/** Hardware Canvas keeps gainmaps. One bounded visible region is decoded at a time. */
internal class OriginalPhotoView(context: Context) : View(context), Drawable.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoding = Mutex()
    private var task: Job? = null
    private var regionTask: Job? = null
    private var identity: List<Any?>? = null
    private var item: MediaItem? = null
    private var preview: Bitmap? = null
    private var animation: Drawable? = null
    private var tile: Bitmap? = null
    private var tileRect: Rect? = null
    private var remote: TileReader? = null
    private var remoteTiles: List<RenderTile> = emptyList()
    private var rawWidth = 0; private var rawHeight = 0; private var orientation = 1
    private var regionSupported = false
    private var started = 0L; private var zoomStarted = 0L; private var firstDraw = true
    private var revision = 0
    private var tilePending = false
    var scale = 1f; private set
    private var dx = 0f; private var dy = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var status: (Boolean, Boolean, Boolean) -> Unit = { _,_,_ -> }
    var zoomState: (Boolean) -> Unit = {}
    var detailStatus: (Boolean,Boolean) -> Unit = {_,_ ->}
    internal val loadedWidth get() = rawWidth
    internal val loadedHeight get() = rawHeight
    internal val visibleRegion get() = tileRect?.let { Rect(it) }
    internal val tileWidth get() = tile?.width ?: 0
    internal val hasHdr get() = (Build.VERSION.SDK_INT >= 34 && preview?.hasGainmap() == true) ||
        preview?.colorSpace?.name?.let { it.contains("HLG",true) || it.contains("PQ",true) } == true
    internal val tileHasHdr get() = Build.VERSION.SDK_INT >= 34 && tile?.hasGainmap() == true

    fun bind(next: MediaItem, attempt: Int) {
        val key = listOf(next.uri, next.binding, next.generation, next.modifiedSeconds, next.bytes, next.cloudId, attempt)
        if (identity == key) return
        identity = key; item = next; revision++; task?.cancel(); regionTask?.cancel()
        (animation as? AnimatedImageDrawable)?.stop(); animation = null; preview = null; tile = null; tileRect = null; remote=null;remoteTiles=emptyList();rawWidth=0;rawHeight=0
        scale = 1f; dx = 0f; dy = 0f; zoomStarted=0;tilePending=false; zoomState(false); detailStatus(false,false);status(true,false,false)
        started = SystemClock.elapsedRealtimeNanos(); firstDraw = true; invalidate()
        val version = revision
        task = scope.launch {
            try {
                if(next.cloudId!=null) {
                    val opened=withContext(Dispatchers.IO){decoding.withLock {
                        val reader=TileReader.open(context,next.cloudId);reader to reader.preview()
                    }}
                    if(version!=revision)return@launch
                    val c=opened.first.catalog;remote=opened.first;preview=opened.second.bitmap
                    rawWidth=c.width;rawHeight=c.height;orientation=c.orientation;regionSupported=true
                    status(false,false,hasHdr);invalidate();if(width>0)requestRegion();return@launch
                }
                val loaded = withContext(Dispatchers.IO) { decoding.withLock {
                    PerformanceJournal(context).timed(next.performanceId, "image_decode") {
                        if (next.mime == "image/gif" || next.mime == "image/webp") {
                            val drawable = ImageDecoder.decodeDrawable(displaySource(context,next.uri))
                            if (drawable is AnimatedImageDrawable) return@timed Loaded(null,drawable,drawable.intrinsicWidth,drawable.intrinsicHeight,1,false)
                        }
                        val exif = runCatching { openDisplayOriginal(context,next.uri).use {
                            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)
                        } }.getOrDefault(1).takeIf{it in 1..8} ?: 1
                        val region = runCatching { openDisplayOriginal(context,next.uri).use { BitmapRegionDecoder.newInstance(it,false) } }.getOrNull()
                        if (region != null) try {
                            val sample = sampleFor(region.width,region.height,exif)
                            val bitmap = region.decodeRegion(Rect(0,0,region.width,region.height),BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGBA_F16 })
                                ?: error("Изображение не прочитано")
                            Loaded(bitmap,null,region.width,region.height,exif,true)
                        } finally { region.recycle() }
                        else {
                            val bitmap = ImageDecoder.decodeBitmap(displaySource(context,next.uri)) { decoder,info,_ ->
                                // Explicit fallback for formats without native region decoding; never a thumbnail source.
                                val ratio = min(1.0,sqrt(16_000_000.0 / (info.size.width.toDouble()*info.size.height)))
                                decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),(info.size.height*ratio).toInt().coerceAtLeast(1))
                            }
                            Loaded(bitmap,null,bitmap.width,bitmap.height,1,false)
                        }
                    }
                } }
                if (version != revision) return@launch
                preview=loaded.bitmap; animation=loaded.drawable; rawWidth=loaded.width; rawHeight=loaded.height
                orientation=loaded.orientation; regionSupported=loaded.region
                animation?.callback=this@OriginalPhotoView; (animation as? AnimatedImageDrawable)?.start()
                status(false,false,hasHdr); invalidate()
                if (width > 0) requestRegion()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if(version==revision) status(false,true,false) }
        }
    }
    private data class Loaded(val bitmap: Bitmap?,val drawable: Drawable?,val width:Int,val height:Int,val orientation:Int,val region:Boolean)
    private fun sampleFor(w:Int,h:Int,o:Int):Int {
        val targetW = if(o in 5..8) height else width
        val targetH = if(o in 5..8) width else height
        val limit = max(w.toFloat()/targetW.coerceAtLeast(1024),h.toFloat()/targetH.coerceAtLeast(1024))
        var sample=1; while(sample*2 <= limit) sample*=2
        return sample
    }
    private fun fit():Float = min(width.toFloat() / (if(orientation in 5..8) rawHeight else rawWidth).coerceAtLeast(1),
        height.toFloat() / (if(orientation in 5..8) rawWidth else rawHeight).coerceAtLeast(1))
    private fun imageMatrix():Matrix = Matrix(photoOrientation(rawWidth,rawHeight,orientation)).apply {
        val ow=if(orientation in 5..8)rawHeight else rawWidth; val oh=if(orientation in 5..8)rawWidth else rawHeight
        postTranslate(-ow/2f,-oh/2f); postScale(fit()*scale,fit()*scale); postTranslate(width/2f+dx,height/2f+dy)
    }
    fun transform(zoom:Float,x:Float,y:Float) {
        if(rawWidth==0)return
        scale=(scale*zoom).coerceIn(1f,max(8f,2f/fit()).coerceAtMost(64f))
        val ow=if(orientation in 5..8)rawHeight else rawWidth; val oh=if(orientation in 5..8)rawWidth else rawHeight
        dx=(dx+x).coerceIn(-max(0f,(ow*fit()*scale-width)/2),max(0f,(ow*fit()*scale-width)/2))
        dy=(dy+y).coerceIn(-max(0f,(oh*fit()*scale-height)/2),max(0f,(oh*fit()*scale-height)/2))
        if(scale==1f){dx=0f;dy=0f}; zoomState(scale>1.01f)
        zoomStarted=SystemClock.elapsedRealtimeNanos();tilePending=true; invalidate(); requestRegion()
    }
    fun doubleTap(x:Float,y:Float) {
        val target=if(scale>1.01f)1f else max(3f,1f/fit()).coerceAtMost(16f)
        val factor=target/scale
        transform(factor,(width/2f-x)*(factor-1),(height/2f-y)*(factor-1))
    }
    private fun requestRegion() {
        val source=item?:return
        if(!regionSupported || width==0 || rawWidth==0)return
        val inverse=Matrix(); if(!imageMatrix().invert(inverse))return
        val viewport=RectF(0f,0f,width.toFloat(),height.toFloat()); inverse.mapRect(viewport)
        val rect=Rect(floor(viewport.left).toInt().coerceIn(0,rawWidth),floor(viewport.top).toInt().coerceIn(0,rawHeight),
            ceil(viewport.right).toInt().coerceIn(0,rawWidth),ceil(viewport.bottom).toInt().coerceIn(0,rawHeight))
        if(rect.isEmpty)return
        var sample=1; while(sample*2*fit()*scale<=1f)sample*=2
        // One tile is bounded by physical viewport pixels, including high-density phones.
        if(rect.width().toLong()/sample * (rect.height()/sample) > 12_000_000L)return
        regionTask?.cancel(); val version=revision
        regionTask=scope.launch {
            delay(60)
            detailStatus(true,false)
            try {
                val reader=remote
                if(reader!=null) {
                    val loaded=withContext(Dispatchers.IO){decoding.withLock{reader.visible(rect,sample)}}
                    if(version!=revision)return@launch
                    remoteTiles=loaded;tileRect=rect;tilePending=false;detailStatus(false,false);invalidate();return@launch
                }
                val bitmap=withContext(Dispatchers.IO) { decoding.withLock {
                    PerformanceJournal(context).timed(source.performanceId,"zoom_decode") {
                        openDisplayOriginal(context,source.uri).use { input ->
                            val decoder=BitmapRegionDecoder.newInstance(input,false) ?: error("Область недоступна")
                            try { decoder.decodeRegion(rect,BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGBA_F16 }) ?: error("Область недоступна") }
                            finally { decoder.recycle() }
                        }
                    }
                } }
                if(version!=revision)return@launch
                // A vendor decoder may silently discard a gainmap. Do not overlay an SDR tile on HDR.
                if(Build.VERSION.SDK_INT>=34 && preview?.hasGainmap()==true && !bitmap.hasGainmap())error("HDR details unavailable")
                tile=bitmap;tileRect=rect;tilePending=false;detailStatus(false,false);invalidate()
            }catch(e:CancellationException){throw e}
            catch(_:Exception){if(version==revision)detailStatus(false,true)}
        }
    }
    fun retryDetails(){zoomStarted=SystemClock.elapsedRealtimeNanos();tilePending=true;requestRegion()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);requestRegion()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        if(rawWidth==0)return
        val saved=canvas.save();canvas.concat(imageMatrix())
        preview?.let { canvas.drawBitmap(it,null,RectF(0f,0f,rawWidth.toFloat(),rawHeight.toFloat()),paint) }
        animation?.let { it.setBounds(0,0,rawWidth,rawHeight);it.draw(canvas) }
        tile?.let { bitmap -> tileRect?.let { canvas.drawBitmap(bitmap,null,RectF(it),paint) } }
        remoteTiles.forEach { region ->
            val clip=canvas.save();canvas.clipRect(region.coordinate.core)
            canvas.drawBitmap(region.bitmap,null,RectF(region.coordinate.expanded),paint);canvas.restoreToCount(clip)
        }
        canvas.restoreToCount(saved)
        val metrics=PerformanceJournal(context);val id=item?.performanceId
        if(firstDraw){firstDraw=false;val elapsed=SystemClock.elapsedRealtimeNanos()-started;if(id!=null)scope.launch(Dispatchers.IO){metrics.record(id,if(remote!=null)"cloud_first_frame" else "open_first_frame",elapsed)}}
        if(zoomStarted!=0L && (tile!=null || remoteTiles.isNotEmpty()) && !tilePending){val elapsed=SystemClock.elapsedRealtimeNanos()-zoomStarted;zoomStarted=0
            if(id!=null)scope.launch(Dispatchers.IO){metrics.record(id,if(remote!=null)"cloud_zoom_frame" else "zoom_first_frame",elapsed)}}
    }
    override fun invalidateDrawable(who:Drawable){invalidate()}
    override fun scheduleDrawable(who:Drawable,what:Runnable,whenMillis:Long){postDelayed(what,(whenMillis-SystemClock.uptimeMillis()).coerceAtLeast(0))}
    override fun unscheduleDrawable(who:Drawable,what:Runnable){removeCallbacks(what)}
    fun release(){revision++;task?.cancel();regionTask?.cancel();scope.cancel();(animation as?AnimatedImageDrawable)?.stop();animation?.callback=null;animation=null;preview=null;tile=null;remote=null;remoteTiles=emptyList()}
}
