package com.cielvitrine.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.cielvitrine.archive.ArchivePhase
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryViewer(items:List<MediaItem>,initial:MediaItem,close:()->Unit,
    isFavorite:(MediaItem)->Boolean={false},favorite:(MediaItem)->Unit={},album:(MediaItem)->Unit={},
    changed:(MediaItem)->Unit={},resolveCloud:suspend (MediaItem)->MediaItem={it}) {
    if(items.isEmpty())return
    val initialIndex=items.indexOfFirst { it.uri==initial.uri }.coerceAtLeast(0)
    val pager=rememberPagerState(initialPage=initialIndex,pageCount={items.size})
    var zoomed by remember { mutableStateOf(false) }
    val current=items[pager.currentPage.coerceIn(items.indices)]
    LaunchedEffect(current.uri){zoomed=false;changed(current)}
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val window=(LocalView.current.parent as?DialogWindowProvider)?.window
        DisposableEffect(window){
            window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            val controller=window?.let { WindowCompat.getInsetsController(it,it.decorView) }
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            onDispose{controller?.show(WindowInsetsCompat.Type.systemBars())}
        }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("gallery-viewer")) {
            HorizontalPager(pager,userScrollEnabled=!zoomed,modifier=Modifier.fillMaxSize().testTag("viewer-pager"),key={items[it].uri.toString()}) { index ->
                val page=items[index]
                // Offscreen pages do not decode images, download originals or start video/audio.
                if(index==pager.currentPage) {
                    var resolved by remember(page.uri,page.binding,page.generation,page.modifiedSeconds,page.bytes){mutableStateOf<MediaItem?>(if(page.cloudId==null || !page.isVideo)page else null)}
                    var failed by remember(page.uri){mutableStateOf(false)}
                    var retry by remember(page.uri){mutableIntStateOf(0)}
                    LaunchedEffect(page.uri,page.cloudId,retry){if(page.cloudId!=null && page.isVideo) {
                        failed=false
                        try{resolved=resolveCloud(page)}catch(e:CancellationException){throw e}catch(_:Exception){failed=true}
                    }}
                    val media=resolved
                    if(media!=null) {
                        if(media.isVideo)VideoContent(media,Modifier.fillMaxSize())
                        else PhotoContent(media,Modifier.fillMaxSize(),zoomState={zoomed=it})
                    } else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                        if(failed)Column(horizontalAlignment=Alignment.CenterHorizontally){Text("Облачная копия не открылась",color=Color.White);TextButton(onClick={retry++}){Text("Повторить")}}
                        else Column(horizontalAlignment=Alignment.CenterHorizontally){CircularProgressIndicator();Text("Скачиваем и проверяем оригинал",color=Color.White,modifier=Modifier.padding(16.dp))}
                    }
                }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha=.55f)).statusBarsPadding().padding(end=16.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=close){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад",tint=Color.White)}
                Text("${pager.currentPage+1} / ${items.size}",color=Color.White)
            }
            if(!current.isVideo)Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha=.55f)).navigationBarsPadding().padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(current.name,Modifier.weight(1f),maxLines=1,color=Color.White,style=MaterialTheme.typography.bodySmall)
                if(current.cloudId==null) {
                    IconButton(onClick={favorite(current)}){Icon(if(isFavorite(current))Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,"Избранное",tint=Color.White)}
                    IconButton(onClick={album(current)}){Icon(Icons.Outlined.CreateNewFolder,"Добавить в альбом",tint=Color.White)}
                }
            }
        }
    }
}
