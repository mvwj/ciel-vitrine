package com.cielvitrine.app

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioMetadataMuxerAndroidTest {
    @Test fun languageSurvivesActualMp4MuxingWhileAacPacketsChannelsAndRateStayExact() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val directory=File(context.cacheDir,"audio-metadata-${ArchiveStore.randomId()}").apply{mkdir()}
        try {
            val source=File(directory,"source.m4a")
            instrument.context.assets.open("safety-corpus/muxer-audio.m4a").use{input->source.outputStream().use{input.copyTo(it)}}
            for(language in listOf("fra","und")) {
                val extractor=MediaExtractor();val target=File(directory,"$language.mp4")
                val originalHash=MessageDigest.getInstance("SHA-256")
                var channels=0;var rate=0
                try {
                    extractor.setDataSource(source.path);extractor.selectTrack(0)
                    val media=extractor.getTrackFormat(0);channels=media.getInteger(MediaFormat.KEY_CHANNEL_COUNT);rate=media.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val csd=media.getByteBuffer("csd-0")!!.duplicate().let{ByteArray(it.remaining()).also(it::get)}
                    // A re-encoded Format can omit its language. Preserve only that annotation;
                    // feed real AAC packets and the real encoder description through the muxer.
                    val format=Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(channels)
                        .setSampleRate(rate).setInitializationData(listOf(csd)).build()
                    AudioMetadataMuxerFactory(language).create(target.path).use { muxer ->
                        val track=muxer.addTrack(format);val buffer=ByteBuffer.allocate(65536)
                        while(true) {
                            buffer.clear();val n=extractor.readSampleData(buffer,0);if(n<0)break
                            val bytes=ByteArray(n);buffer.position(0);buffer.get(bytes);originalHash.update(bytes)
                            buffer.position(0);buffer.limit(n)
                            muxer.writeSampleData(track,buffer,BufferInfo(extractor.sampleTime,n,extractor.sampleFlags))
                            extractor.advance()
                        }
                    }
                } finally{extractor.release()}
                val actual=MediaExtractor();val restoredHash=MessageDigest.getInstance("SHA-256")
                try {
                    actual.setDataSource(target.path);assertEquals(1,actual.trackCount);actual.selectTrack(0)
                    val media=actual.getTrackFormat(0)
                    assertEquals(language,media.getString(MediaFormat.KEY_LANGUAGE))
                    assertEquals(channels,media.getInteger(MediaFormat.KEY_CHANNEL_COUNT));assertEquals(rate,media.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    val buffer=ByteBuffer.allocate(65536)
                    while(true){buffer.clear();val n=actual.readSampleData(buffer,0);if(n<0)break
                        val bytes=ByteArray(n);buffer.position(0);buffer.get(bytes);restoredHash.update(bytes);actual.advance()}
                    assertArrayEquals(originalHash.digest(),restoredHash.digest())
                }finally{actual.release()}
            }
        }finally{directory.deleteRecursively()}
    }
}
