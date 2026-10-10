package com.cielvitrine.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class TimelineDay(val date: LocalDate, val members: List<MediaItem>, val headerIndex: Int)

/** A full-width, zero-gap grid. Only the day headers separate its rows. */
@Composable
internal fun MediaTimeline(
    media: List<MediaItem>, modifier: Modifier = Modifier, open: (MediaItem) -> Unit,
    thumbnail: @Composable (MediaItem, Modifier) -> Unit = { item, cell -> MediaThumb(item, cell) },
) {
    val zone = ZoneId.systemDefault()
    val days = remember(media, zone) {
        var index = 1 // The count occupies the first grid item.
        media.groupBy { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }.map { (date, members) ->
            TimelineDay(date, members, index).also { index += members.size + 1 }
        }
    }
    if (days.isEmpty()) return
    val grid = rememberLazyGridState()
    val formatter = remember { DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru")) }
    val itemCount = media.size + days.size + 1
    fun dayAt(index: Int): TimelineDay {
        val found = days.binarySearch { it.headerIndex.compareTo(index) }
        return days[(if (found >= 0) found else -found - 2).coerceIn(days.indices)]
    }
    val haptic = LocalHapticFeedback.current
    var dragging by remember(media) { mutableStateOf(false) }
    var dragFraction by remember(media) { mutableFloatStateOf(0f) }
    var requestedIndex by remember(media) { mutableStateOf<Int?>(null) }
    var lastTickDay by remember(media) { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(requestedIndex) { requestedIndex?.let { grid.scrollToItem(it) } }

    val fraction by remember {
        derivedStateOf {
            if (!grid.canScrollForward && grid.canScrollBackward) 1f
            else grid.firstVisibleItemIndex.toFloat() / (grid.layoutInfo.totalItemsCount - grid.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)
        }
    }
    val progress = if (dragging) dragFraction else fraction.coerceIn(0f, 1f)
    val visibleDay = dayAt(if (dragging) requestedIndex ?: grid.firstVisibleItemIndex else grid.firstVisibleItemIndex)
    val label = visibleDay.date.format(formatter)
    fun seek(value: Float) {
        dragFraction = value.coerceIn(0f, 1f)
        val index = ((itemCount - 1) * dragFraction).roundToInt().coerceIn(0, itemCount - 1)
        requestedIndex = index
        val day = dayAt(index).date
        if (lastTickDay != day) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastTickDay = day
        }
    }
    val latestSeek by rememberUpdatedState<(Float) -> Unit>({ seek(it) })

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val thumbHeight = 40.dp
        val top = with(density) { 16.dp.toPx() }
        val thumbPixels = with(density) { thumbHeight.toPx() }
        val travel = (with(density) { maxHeight.toPx() } - 2 * top - thumbPixels).coerceAtLeast(1f)
        val thumbTop = top + travel * progress
        LazyVerticalGrid(
            columns = GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize().testTag("media-timeline"),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "count", contentType = "count", span = { GridItemSpan(maxLineSpan) }) {
                Text("${media.size} фото и видео", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            days.forEach { day ->
                item(key = "day:${day.date}", contentType = "day", span = { GridItemSpan(maxLineSpan) }) {
                    Text(day.date.format(formatter), Modifier.testTag("day:${day.date}").fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.titleMedium)
                }
                items(day.members, key = { it.uri.toString() }, contentType = { "media" }) { item ->
                    thumbnail(item, Modifier.testTag("media:${item.uri}").aspectRatio(1f).clickable { open(item) })
                }
            }
        }
        if (grid.canScrollBackward || grid.canScrollForward) {
            if (dragging || grid.isScrollInProgress) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 46.dp)
                        .offset { IntOffset(0, thumbTop.roundToInt()) }.testTag("scroll-date"),
                    shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary, shadowElevation = 3.dp,
                ) { Text(label, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge) }
            }
            Box(Modifier.align(Alignment.CenterEnd).width(48.dp).fillMaxHeight().testTag("date-scrollbar")
                .semantics {
                    contentDescription = "Прокрутка по датам: $label"
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    setProgress { latestSeek(it); true }
                }
                .pointerInput(days, travel, top, thumbPixels) {
                    fun at(y: Float) = latestSeek((y - top - thumbPixels / 2) / travel)
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; lastTickDay = dayAt(grid.firstVisibleItemIndex).date; at(it.y) },
                        onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ -> change.consume(); at(change.position.y) },
                    )
                }) {
                Box(Modifier.align(Alignment.TopEnd).padding(end = 9.dp).offset { IntOffset(0, top.roundToInt()) }
                    .width(2.dp).height(with(density) { (travel + thumbPixels).toDp() })
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .16f)))
                Box(Modifier.align(Alignment.TopEnd).padding(end = 6.dp).offset { IntOffset(0, thumbTop.roundToInt()) }
                    .width(8.dp).height(thumbHeight).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
            }
        }
    }
}
