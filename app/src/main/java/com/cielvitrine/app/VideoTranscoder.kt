package com.cielvitrine.app

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.*
import androidx.media3.common.MediaItem as PlayerMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class UnsupportedVideoPreparation(message:String):IllegalArgumentException(message)

@Serializable internal data class VideoInfo(val width: Int, val height: Int, val durationUs: Long,
    val audioTracks: Int, val transfer: Int, val standard: Int, val range: Int,
    val staticHdr: String?, val codec: String, val frameRate: Float = -1f,
    val rotation: Int = 0, val videoTracks: Int = 1, val otherTracks: Int = 0,
    val audioFacts: List<String> = emptyList(), val firstSamplesUs: List<Long> = emptyList()) {
    val hdr: Boolean get() = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG || staticHdr != null || codec == "video/dolby-vision"
    val shortEdge: Int get() = minOf(width,height)
    fun requireDerivative(source: VideoInfo, edge: Int) {
        require(width>0 && height>0 && durationUs>=0 && edge>0)
        require(edge <= source.shortEdge && shortEdge == edge && width <= source.width && height <= source.height)
        require(kotlin.math.abs(width.toDouble()/height - source.width.toDouble()/source.height) < .01)
        require(kotlin.math.abs(durationUs-source.durationUs) <= 100000) { "Video duration changed" }
        require((audioTracks > 0) == (source.audioTracks > 0)) { "Audio missing" }
        require(audioTracks == source.audioTracks && videoTracks == source.videoTracks && otherTracks == source.otherTracks) { "Declared tracks changed" }
        require(audioFacts == source.audioFacts) { "Audio language, channels or sample rate changed" }
        // Media3 may bake source rotation into pixels. width/height are display axes.
        require(rotation == source.rotation || rotation == 0) { "Video orientation changed" }
        if (source.frameRate > 0f)
            require(frameRate.isFinite() && frameRate > 0f && kotlin.math.abs(frameRate-source.frameRate) <= 0.01f) { "Frame rate changed or unknown" }
        if(source.firstSamplesUs.isNotEmpty()) {
            require(firstSamplesUs.size==source.firstSamplesUs.size)
            val tolerance=maxOf(250_000L,if(source.frameRate>0) kotlin.math.ceil(1_000_000.0/source.frameRate).toLong() else 250_000L)
            require(firstSamplesUs.zip(source.firstSamplesUs).all { (a,b) -> a>=0 && b>=0 && kotlin.math.abs(a-b)<=tolerance }) { "Timeline origin changed" }
        }
        if(source.hdr) require(hdr && transfer == source.transfer && standard == source.standard && range == source.range && (source.staticHdr == null || staticHdr == source.staticHdr)) { "HDR data changed" }
        require((source.transfer<0 || transfer==source.transfer) && (source.standard<0 || standard==source.standard) &&
            (source.range<0 || range==source.range)) { "Video color description changed" }
    }
}

