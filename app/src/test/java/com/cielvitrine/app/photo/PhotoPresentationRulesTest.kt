package com.cielvitrine.app

import com.cielvitrine.archive.viewer.ByteBudget
import org.junit.Assert.*
import org.junit.Test

class PhotoPresentationRulesTest {
    @Test fun orientationSwapsViewportAxesForAllExifOrientations() {
        // 12000x8000 fits at 1/16 in both cases; it cannot distinguish an axis swap.
        (1..4).forEach { assertEquals(16, PhotoPresentationRules.sampleFor(16000, 8000, 1000, 500, it)) }
        (5..8).forEach { assertEquals(32, PhotoPresentationRules.sampleFor(16000, 8000, 1000, 500, it)) }
        (1..8).forEach { assertEquals(16, PhotoPresentationRules.sampleFor(12000, 8000, 1000, 500, it)) }
    }

    @Test fun exifCornersIncludeBothMirrorsAndBothQuarterTurns() {
        val expected = listOf(0f to 0f, 300f to 0f, 300f to 200f, 0f to 200f,
            0f to 0f, 200f to 0f, 200f to 300f, 0f to 300f)
        (1..8).forEach { assertEquals(expected[it-1], PhotoPresentationRules.orientedPoint(0f,0f,300,200,it)) }
        assertEquals(170f to 40f, PhotoPresentationRules.orientedPoint(40f,30f,300,200,6))
        assertEquals(170f to 260f, PhotoPresentationRules.orientedPoint(40f,30f,300,200,7))
    }

    @Test fun previewSampleRoundsUpForMemoryEvenOnHugeViewport() {
        for ((w,h) in listOf(32768 to 7629, 12000 to 8000, 8001 to 8001, 1 to 32768)) {
            val sample = PhotoPresentationRules.sampleFor(w,h,32768,32768,1)
            assertTrue(PhotoPresentationRules.pixels(w,h,sample) <= PhotoPresentationRules.MAX_DECODE_PIXELS)
        }
    }

    @Test fun offCentreZoomPreservesFocusWithoutCumulativeDrift() {
        val moved = PhotoPresentationRules.focalOffset(30f,100f,2f,0f)
        assertEquals(-40f,moved,0.0001f)
        assertEquals(30f,PhotoPresentationRules.focalOffset(moved,100f,.5f,0f),0.0001f)
        assertEquals(-33f,PhotoPresentationRules.focalOffset(30f,100f,2f,7f),0.0001f)
    }

    @Test fun largeDetailRequestsChooseABoundedLevelWithoutReleasingThePreview() {
        val budget=ByteBudget(64L*1024*1024)
        budget.reserve(24L*1024*1024).use {
            val sample=PhotoPresentationRules.boundedDetailSample(8001,6001,1,budget.maximum-budget.used())
            assertEquals(8,sample)
            val bytes=PhotoPresentationRules.pixels(8001,6001,sample)*16
            budget.reserve(bytes).use { assertTrue(budget.used()<=budget.maximum) }
            assertEquals(24L*1024*1024,budget.used())
        }
    }

    @Test fun newerViewportInvalidatesOlderWork() {
        val generation = PhotoGeneration()
        val old = generation.advance()
        val fresh = generation.advance()
        assertFalse(generation.isCurrent(old))
        assertTrue(generation.isCurrent(fresh))
    }

    @Test fun failedDetailAdmissionLeavesPreviewReservationHeldAndBounded() {
        val budget = ByteBudget(64L * 1024 * 1024)
        val preview = budget.reserve(8L * 1024 * 1024)
        try {
            try {
                budget.reserve(57L * 1024 * 1024)
                fail("detail should not overcommit the resident preview")
            } catch (_: IllegalStateException) { }
            assertEquals(8L * 1024 * 1024, budget.used())
        } finally { preview.close() }
        assertEquals(0L, budget.used())
    }
}
