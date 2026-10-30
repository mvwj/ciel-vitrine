package com.cielvitrine.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun ProgressivePhotoContent(item: MediaItem, modifier: Modifier = Modifier, zoomState: (Boolean) -> Unit = {}) {
    var loading by remember(item.uri, item.binding, item.generation) { mutableStateOf(true) }
    var failed by remember(item.uri, item.binding, item.generation) { mutableStateOf(false) }
    var refining by remember(item.uri, item.binding, item.generation) { mutableStateOf(false) }
    var refinementFailed by remember(item.uri, item.binding, item.generation) { mutableStateOf(false) }
    var attempt by remember(item.uri, item.binding, item.generation) { mutableIntStateOf(0) }
    var native by remember { mutableStateOf<OriginalPhotoView?>(null) }
    val context=LocalContext.current
    val view=LocalView.current
    val window=(view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: context.viewerActivity()?.window
    DisposableEffect(window) {
        val previous=window?.colorMode
        onDispose { if(previous!=null)window.colorMode=previous;zoomState(false) }
    }
    val reportHdr:(Boolean)->Unit={hdr -> window?.colorMode=if(hdr)android.content.pm.ActivityInfo.COLOR_MODE_HDR else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT}
    Box(modifier.fillMaxSize()
        .pointerInput(item.uri, item.binding, item.generation) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false)
                var transforming=false
                do {
                    val event=awaitPointerEvent()
                    if(event.changes.any { it.isConsumed })break
                    val zoom=event.calculateZoom()
                    val pan=event.calculatePan()
                    // Single-finger swipes at fit size belong to the gallery pager.
                    if(event.changes.count{it.pressed}>=2 || (native?.scale ?: 1f)>1.01f)transforming=true
                    if(transforming) {
                        val centroid=event.calculateCentroid(useCurrent=false)
                        if(centroid!=androidx.compose.ui.geometry.Offset.Unspecified)native?.transform(zoom,pan.x,pan.y,centroid.x,centroid.y)
                        event.changes.filter{it.positionChanged()}.forEach{it.consume()}
                    }
                }while(event.changes.any{it.pressed})
            }
        }
        .pointerInput(item.uri, item.binding, item.generation) {
            detectTapGestures(onDoubleTap = { native?.doubleTap(it.x, it.y) })
        }) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("photo-image"),
            factory = { context -> OriginalPhotoView(context).also { view ->
                native = view
                view.status = { isLoading, error, hdr -> loading = isLoading; failed = error;reportHdr(hdr) }
                view.detailStatus = { isLoading, error -> refining = isLoading; refinementFailed = error }
                view.zoomState = zoomState
                view.bind(item, attempt)
            } },
            update = { view ->
                native = view
                view.status = { isLoading, error, hdr -> loading = isLoading; failed = error;reportHdr(hdr) }
                view.detailStatus = { isLoading, error -> refining = isLoading; refinementFailed = error }
                view.zoomState = zoomState
                view.bind(item, attempt)
            },
            onRelease = { it.release(); if (native === it) native = null }
        )
        if (loading) Text("Загрузка", Modifier.align(Alignment.Center).testTag("media-loading"))
        if (failed) Text("Не удалось открыть изображение", Modifier.align(Alignment.Center).testTag("media-error"))
        else if (refinementFailed) Text("Не удалось уточнить изображение", Modifier.align(Alignment.BottomCenter).testTag("photo-detail-error"))
        else if (refining) Text("Уточнение", Modifier.align(Alignment.BottomCenter).testTag("photo-refining"))
        if(failed || refinementFailed)TextButton(onClick={if(failed)attempt++ else native?.retryDetails()},modifier=Modifier.align(Alignment.BottomEnd)) { Text("Повторить") }
    }
}

private tailrec fun android.content.Context.viewerActivity():android.app.Activity?=when(this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.viewerActivity()
    else -> null
}
