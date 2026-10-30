package com.cielvitrine.app

import android.net.Uri
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Requires the existing generated safety corpus and a real Android decoder; unrun on Linux. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoSwitchAndroidTest {
    @Test fun preparedSourceReplacementKeepsPausedClockVolumeAudioPolicyAndPlayState() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val directory=File(context.cacheDir,"video-switch-${ArchiveStore.randomId()}").apply{mkdir()}
        val first=File(directory,"original.mp4");val second=File(directory,"prepared.mp4")
        instrument.context.assets.open("safety-corpus/h264.mp4").use{input->first.outputStream().use{input.copyTo(it)}}
        first.copyTo(second)
        lateinit var player:ExoPlayer
        fun ready() {
            val deadline=System.nanoTime()+10_000_000_000L;var ready=false
            while(!ready && System.nanoTime()<deadline) {
                instrument.runOnMainSync { assertNull(player.playerError);ready=player.playbackState==Player.STATE_READY }
                if(!ready)Thread.sleep(10)
            }
            assertTrue("Decoder did not become ready",ready)
        }
        try {
            instrument.runOnMainSync {
                player=ExoPlayer.Builder(context).build()
                player.setMediaItem(PlayerMediaItem.fromUri(Uri.fromFile(first)));player.prepare()
            }
            ready()
            instrument.runOnMainSync {player.pause();player.seekTo(437);player.volume=.37f;player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("en").build()}
            ready()
            var before=0L
            instrument.runOnMainSync {
                before=player.currentPosition
                val source=DefaultMediaSourceFactory(LeasedLocalDataSource.Factory(context)).createMediaSource(PlayerMediaItem.fromUri(Uri.fromFile(second)))
                switchVideoSource(player,source)
            }
            ready()
            instrument.runOnMainSync {
                assertFalse(player.playWhenReady);assertEquals(.37f,player.volume,0f)
                assertEquals(listOf("en"),player.trackSelectionParameters.preferredAudioLanguages)
                assertTrue(kotlin.math.abs(player.currentPosition-before)<=250)
                player.play()
                switchVideoSource(player,DefaultMediaSourceFactory(LeasedLocalDataSource.Factory(context)).createMediaSource(PlayerMediaItem.fromUri(Uri.fromFile(first))))
                assertTrue(player.playWhenReady);assertEquals(.37f,player.volume,0f)
            }
            ready()
        }finally{instrument.runOnMainSync{player.release()};directory.deleteRecursively()}
    }
}
