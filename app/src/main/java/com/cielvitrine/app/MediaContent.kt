package com.cielvitrine.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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

@Composable
internal fun PhotoContent(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = remember(context) { ImageLoader.Builder(context).diskCache(null).components { add(AnimatedImageDecoder.Factory()) }.build() }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    var attempt by remember(item.uri, item.generation, item.modifiedSeconds, item.bytes) { mutableIntStateOf(0) }
    var failed by remember(item.uri, item.generation, item.modifiedSeconds, item.bytes, attempt) { mutableStateOf(false) }
    var loading by remember(item.uri, item.generation, item.modifiedSeconds, item.bytes, attempt) { mutableStateOf(true) }
    Box(modifier, contentAlignment = Alignment.Center) {
        key(item.uri, item.generation, item.modifiedSeconds, item.bytes, attempt) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(item.uri).diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build(),
                imageLoader = loader, contentDescription = item.name,
                modifier = Modifier.fillMaxSize().testTag("photo-image"), contentScale = ContentScale.Fit,
                onLoading = { loading = true }, onSuccess = { loading = false; failed = false },
                onError = { loading = false; failed = true },
            )
        }
        if (loading) CircularProgressIndicator(Modifier.testTag("media-loading"))
        if (failed) MediaFailure { attempt++ }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoContent(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var attempt by remember(item.uri, item.generation, item.modifiedSeconds, item.bytes) { mutableIntStateOf(0) }
    val player = remember(item.uri, item.generation, item.modifiedSeconds, item.bytes, attempt) { ExoPlayer.Builder(context).build() }
    var failed by remember(player) { mutableStateOf(false) }
    var loading by remember(player) { mutableStateOf(true) }
    DisposableEffect(player, owner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { loading = false; failed = true }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) { loading = false; failed = false } }
            override fun onRenderedFirstFrame() { loading = false }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(observer); player.addListener(listener)
        player.setMediaItem(PlayerMediaItem.fromUri(item.uri)); player.prepare()
        onDispose { owner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
        if (loading) CircularProgressIndicator(Modifier.testTag("media-loading"))
        if (failed) MediaFailure { attempt++ }
    }
}
