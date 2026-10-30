package com.cielvitrine.app

internal object VideoSelectionRules {
    fun resolve(requested:VideoQuality,available:Set<VideoQuality>,screenEdge:Int):VideoQuality = when(requested) {
        VideoQuality.AUTO -> available.filter { it.edge>0 && it.edge<=screenEdge }.maxByOrNull { it.edge } ?: VideoQuality.ORIGINAL
        VideoQuality.ORIGINAL -> VideoQuality.ORIGINAL
        else -> requested.takeIf { it in available } ?: VideoQuality.ORIGINAL
    }
}

/** Capture once at replacement, always in the original timeline; no rounding or offset addition. */
internal data class VideoSwitchState(val positionMs:Long,val playing:Boolean,val volume:Float)
