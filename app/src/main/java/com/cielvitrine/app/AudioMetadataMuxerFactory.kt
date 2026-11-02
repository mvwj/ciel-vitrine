package com.cielvitrine.app

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.InAppMp4Muxer

/** Transformer may rebuild an encoded audio Format without its source language. Restore the
 * track annotation at mux admission; leave actual encoded channels/rate untouched and verified. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AudioMetadataMuxerFactory(private val language:String?,private val delegate:Muxer.Factory=InAppMp4Muxer.Factory()):Muxer.Factory by delegate {
    override fun create(path:String):Muxer {
        val target=delegate.create(path)
        return object:Muxer by target {
            override fun addTrack(format:Format):Int=target.addTrack(
                if(language!=null && MimeTypes.isAudio(format.sampleMimeType))format.buildUpon().setLanguage(language).build() else format)
        }
    }
}
