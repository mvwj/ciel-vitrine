package com.cielvitrine.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
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
import com.cielvitrine.archive.viewer.ByteBudget
import com.cielvitrine.archive.viewer.deliverViewerResource
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
    val origin = PhotoPresentationRules.orientedPoint(0f,0f,width,height,orientation)
    val x = PhotoPresentationRules.orientedPoint(1f,0f,width,height,orientation)
    val y = PhotoPresentationRules.orientedPoint(0f,1f,width,height,orientation)
    setValues(floatArrayOf(x.first-origin.first,y.first-origin.first,origin.first,
        x.second-origin.second,y.second-origin.second,origin.second,0f,0f,1f))
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
    private var previewAllocation: ByteBudget.Reservation? = null
    private var previewTile: RenderTile? = null
    private var sourceOwner: AutoCloseable? = null
    private var displayUri: Uri? = null
    private var animation: Drawable? = null
    private var tile: Bitmap? = null
    private var tileAllocation: ByteBudget.Reservation? = null
    private var tileRect: Rect? = null
    private var remote: TileReader? = null
    private val readers=java.util.concurrent.ConcurrentHashMap<Long,TileReader>()
    private var remoteTiles: List<RenderTile> = emptyList()
    private var rawWidth = 0; private var rawHeight = 0; private var orientation = 1
    private var regionSupported = false
    private var started = 0L; private var zoomStarted = 0L; private var firstDraw = true
    private var revision = 0L
    private val viewportGeneration = PhotoGeneration()
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
    internal val cloudDetailCount get()=remoteTiles.size
    internal val cloudDetailSamples get()=remoteTiles.map{it.coordinate.sample}
    internal val cloudCacheKeys get()=remote?.cacheKeys().orEmpty()
    internal val detailLoading get()=tilePending
    internal val hasHdr get() = (Build.VERSION.SDK_INT >= 34 && preview?.hasGainmap() == true) ||
        preview?.colorSpace?.name?.let { it.contains("HLG",true) || it.contains("PQ",true) } == true
    internal val tileHasHdr get() = Build.VERSION.SDK_INT >= 34 && tile?.hasGainmap() == true

    fun bind(next: MediaItem, attempt: Int) {
        val key = listOf(next.uri, next.binding, next.generation, next.modifiedSeconds, next.bytes, next.cloudId, attempt)
        if (identity == key) return
        identity = key; item = next; revision++; viewportGeneration.advance(); task?.cancel(); regionTask?.cancel()
        val retired=revision
        val oldSource=sourceOwner;sourceOwner=null;displayUri=null
        viewCleanup.launch { decoding.withLock { oldSource?.close();readers.entries.filter{it.key<retired}.forEach{it.value.close();readers.remove(it.key)} } }
        (animation as? AnimatedImageDrawable)?.stop(); animation = null
        retireLocalDetails(); retirePreview()
        remoteTiles.forEach{it.close()}; tile = null; tileRect = null; remote=null;remoteTiles=emptyList();rawWidth=0;rawHeight=0
        scale = 1f; dx = 0f; dy = 0f; zoomStarted=0;tilePending=false; zoomState(false); detailStatus(false,false);status(true,false,false)
        started = SystemClock.elapsedRealtimeNanos(); firstDraw = true; invalidate()
        val version = revision
        task = scope.launch {
            try {
                val prepared=next.cloudId?.let { id -> withContext(Dispatchers.IO) {
                    next.mime in setOf("image/jpeg","image/png") || (next.mime!="image/gif" && TileStore(context).load(id)?.ready==true)
                } } ?: false
                if(next.cloudId!=null && prepared) {
                    deliverViewerResource(load={ decoding.withLock {
                        val reader=TileReader.open(context,next.cloudId);readers[version]=reader
                        reader.preview()
                    } },accept={ opened ->
                        check(version==revision)
                        val reader=readers.getValue(version); val c=reader.catalog
                        remote=reader;preview=opened.bitmap;previewTile=opened
                        rawWidth=c.width;rawHeight=c.height;orientation=c.orientation;regionSupported=true
                    })
                    status(false,false,hasHdr);invalidate();if(width>0)requestRegion();return@launch
                }
                deliverViewerResource(load = { decoding.withLock {
                    val recovered=if(next.cloudId!=null) {
                        require(MediaCapabilities.photo(next.mime) in setOf(MediaViewCapability.LOCAL_ANIMATION,MediaViewCapability.PLATFORM_DECODER)) { "Просмотр формата не поддерживается; оригинал сохранён" }
                        CloudOriginalReader(context).acquire(ArchiveStore(context).use{it.load(next.cloudId)})
                    } else null
                    val localUri=recovered?.let{Uri.fromFile(it.file)} ?: next.uri
                    val localLease=try{ViewerRuntime.source(localUri.toString())}catch(e:Exception){recovered?.close();throw e}
                    val ownership=AutoCloseable{try{localLease.close()}finally{recovered?.close()}}
                    try {
                    val decoded=PerformanceJournal(context).timed(next.performanceId, "image_decode") {
                        if (next.mime == "image/gif" || next.mime == "image/webp") {
                            val reservation=ViewerRuntime.imageBudget.reserve(56L*1024*1024)
                            val drawable=try { ImageDecoder.decodeDrawable(displaySource(context,localUri)) { decoder,info,_ ->
                                val ratio=min(1.0,sqrt(1_000_000.0/(info.size.width.toDouble()*info.size.height)))
                                decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),(info.size.height*ratio).toInt().coerceAtLeast(1))
                                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                            } }
                                catch(e:Exception){reservation.close();throw e}
                            if (drawable is AnimatedImageDrawable) return@timed Loaded(null,drawable,drawable.intrinsicWidth,drawable.intrinsicHeight,1,false,reservation)
                            (drawable as? BitmapDrawable)?.bitmap?.recycleViewerRaster()
                            reservation.close()
                        }
                        val exif = runCatching { openDisplayOriginal(context,localUri).use {
                            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)
                        } }.getOrDefault(1).takeIf{it in 1..8} ?: 1
                        val region = runCatching { openDisplayOriginal(context,localUri).use { BitmapRegionDecoder.newInstance(it,false) } }.getOrNull()
                        if (region != null) try {
                            val sample = sampleFor(region.width,region.height,exif)
                            val reservation=ViewerRuntime.imageBudget.reserve(56L*1024*1024)
                            try {
                                val bitmap = region.decodeRegion(Rect(0,0,region.width,region.height),BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGBA_F16 })
                                    ?: error("Изображение не прочитано")
                                try { reservation.shrink(rasterBytes(bitmap)); Loaded(bitmap,null,region.width,region.height,exif,true,reservation) }
                                catch(e:Exception){bitmap.recycleViewerRaster();throw e}
                            } catch(e:Exception) { reservation.close(); throw e }
                        } finally { region.recycle() }
                        else {
                            val reservation=ViewerRuntime.imageBudget.reserve(56L*1024*1024)
                            try { val bitmap = ImageDecoder.decodeBitmap(displaySource(context,localUri)) { decoder,info,_ ->
                                // Explicit fallback for formats without native region decoding; never a thumbnail source.
                                val ratio = min(1.0,sqrt(PhotoPresentationRules.MAX_DECODE_PIXELS.toDouble() / (info.size.width.toDouble()*info.size.height)))
                                decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),(info.size.height*ratio).toInt().coerceAtLeast(1))
                            }
                            try { reservation.shrink(rasterBytes(bitmap)); Loaded(bitmap,null,bitmap.width,bitmap.height,1,false,reservation) }
                            catch(e:Exception){bitmap.recycleViewerRaster();throw e}
                            } catch(e:Exception) { reservation.close(); throw e }
                        }
                    }
                decoded.copy(owner=ownership,uri=localUri)
                    }catch(e:Exception){ownership.close();throw e}
                } }, accept = { loaded ->
                check(version==revision)
                loaded.drawable?.callback=this@OriginalPhotoView; (loaded.drawable as? AnimatedImageDrawable)?.start()
                preview=loaded.bitmap; previewAllocation=loaded.allocation; animation=loaded.drawable; rawWidth=loaded.width; rawHeight=loaded.height
                orientation=loaded.orientation; regionSupported=loaded.region; sourceOwner=loaded.owner;displayUri=loaded.uri
                })
                status(false,false,hasHdr); invalidate()
                if (width > 0) requestRegion()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if(version==revision) status(false,true,false) }
        }
    }
    private data class Loaded(val bitmap: Bitmap?,val drawable: Drawable?,val width:Int,val height:Int,val orientation:Int,val region:Boolean,val allocation:ByteBudget.Reservation?=null,val owner:AutoCloseable?=null,val uri:Uri?=null):AutoCloseable {
        override fun close(){bitmap?.recycleViewerRaster();(drawable as? AnimatedImageDrawable)?.stop();allocation?.close();owner?.close()}
    }
    private fun retirePreview() {
        if(previewTile!=null)previewTile?.close() else preview?.recycleViewerRaster()
        previewTile=null;preview=null;previewAllocation?.close();previewAllocation=null
    }
    private fun rasterBytes(bitmap:Bitmap):Long = bitmap.allocationByteCount.toLong() +
        (if(Build.VERSION.SDK_INT>=34 && bitmap.hasGainmap()) bitmap.gainmap!!.gainmapContents.allocationByteCount.toLong() else 0L)
    private fun retireLocalDetails() { tile?.recycleViewerRaster(); tile=null; tileAllocation?.close(); tileAllocation=null; tileRect=null }
    private fun sampleFor(w:Int,h:Int,o:Int):Int {
        return PhotoPresentationRules.sampleFor(w,h,width,height,o)
    }
    private fun fit():Float = min(width.toFloat() / (if(orientation in 5..8) rawHeight else rawWidth).coerceAtLeast(1),
        height.toFloat() / (if(orientation in 5..8) rawWidth else rawHeight).coerceAtLeast(1))
    private fun imageMatrix():Matrix = Matrix(photoOrientation(rawWidth,rawHeight,orientation)).apply {
        val ow=if(orientation in 5..8)rawHeight else rawWidth; val oh=if(orientation in 5..8)rawWidth else rawHeight
        postTranslate(-ow/2f,-oh/2f); postScale(fit()*scale,fit()*scale); postTranslate(width/2f+dx,height/2f+dy)
    }
    fun transform(zoom:Float,x:Float,y:Float,focusX:Float=width/2f,focusY:Float=height/2f) {
        if(rawWidth==0)return
        val oldScale=scale
        scale=(scale*zoom).coerceIn(1f,max(8f,2f/fit()).coerceAtMost(64f))
        val factor=scale/oldScale
        val ow=if(orientation in 5..8)rawHeight else rawWidth; val oh=if(orientation in 5..8)rawWidth else rawHeight
        dx=PhotoPresentationRules.focalOffset(dx,focusX-width/2f,factor,x).coerceIn(-max(0f,(ow*fit()*scale-width)/2),max(0f,(ow*fit()*scale-width)/2))
        dy=PhotoPresentationRules.focalOffset(dy,focusY-height/2f,factor,y).coerceIn(-max(0f,(oh*fit()*scale-height)/2),max(0f,(oh*fit()*scale-height)/2))
        if(scale==1f){dx=0f;dy=0f}; zoomState(scale>1.01f)
        zoomStarted=SystemClock.elapsedRealtimeNanos();tilePending=true; invalidate(); requestRegion()
    }
    fun doubleTap(x:Float,y:Float) {
        val target=if(scale>1.01f)1f else max(3f,1f/fit()).coerceAtMost(16f)
        val factor=target/scale
        transform(factor,0f,0f,x,y)
    }
    private fun requestRegion() {
        regionTask?.cancel()
        val requestGeneration=viewportGeneration.advance()
        val source=(item?:return).copy(uri=displayUri ?: item!!.uri)
        if(!regionSupported || width==0 || rawWidth==0)return
        val inverse=Matrix(); if(!imageMatrix().invert(inverse))return
        val viewport=RectF(0f,0f,width.toFloat(),height.toFloat()); inverse.mapRect(viewport)
        val rect=Rect(floor(viewport.left).toInt().coerceIn(0,rawWidth),floor(viewport.top).toInt().coerceIn(0,rawHeight),
            ceil(viewport.right).toInt().coerceIn(0,rawWidth),ceil(viewport.bottom).toInt().coerceIn(0,rawHeight))
        if(rect.isEmpty)return
        var sample=1; while(sample*2*fit()*scale<=1f)sample*=2
        // The loader selects a coarser level when the preview plus requested detail
        // and decode scratch would exceed the shared budget.
        val version=revision
        // Keep only the coarse preview resident while a new working set is decoded.
        remoteTiles.forEach { it.close() }; remoteTiles=emptyList(); retireLocalDetails()
        regionTask=scope.launch {
            delay(60)
            detailStatus(true,false)
            try {
                val reader=remote
                if(reader!=null) {
                    deliverViewerResource(load={decoding.withLock { TileBatch(reader.visible(rect,sample) { viewportGeneration.isCurrent(requestGeneration) }) }},accept={loaded ->
                        check(version==revision && viewportGeneration.isCurrent(requestGeneration))
                        remoteTiles=loaded.tiles;tileRect=rect;tilePending=false;detailStatus(false,false);invalidate()
                    });return@launch
                }
                deliverViewerResource(load={decoding.withLock { ViewerRuntime.source(source.uri.toString()).use {
                    val decodeSample=PhotoPresentationRules.boundedDetailSample(rect.width(),rect.height(),sample,
                        ViewerRuntime.imageBudget.maximum-ViewerRuntime.imageBudget.used())
                    val detailPixels=PhotoPresentationRules.pixels(rect.width(),rect.height(),decodeSample)
                    val reservation=ViewerRuntime.imageBudget.reserve(detailPixels*16L)
                    try {
                    val bitmap=
                    PerformanceJournal(context).timed(source.performanceId,"zoom_decode") {
                        openDisplayOriginal(context,source.uri).use { input ->
                            val decoder=BitmapRegionDecoder.newInstance(input,false) ?: error("Область недоступна")
                            try { decoder.decodeRegion(rect,BitmapFactory.Options().apply { inSampleSize=decodeSample;inPreferredConfig=Bitmap.Config.RGBA_F16 }) ?: error("Область недоступна") }
                            finally { decoder.recycle() }
                        }
                    }
                    try { reservation.shrink(rasterBytes(bitmap)) }
                    catch(e:Exception){bitmap.recycleViewerRaster();throw e}
                    Loaded(bitmap,null,rect.width(),rect.height(),orientation,true,reservation)
                    } catch(e:Exception){reservation.close();throw e}
                } } },accept={ loaded ->
                check(version==revision && viewportGeneration.isCurrent(requestGeneration))
                val bitmap=requireNotNull(loaded.bitmap)
                // A vendor decoder may silently discard a gainmap. Do not overlay an SDR tile on HDR.
                if(Build.VERSION.SDK_INT>=34 && preview?.hasGainmap()==true && !bitmap.hasGainmap())error("HDR details unavailable")
                tile=bitmap;tileAllocation=loaded.allocation;tileRect=rect;tilePending=false;detailStatus(false,false);invalidate()
                })
            }catch(e:CancellationException){throw e}
            catch(_:Exception){if(version==revision && viewportGeneration.isCurrent(requestGeneration))detailStatus(false,true)}
        }
    }
    private class TileBatch(val tiles:List<RenderTile>):AutoCloseable {override fun close(){tiles.forEach{it.close()}}}
    fun retryDetails(){zoomStarted=SystemClock.elapsedRealtimeNanos();tilePending=true;requestRegion()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);viewportGeneration.advance();requestRegion()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        if(rawWidth==0)return
        val saved=canvas.save();canvas.concat(imageMatrix())
        // Details replace coarse pixels. Drawing translucent pixels twice would change alpha.
        val coarse=canvas.save()
        if(tile!=null)tileRect?.let { canvas.clipOutRect(it) }
        remoteTiles.forEach { canvas.clipOutRect(it.coordinate.core) }
        preview?.let { canvas.drawBitmap(it,null,RectF(0f,0f,rawWidth.toFloat(),rawHeight.toFloat()),paint) }
        canvas.restoreToCount(coarse)
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
    fun release(){revision++;viewportGeneration.advance();task?.cancel();regionTask?.cancel();scope.cancel();(animation as?AnimatedImageDrawable)?.stop();animation?.callback=null;animation=null;retirePreview();retireLocalDetails();remote=null;remoteTiles.forEach{it.close()};remoteTiles=emptyList()
        val oldSource=sourceOwner;sourceOwner=null;displayUri=null
        viewCleanup.launch{decoding.withLock{oldSource?.close();readers.values.forEach{it.close()};readers.clear()}}
    }
}
