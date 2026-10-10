package com.cielvitrine.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MediaTimelineAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.systemDefault()
    private fun media(days: Int) = (0 until days).flatMap { day ->
        val time = LocalDate.of(2026, 9, 25).minusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        (0 until 6).map { cell -> val id = day * 6L + cell
            MediaItem(Uri.parse("content://ciel-timeline-test/$id"), "fixture $id", "image/jpeg", time,
                time / 1000, id, "Test", 10, 10, 10, 0)
        }
    }
    private fun show(items: List<MediaItem>, ticks: AtomicInteger = AtomicInteger()) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { ticks.incrementAndGet() }
            }) {
                MaterialTheme {
                    MediaTimeline(items, Modifier.fillMaxSize(), open = {}, thumbnail = { _, cell -> Box(cell.background(Color.DarkGray)) })
                }
            }
        }
    }
    @Test fun photosAreSquareAndTouchWhileDaysHaveASeparateCaptionAndGap() {
        val items = media(2); show(items)
        fun bounds(index: Int) = compose.onNodeWithTag("media:${items[index].uri}").fetchSemanticsNode().boundsInRoot
        val first = bounds(0); val second = bounds(1); val rowBelow = bounds(3)
        assertEquals(first.width, first.height, 1f)
        assertEquals(first.right, second.left, 1f)
        assertEquals(first.top, second.top, 1f)
        assertEquals(first.bottom, rowBelow.top, 1f)
        val nextDay = bounds(6)
        val caption = compose.onNodeWithTag("day:2026-09-24").fetchSemanticsNode().boundsInRoot
        assertTrue(nextDay.top > rowBelow.bottom)
        assertTrue(caption.top >= rowBelow.bottom && caption.bottom <= nextDay.top)
    }
    @Test fun draggingShowsTheDateTicksAndCanReachTheOldestDayBeyond240Photos() {
        val items = media(50); val ticks = AtomicInteger(); show(items, ticks)
        val rail = compose.onNodeWithTag("date-scrollbar")
        rail.performTouchInput {
            down(Offset(center.x, height * .1f))
            moveTo(Offset(center.x, height * .65f), delayMillis = 300)
        }
        compose.onNodeWithTag("scroll-date").assertIsDisplayed()
        assertTrue("A crossed date produces haptic feedback", ticks.get() > 0)
        rail.performTouchInput { up() }
        rail.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.waitForIdle()
        compose.onNodeWithTag("media:${items.last().uri}").assertIsDisplayed()
        rail.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitForIdle()
        compose.onNodeWithTag("day:2026-09-25").assertIsDisplayed()
    }
    @Test fun captureDatesTakePriorityAndFallbacksUseTheCorrectUnits() {
        assertEquals(1234567890000, galleryDate(1234567890000, 1700000000, 1800000000))
        assertEquals(1700000000000, galleryDate(null, 1700000000, 1800000000))
        assertEquals(1800000000000, galleryDate(0, 0, 1800000000))
    }
}