/** Native Media3 transformation of a viewing copy. The exact archive is never transformed. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object VideoTranscoder {
    fun inspect(session: MultipartSession): VideoInfo {
        val source = object: MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = session.readAt(position,buffer,offset,size)
            override fun getSize() = session.open().passport.bytes
            override fun close() {}
        }
        val extractor = MediaExtractor()
        try { extractor.setDataSource(source); return inspect(extractor) } finally { extractor.release(); source.close() }
    }
    fun inspect(file: File): VideoInfo {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(file.path); return inspect(extractor) } finally { extractor.release() }
    }
    private fun inspect(extractor: MediaExtractor): VideoInfo {
        var video: MediaFormat? = null; var audio = 0; var videos = 0; var others=0
        val audioFacts=mutableListOf<String>();val firstSamples=mutableListOf<Long>()
        for(i in 0 until extractor.trackCount) { val format = extractor.getTrackFormat(i); val mime=format.getString(MediaFormat.KEY_MIME) ?: ""
            if(mime.startsWith("video/")) { videos++; if (video == null) video=format }
            if(mime.startsWith("audio/")) {
                audio++
                fun fact(key:String)=if(format.containsKey(key))format.getInteger(key).toString() else "unknown"
                audioFacts+="${format.getString(MediaFormat.KEY_LANGUAGE) ?: "und"}:${fact(MediaFormat.KEY_CHANNEL_COUNT)}:${fact(MediaFormat.KEY_SAMPLE_RATE)}"
            }
            if(!mime.startsWith("video/") && !mime.startsWith("audio/"))others++
            extractor.selectTrack(i);extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            firstSamples+=extractor.sampleTime;extractor.unselectTrack(i)
        }
        val v = video ?: error("No video track")
        fun integer(key: String, fallback: Int = -1) = if(v.containsKey(key))v.getInteger(key) else fallback
        val w = integer(MediaFormat.KEY_WIDTH); val h=integer(MediaFormat.KEY_HEIGHT); require(w>0 && h>0)
        val rotation=integer(MediaFormat.KEY_ROTATION,0); val portrait=rotation in setOf(90,270)
        val static = v.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)?.let { b -> val bytes=ByteArray(b.remaining());b.get(bytes);tileHash(bytes) }
        val duration=if(v.containsKey(MediaFormat.KEY_DURATION))v.getLong(MediaFormat.KEY_DURATION) else -1; require(duration>=0)
        return VideoInfo(if(portrait)h else w,if(portrait)w else h,duration,audio,integer(MediaFormat.KEY_COLOR_TRANSFER),integer(MediaFormat.KEY_COLOR_STANDARD),integer(MediaFormat.KEY_COLOR_RANGE),static,v.getString(MediaFormat.KEY_MIME)!!,
            if(v.containsKey(MediaFormat.KEY_FRAME_RATE)) v.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: -1f else -1f,rotation,videos,others,audioFacts,firstSamples)
    }
    suspend fun convert(context: Context, session: MultipartSession, output: File, edge: Int,
        info: VideoInfo, stopped: () -> Boolean = { false }) {
        preparationProblem(info,edge)?.let { throw UnsupportedVideoPreparation(it) }
        require(!output.exists() && output.parentFile!!.usableSpace > 64L*1024*1024)
        val ratio=edge.toDouble()/info.shortEdge
        val w=(info.width*ratio/2).toInt()*2;val h=(info.height*ratio/2).toInt()*2
        val sourceFactory=DefaultMediaSourceFactory(context).setDataSourceFactory(MultipartDataSource.Factory(session))
        withContext(Dispatchers.Main) { suspendCancellableCoroutine<Unit> { continuation ->
            val handler=Handler(Looper.getMainLooper()); var transformer: Transformer?=null
            fun fail(error: Exception) { if(continuation.isActive) { transformer?.cancel(); continuation.resumeWithException(error) } }
            val watcher=object: Runnable { override fun run() {
                if(!continuation.isActive) return
                if(stopped() || output.parentFile!!.usableSpace < 64L*1024*1024) fail(IllegalStateException("Transformation paused"))
                else handler.postDelayed(this,1000)
            } }
            val listener=object: Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(watcher); if(continuation.isActive)continuation.resume(Unit)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    handler.removeCallbacks(watcher); fail(exportException)
                }
                override fun onFallbackApplied(composition: Composition, originalTransformationRequest: TransformationRequest, fallbackTransformationRequest: TransformationRequest) {
                    // The default Transformer may tone-map unsupported HDR. This app rejects that output.
                    if(fallbackTransformationRequest.hdrMode != Composition.HDR_MODE_KEEP_HDR ||
                        fallbackTransformationRequest.outputHeight != originalTransformationRequest.outputHeight) {
                        handler.removeCallbacks(watcher); fail(UnsupportedVideoPreparation("Кодер не поддерживает запрошенное качество. Используйте оригинал."))
                    }
                }
            }
            try {
                transformer=Transformer.Builder(context)
                    .setVideoMimeType(if(info.hdr)MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                    .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
                    .setAssetLoaderFactory(ExoPlayerAssetLoader.Factory(context,DefaultDecoderFactory.Builder(context).build(),Clock.DEFAULT,sourceFactory))
                    .addListener(listener).build()
                val item=EditedMediaItem.Builder(PlayerMediaItem.Builder().setUri(Uri.parse("ciel-cloud://${session.job.id}")).setMimeType(session.job.mime).build())
                    .setEffects(Effects(emptyList(),listOf(Presentation.createForWidthAndHeight(w,h,Presentation.LAYOUT_SCALE_TO_FIT)))).build()
                val tracks=if(info.audioTracks>0)setOf(C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO) else setOf(C.TRACK_TYPE_VIDEO)
                val sequence=EditedMediaItemSequence.Builder(tracks).addItem(item).build()
                val composition=Composition.Builder(sequence).setHdrMode(Composition.HDR_MODE_KEEP_HDR).build()
                transformer!!.start(composition,output.path);handler.post(watcher)
            } catch(e: Exception) { handler.removeCallbacks(watcher); fail(e) }
            continuation.invokeOnCancellation { handler.post { handler.removeCallbacks(watcher);transformer?.cancel() } }
        } }
        inspect(output).requireDerivative(info,edge)
    }
    fun preparationProblem(info:VideoInfo,edge:Int):String?=when {
        edge<=0 || edge>info.shortEdge -> "Разрешение выше исходного недоступно. Используйте оригинал."
        info.audioTracks>1 || info.videoTracks!=1 || info.otherTracks!=0 -> "Кодер не сохраняет все объявленные дорожки. Используйте оригинал."
        info.hdr -> "Подготовка HDR не поддерживается: сохранение динамических метаданных не проверено. Используйте оригинал."
        else -> null
    }
}
