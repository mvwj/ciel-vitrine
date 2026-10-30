package com.cielvitrine.app

import org.junit.Assert.*
import org.junit.Test

class VideoSelectionRulesTest {
    private val available=setOf(VideoQuality.P1080,VideoQuality.P2160)
    @Test fun autoUsesTheActualDisplayShortEdgeAndOnlyPreparedVersions() {
        assertEquals(VideoQuality.P1080,VideoSelectionRules.resolve(VideoQuality.AUTO,available,1440))
        assertEquals(VideoQuality.P2160,VideoSelectionRules.resolve(VideoQuality.AUTO,available,2160))
        assertEquals(VideoQuality.ORIGINAL,VideoSelectionRules.resolve(VideoQuality.AUTO,available,720))
        assertEquals(VideoQuality.ORIGINAL,VideoSelectionRules.resolve(VideoQuality.P1440,available,2160))
        assertEquals(VideoQuality.P2160,VideoSelectionRules.resolve(VideoQuality.P2160,available,720))
    }
}
