package com.cielvitrine.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest

@Composable
private fun MediaFailure(retry: () -> Unit) {
    Column(Modifier.padding(24.dp).testTag("media-error"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Не удалось открыть файл", style = MaterialTheme.typography.titleMedium)
        Text("Проверьте доступ к файлу и поддержку его формата на этом телефоне. Просмотр не изменяет оригинал.", Modifier.padding(vertical = 12.dp))
        OutlinedButton(onClick = retry) { Text("Повторить") }
    }
}

private tailrec fun android.content.Context.activity(): android.app.Activity? = when(this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun PhotoContent(item: MediaItem, modifier: Modifier = Modifier, zoomState: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val localView = androidx.compose.ui.platform.LocalView.current
    val window = (localView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: context.activity()?.window
    val native = remember(context) { OriginalPhotoView(context) }
    var attempt by remember(item.uri, item.binding, item.generation, item.modifiedSeconds, item.bytes) { mutableIntStateOf(0) }
    var failed by remember(item.uri, item.binding, attempt) { mutableStateOf(false) }
    var loading by remember(item.uri, item.binding, attempt) { mutableStateOf(true) }
    var detailsBusy by remember(item.uri,attempt){mutableStateOf(false)}
    var detailsFailed by remember(item.uri,attempt){mutableStateOf(false)}
    val latestZoom by rememberUpdatedState(zoomState)
    DisposableEffect(native,window) {
        val oldColor = window?.colorMode
        onDispose { native.release(); if(oldColor!=null)window.colorMode=oldColor; latestZoom(false) }
    }
    val transform = androidx.compose.foundation.gestures.rememberTransformableState { zoom,pan,_ -> native.transform(zoom,pan.x,pan.y) }
    Box(modifier,contentAlignment=Alignment.Center) {
        AndroidView(factory={native},modifier=Modifier.fillMaxSize().testTag("photo-image")
            .transformable(transform,canPan={native.scale>1.01f})
            .pointerInput(native){detectTapGestures(onDoubleTap={native.doubleTap(it.x,it.y)})},update={view ->
            view.status={busy,error,hdr -> loading=busy;failed=error
                window?.colorMode=if(hdr)android.content.pm.ActivityInfo.COLOR_MODE_HDR else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT }
            view.detailStatus={busy,error -> detailsBusy=busy;detailsFailed=error}
            view.zoomState={latestZoom(it)};view.bind(item,attempt)
        })
        if(!loading && !failed && (detailsBusy || detailsFailed))Row(Modifier.align(Alignment.TopCenter).padding(top=64.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha=.65f)).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
            if(detailsFailed)TextButton(onClick={native.retryDetails()}){Text("Детали не подгрузились · повторить")}
            else Text("Подгружаем детали",color=androidx.compose.ui.graphics.Color.White,style=MaterialTheme.typography.bodySmall)
        }
        if(loading)CircularProgressIndicator(Modifier.testTag("media-loading"))
        if(failed) {
            if(item.cloudId!=null)Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("Не удалось прочитать фрагменты")
                Text("Дождитесь их подготовки на вкладке «Облако». Проверьте сеть и подключённый аккаунт.",Modifier.padding(vertical=12.dp))
                OutlinedButton(onClick={attempt++}){Text("Повторить")}
            } else MediaFailure { attempt++ }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoContent(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var attempt by remember(item.uri, item.binding, item.generation, item.modifiedSeconds, item.bytes) { mutableIntStateOf(0) }
    val player = remember(item.uri, item.binding, item.generation, item.modifiedSeconds, item.bytes, attempt) { ExoPlayer.Builder(context).build() }
    var failed by remember(player) { mutableStateOf(false) }
    var loading by remember(player) { mutableStateOf(true) }
    var playing by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableLongStateOf(0) }
    var duration by remember(player) { mutableLongStateOf(0) }
    var seeking by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player) { while(true) { if(!seeking)position=player.currentPosition;duration=player.duration.coerceAtLeast(0);delay(200) } }
    DisposableEffect(player, owner) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying:Boolean){playing=isPlaying}
            override fun onPlayerError(error: PlaybackException) { loading = false; failed = true }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) { loading = false; failed = false } }
            override fun onRenderedFirstFrame() { loading = false }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(observer); player.addListener(listener)
        player.setMediaItem(PlayerMediaItem.Builder().setUri(item.uri).setMimeType(item.mime).build()); player.prepare(); player.playWhenReady=true
        onDispose { owner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player;useController=false;keepScreenOn=true } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black.copy(alpha=.65f)).navigationBarsPadding().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={if(player.isPlaying)player.pause() else {if(player.playbackState==Player.STATE_ENDED)player.seekTo(0);player.play()}},modifier=Modifier.testTag("video-play-pause")) {
                Icon(if(playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing)"Пауза" else "Воспроизвести",tint=androidx.compose.ui.graphics.Color.White)
            }
            Slider(value=position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={seeking=true;position=it.toLong()},onValueChangeFinished={player.seekTo(position);seeking=false},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=duration>0,modifier=Modifier.weight(1f).testTag("video-seek"))
        }
        if (loading) CircularProgressIndicator(Modifier.testTag("media-loading"))
        if (failed) MediaFailure { attempt++ }
    }
}
