package com.cielvitrine.app

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.cielvitrine.archive.ArchiveJob
import com.cielvitrine.archive.ArchivePhase
import com.cielvitrine.archive.OriginalTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private data class VideoChoice(val job: ArchiveJob? = null, val quality: VideoQuality = VideoQuality.ORIGINAL,
    val local: Boolean = false)

@Composable internal fun ProgressiveVideoContent(item: MediaItem, modifier: Modifier = Modifier) {
    key(item.uri, item.binding, item.generation, item.modifiedSeconds, item.bytes) { VideoSessionContent(item, modifier) }
}

/** One player per resource snapshot. Quality lookup never disposes the active player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun VideoSessionContent(item: MediaItem, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isCloud = item.cloudId != null || item.uri.scheme == "ciel-cloud"
    val originalId = item.cloudId ?: item.uri.host
    var original by remember { mutableStateOf<ArchiveJob?>(null) }
    var derivatives by remember { mutableStateOf<Map<VideoQuality, ArchiveJob>>(emptyMap()) }
    var selected by remember { mutableStateOf(PlaybackPreferences(context).quality) }
    var choice by remember { mutableStateOf<VideoChoice?>(if(isCloud)null else VideoChoice(local=true)) }
    var refresh by remember { mutableIntStateOf(0) }
    var waiting by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Set<VideoQuality>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var dimensions by remember { mutableStateOf("") }
    var playerLoading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var ownedSession by remember { mutableStateOf<MultipartSession?>(null) }
    var appliedChoice by remember { mutableStateOf<VideoChoice?>(null) }
    val player = remember { ExoPlayer.Builder(context).build().apply { setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT) } }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(player,owner) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_PAUSE)player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while(true) {
            if(!seeking)position=player.currentPosition.coerceAtLeast(0)
            duration=player.duration.coerceAtLeast(0);playing=player.isPlaying
            delay(200)
        }
    }
    DisposableEffect(player) {
        val listener=object:Player.Listener {
            override fun onPlayerError(error:PlaybackException) { playerLoading=false;playbackError="Ошибка воспроизведения: ${error.errorCodeName}" }
            override fun onPlaybackStateChanged(state:Int) { if(state==Player.STATE_READY || state==Player.STATE_ENDED)playerLoading=false }
            override fun onVideoSizeChanged(size:androidx.media3.common.VideoSize) { dimensions="${size.width}×${size.height}" }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener);player.release()
            ownedSession?.let { retiring -> viewCleanup.launch { retiring.close() } };ownedSession=null
        }
    }

    LaunchedEffect(selected, refresh) {
        if(!isCloud)return@LaunchedEffect
        try {
            val pair=withContext(Dispatchers.IO) {
                val source=VideoSources.original(context,requireNotNull(originalId))
                require(source.phase==ArchivePhase.VERIFIED && source.mime.startsWith("video/")) { "Видео ещё не проверено" }
                source to VideoSources.available(context,source)
            }
            original=pair.first;derivatives=pair.second
            if(selected.edge>0) {
                val savedRequest=withContext(Dispatchers.IO) { VideoViewStore(context,pair.first).load(selected) }
                if(savedRequest!=null && savedRequest.job.phase!=ArchivePhase.VERIFIED) {
                    if(savedRequest.job.deferred)message=savedRequest.job.notice
                    else pending=pending+selected
                }
            }
            // AUTO is a deterministic prepared rendition fitting the display's short edge.
            val screen=context.resources.displayMetrics.let { minOf(it.widthPixels,it.heightPixels) }
            val requested=VideoSelectionRules.resolve(selected,pair.second.keys,screen)
            val prepared=pair.second[requested]
            val source=prepared ?: withContext(Dispatchers.IO) { VideoSources.stream(context,pair.first) }
            waiting=source==null
            if(source!=null)choice=VideoChoice(source,if(prepared!=null)requested else VideoQuality.ORIGINAL)
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { waiting=false;message=e.message ?: "Видео недоступно; текущий поток сохранён" }
    }
    LaunchedEffect(waiting,refresh) {
        if(waiting) { delay(1500);refresh++ }
    }
    LaunchedEffect(pending) {
        while(pending.isNotEmpty()) {
            delay(1500)
            val source=original ?: continue
            try {
                val states=withContext(Dispatchers.IO) { pending.associateWith { VideoViewStore(context,source).load(it) } }
                val done=states.filterValues { it==null || it.job.phase==ArchivePhase.VERIFIED || it.job.deferred }.keys
                if(done.isNotEmpty()) {
                    states.values.filterNotNull().firstOrNull { it.job.deferred }?.let { message=it.job.notice }
                    pending=pending-done;refresh++
                }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { message="Не удалось проверить подготовку";break }
        }
    }

    val active=choice
    DisposableEffect(player,active,retry) {
        var session:MultipartSession?=null
        if(active!=null)try {
            val media=PlayerMediaItem.fromUri(if(active.local)item.uri else Uri.parse("ciel-cloud://${active.job!!.id}"))
            val source=if(active.local)DefaultMediaSourceFactory(LeasedLocalDataSource.Factory(context)).createMediaSource(media)
                else {
                    require(active.job!!.transport==OriginalTransport.MULTIPART)
                    session=MultipartSession(context,active.job,clearOnClose=PlaybackPreferences(context).clearAfterViewing)
                    ProgressiveMediaSource.Factory(MultipartDataSource.Factory(session!!)).createMediaSource(media)
                }
            switchVideoSource(player,source)
            val previous=ownedSession;ownedSession=session;session=null
            previous?.let { retiring -> viewCleanup.launch { retiring.close() } }
            appliedChoice=active
            playbackError=null;playerLoading=true;dimensions=""
        }catch(e:Exception) {
            session?.let { retiring -> viewCleanup.launch { retiring.close() } }
            playerLoading=false;playbackError=e.message ?: "Не удалось открыть видео"
        }
        onDispose { }
    }

    Column(modifier) {
        if(active!=null)AndroidView(modifier=Modifier.fillMaxWidth().weight(1f),
            factory={ PlayerView(it).apply { this.player=player;useController=false;keepScreenOn=true } },update={it.player=player})
        else Box(Modifier.fillMaxWidth().weight(1f)) { Text(if(waiting)"Подготавливаем проверенный поток…" else "Видео недоступно") }
        Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha=.65f)).navigationBarsPadding(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={if(player.isPlaying)player.pause() else { if(player.playbackState==Player.STATE_ENDED)player.seekTo(0);player.play() }},modifier=Modifier.testTag("video-play-pause")) {
                Icon(if(playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing)"Пауза" else "Воспроизвести")
            }
            Slider(value=position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),
                onValueChange={seeking=true;position=it.toLong()},onValueChangeFinished={player.seekTo(position);seeking=false},
                valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=duration>0,
                modifier=Modifier.weight(1f).testTag("video-seek"))
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick={menu=true}) { Icon(Icons.Outlined.Settings,"Качество видео: ${selected.label}") }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    (if(isCloud)VideoQuality.entries else listOf(VideoQuality.ORIGINAL)).forEach { quality ->
                        DropdownMenuItem(text={ Text("${quality.label} · "+when {
                            !isCloud -> "локальный оригинал"
                            quality==VideoQuality.AUTO -> "по размеру экрана"
                            quality==VideoQuality.ORIGINAL -> if(original==null)"проверяем" else "оригинал"
                            quality in derivatives -> "подготовлено"
                            else -> "не подготовлено"
                        }) },onClick={menu=false;selected=quality;PlaybackPreferences(context).quality=quality;message=null})
                    }
                }
            }
            val target=selected.takeIf { it.edge>0 }
            if(isCloud && target!=null && target !in derivatives && target !in pending)OutlinedButton(onClick={
                scope.launch {
                    try {
                        val source=original ?: error("Оригинал пока недоступен")
                        withContext(Dispatchers.IO) { VideoSources.request(context,source,target) }
                        pending=pending+target;message="Подготовка ${target.label} запущена отдельно; доступный поток остаётся открыт."
                    }catch(e:CancellationException){throw e}catch(e:Exception){message=e.message}
                }
            }) { Text("Подготовить ${target.label}") }
            if(target!=null && target in pending)TextButton(onClick={scope.launch {
                try { original?.let { source -> withContext(Dispatchers.IO) { VideoSources.cancel(context,source,target) } };pending=pending-target }
                catch(e:CancellationException){throw e}catch(e:Exception){message=e.message}
            }}) { Text("Отменить") }
        }
        val displayed=appliedChoice
        Text(if(displayed==null)"Поток ещё не открыт" else "Поток: ${if(displayed.local)"локальный оригинал" else displayed.quality.label} $dimensions",
            style=MaterialTheme.typography.bodySmall)
        if(selected.edge>0 && selected !in derivatives && isCloud)Text("${selected.label} ещё не подготовлено",style=MaterialTheme.typography.bodySmall)
        if(active!=null && playerLoading)Text("Загрузка",Modifier.testTag("media-loading"))
        playbackError?.let { Text(it,Modifier.testTag("media-error"));TextButton(onClick={retry++}){Text("Повторить")} }
        message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
    }
}

/** Replace only the media source: capture the live clock immediately before replacement. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun switchVideoSource(player:ExoPlayer,source:androidx.media3.exoplayer.source.MediaSource) {
    val state=VideoSwitchState(player.currentPosition.coerceAtLeast(0),player.playWhenReady,player.volume)
    val tracks=player.trackSelectionParameters
    val language=player.currentTracks.groups.asSequence().filter { it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO }
        .flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected)
            .mapNotNull { group.mediaTrackGroup.getFormat(it).language } }.firstOrNull()
    player.setMediaSource(source,state.positionMs)
    val explicitAudio=tracks.overrides.values.any { it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO }
    player.trackSelectionParameters=if(explicitAudio && language!=null)
        tracks.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO).setPreferredAudioLanguage(language).build() else tracks
    player.volume=state.volume;player.playWhenReady=state.playing
    player.prepare()
}
