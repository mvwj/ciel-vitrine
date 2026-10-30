package com.cielvitrine.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Stable gallery entry points. Presentation is implemented in the assigned photo/video paths. */
@Composable
internal fun PhotoContent(item: MediaItem, modifier: Modifier = Modifier, zoomState: (Boolean) -> Unit = {}) {
    ProgressivePhotoContent(item, modifier, zoomState)
}

@Composable
internal fun VideoContent(item: MediaItem, modifier: Modifier = Modifier) {
    ProgressiveVideoContent(item, modifier)
}
