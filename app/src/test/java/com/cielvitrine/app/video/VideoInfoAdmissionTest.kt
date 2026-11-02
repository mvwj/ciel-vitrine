package com.cielvitrine.app

import org.junit.Assert.assertThrows
import org.junit.Assert.*
import org.junit.Test

class VideoInfoAdmissionTest {
    private val source = VideoInfo(3840, 2160, 10_000_000, 1, 3, 1, 2,
        null, "video/avc", 30f, 0, 1)

    @Test fun acceptsPreparedDerivativeWithoutUpscalingAndWithMatchingTimeline() {
        VideoInfo(1920, 1080, 10_050_000, 1, 3, 1, 2,
            null, "video/avc", 30f, 0, 1).requireDerivative(source, 1080)
    }

    @Test fun rejectsUpscalingAndChangedDeclaredTracks() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoInfo(7680, 4320, 10_000_000, 1, 3, 1, 2,
                null, "video/avc", 30f, 0, 1).requireDerivative(source, 2160)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoInfo(1920, 1080, 10_000_000, 0, 3, 1, 2,
                null, "video/avc", 30f, 0, 1).requireDerivative(source, 1080)
        }
    }

    @Test fun rejectsChangedFrameRateAndOrientation() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoInfo(1920, 1080, 10_000_000, 1, 3, 1, 2,
                null, "video/avc", 24f, 0, 1).requireDerivative(source, 1080)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoInfo(1920, 1080, 10_000_000, 1, 3, 1, 2,
                null, "video/avc", 30f, 90, 1).requireDerivative(source, 1080)
        }
    }

    @Test fun rejectsLostMetadataTracksAudioLayoutAndUnknownFrameRate() {
        val derivative=source.copy(width=1920,height=1080)
        assertThrows(IllegalArgumentException::class.java) { derivative.requireDerivative(source.copy(otherTracks=1),1080) }
        assertThrows(IllegalArgumentException::class.java) { derivative.requireDerivative(source.copy(audioFacts=listOf("en:6:48000")),1080) }
        assertThrows(IllegalArgumentException::class.java) { derivative.copy(frameRate=-1f).requireDerivative(source,1080) }
    }
    @Test fun rejectsHdrLossAndTimelineOriginShiftButAllowsBakedRotation() {
        val derivative=source.copy(width=1920,height=1080)
        assertThrows(IllegalArgumentException::class.java) { derivative.requireDerivative(source.copy(transfer=6),1080) }
        assertThrows(IllegalArgumentException::class.java) { derivative.copy(firstSamplesUs=listOf(251000)).requireDerivative(source.copy(firstSamplesUs=listOf(0)),1080) }
        derivative.requireDerivative(source.copy(rotation=90),1080)
    }
    @Test fun preparationExplainsUnsupportedHdrAdditionalTracksAndUpscalingBeforeEncoding() {
        assertNull(VideoTranscoder.preparationProblem(source,1080))
        assertNotNull(VideoTranscoder.preparationProblem(source.copy(transfer=6),1080))
        assertNotNull(VideoTranscoder.preparationProblem(source.copy(codec="video/dolby-vision"),1080))
        assertNotNull(VideoTranscoder.preparationProblem(source.copy(audioTracks=2),1080))
        assertNotNull(VideoTranscoder.preparationProblem(source.copy(otherTracks=1),1080))
        assertNotNull(VideoTranscoder.preparationProblem(source,4320))
    }
    @Test fun matchesTrackIdentityAcrossMuxOrderAndAccountsForDeclaredAacPriming() {
        val original=source.copy(firstSamplesUs=listOf(49000,0),trackTiming=listOf(
            VideoTrackTiming("video/avc",49000),VideoTrackTiming("audio/mpeg",0,sampleRate=48000)))
        val converted=original.copy(width=1920,height=1080,firstSamplesUs=listOf(-12000,0),trackTiming=listOf(
            VideoTrackTiming("audio/mp4a-latm",-12000,1600,sampleRate=48000),VideoTrackTiming("video/avc",0)))
        converted.requireDerivative(original,1080)
        assertThrows(IllegalArgumentException::class.java){converted.copy(trackTiming=converted.trackTiming.map{if(it.mime.startsWith("audio/"))it.copy(encoderDelayFrames=0) else it}).requireDerivative(original,1080)}
        assertThrows(IllegalArgumentException::class.java){converted.copy(trackTiming=converted.trackTiming.map{if(it.mime.startsWith("audio/"))it.copy(firstUs=1_000_000) else it}).requireDerivative(original,1080)}
        assertThrows(IllegalArgumentException::class.java){converted.copy(trackTiming=converted.trackTiming.drop(1)).requireDerivative(original,1080)}
    }
}
