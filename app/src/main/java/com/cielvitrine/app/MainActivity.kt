package com.cielvitrine.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthSdk
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val Ink = Color(0xFFE5ECEB)
private val Paper = Color(0xFF11181C)
private val Mist = Color(0xFF27343A)
private val Accent = Color(0xFFA1CEC3)
private val Card = Color(0xFF1C272D)
internal val GalleryColors = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF10382F),
    secondary = Accent, onSecondary = Color(0xFF10382F),
    background = Paper, surface = Paper, onSurface = Ink, onBackground = Ink,
    surfaceVariant = Mist, onSurfaceVariant = Color(0xFFBBCACB),
    secondaryContainer = Mist, onSecondaryContainer = Ink,
    surfaceContainer = Card, surfaceContainerLow = Card,
)
private val russian = Locale.forLanguageTag("ru")
private fun dateLabel(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM yyyy", russian))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = GalleryColors) {
                GalleryApp()
            }
        }
    }
}

private enum class Destination(val title: String, val icon: ImageVector) {
    PHOTOS("Фото", Icons.Outlined.PhotoLibrary), ALBUMS("Альбомы", Icons.Outlined.CollectionsBookmark),
    CLOUD("Облако", Icons.Outlined.CloudQueue), SETTINGS("Настройки", Icons.Outlined.Tune)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryApp(model: GalleryModel = viewModel(), cloudModel: YandexCloudModel = viewModel(), archiveModel: ArchiveModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val yandex by cloudModel.state.collectAsStateWithLifecycle()
    val archive by archiveModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val authSdk = remember { YandexAuthSdk.create(YandexAuthOptions(context)) }
    val yandexLogin = rememberLauncherForActivityResult(authSdk.contract) { result ->
        YandexAuthResultBridge.deliver(result, cloudModel)
    }
    fun signInYandex() {
        if (!cloudModel.beginLogin()) return
        model.chooseCloud("Яндекс Диск")
        try { yandexLogin.launch(YandexAuthLoginOptions()) } catch (e: Exception) { cloudModel.loginFailed() }
    }
    var destination by rememberSaveable { mutableStateOf(Destination.PHOTOS) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var albumFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var favoriteFilter by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MediaItem?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(listOf<String>()) }
    var pendingSelected by rememberSaveable { mutableStateOf(false) }
    val library=remember(state.media,archive.remoteMedia){(state.media+archive.remoteMedia).sortedByDescending{it.date}}
    var saveMode by rememberSaveable { mutableStateOf(SaveMode.KEEP_LOCAL) }
    var saveDialog by remember { mutableStateOf(false) }
    var completeSet by rememberSaveable { mutableStateOf(false) }
    var cloudDetail by remember { mutableStateOf<MediaCloudStatus?>(null) }
    var releaseIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var autoReleaseAttempted by rememberSaveable { mutableStateOf(listOf<String>()) }
    val selectionItems=remember(library,selection){library.filter{it.binding!=null && it.binding in selection}}
    val selectionLocalItems=selectionItems.filter{it.cloudId==null}
    fun selectItem(item:MediaItem){item.binding?.let { key -> selectionMode=true;selection=if(key in selection)selection-key else selection+key }}
    var addAlbum by remember { mutableStateOf(false) }
    var providerDialog by remember { mutableStateOf(false) }
    var archiveInfo by remember { mutableStateOf(false) }
    var coldInfo by remember { mutableStateOf(false) }
    var allInfo by remember { mutableStateOf(false) }
    var groupInfo by remember { mutableStateOf(false) }
    var coldSelection by remember { mutableStateOf(setOf<String>()) }
    var pendingAll by rememberSaveable { mutableStateOf(false) }
    val originalPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (pendingAll) { pendingAll = false; if(granted)archiveModel.prepareAll() else model.message("Для архива оригинала нужен доступ к исходным метаданным") }
        if (pendingSelected) { pendingSelected=false
            if(granted && selectionItems.size==selection.size && selectionLocalItems.isNotEmpty())archiveModel.protectSelected(selectionLocalItems,saveMode,completeSet)
            else model.message("Не все выбранные оригиналы доступны с исходными метаданными. Выбор сохранён.")
        }
    }
    val selectColdFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) archiveModel.add(uri) }
    var pendingSource by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingContainerHash by rememberSaveable { mutableStateOf<String?>(null) }
    var restore by rememberSaveable { mutableStateOf(false) }
    val fileScope = rememberCoroutineScope()
    val removeLocal = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        archiveModel.releaseResult(releaseIds,result.resultCode==android.app.Activity.RESULT_OK)
        releaseIds=emptyList();model.refresh()
    }
    fun releaseLocal(ids:List<String>) {
        fileScope.launch {
            try {
                val request=archiveModel.prepareRelease(ids)
                releaseIds=ids
                removeLocal.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(error:Exception){model.message(if(error is ReleaseNotReady)error.message else "Освобождение места остановлено: не все проверки пройдены. Оригиналы на телефоне сохранены.")}
        }
    }
    val createFile = rememberLauncherForActivityResult(OriginalDocumentContract()) { uri ->
        val source = pendingSource
        if (uri != null && source != null) model.portableFile(android.net.Uri.parse(source), uri, restore, pendingContainerHash)
        pendingSource = null
        pendingContainerHash = null
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingSource = null
        pendingContainerHash = null
        if (uri != null) fileScope.launch {
            try {
                val checked = if (restore) model.inspectRecovery(uri) else null
                pendingSource = uri.toString()
                pendingContainerHash = checked?.containerSha256
                createFile.launch(checked?.let { OriginalDocument(it.name, it.mime) }
                    ?: OriginalDocument("original.bin", "application/octet-stream"))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                pendingSource = null; pendingContainerHash = null
                model.message("Не удалось подготовить файл: ${e.localizedMessage}")
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    fun requestMedia() = permissions.launch(if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    val owner = LocalLifecycleOwner.current
    val lifecycleState by owner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(archive.plans,lifecycleState,archive.busy,releaseIds) {
        if(lifecycleState==Lifecycle.State.RESUMED && !archive.busy && releaseIds.isEmpty()) {
            val ready=archive.plans.filter{it.stage==ProtectionStage.READY && it.checkedAt==null && it.coldId !in autoReleaseAttempted && ProtectionRules.setReady(it,archive.plans)}.take(32)
            if(ready.isNotEmpty()) {
                val ids=ready.map{it.coldId}
                autoReleaseAttempted=(autoReleaseAttempted+ids).takeLast(240)
                releaseLocal(ids)
            }
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { model.refresh() }
    LaunchedEffect(state.media) { archiveModel.libraryChanged(state.media) }
    LaunchedEffect(state.media, state.loading) {
        val shown = selected
        if (!state.loading && shown?.binding != null && shown.cloudId==null) {
            val current = state.media.firstOrNull { it.binding == shown.binding }
            if (current == null) {
                selected = null
                model.message("Снимок изменился или доступ закрыт. Откройте его заново.")
            } else selected = current
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); model.message(null) }
    }
    LaunchedEffect(archive.message) { archive.message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); archiveModel.clearMessage() } }
    LaunchedEffect(yandex.message) { yandex.message?.let { model.message(it); cloudModel.clearMessage() } }
    LaunchedEffect(yandex.connection, yandex.account?.uid) { if (yandex.account != null && state.cloud?.provider != "Яндекс Диск") model.chooseCloud("Яндекс Диск")
        if (yandex.connection == YandexConnection.CONNECTED) archiveModel.accountConnected() }
    val filtered = remember(library, state.favorites, state.albums, folder, albumFilter, favoriteFilter) {
        library.filter { item -> (folder == null || item.folder == folder) && (!favoriteFilter || state.favorites.containsMedia(item)) &&
            (albumFilter == null || (state.albums.firstOrNull { it.name == albumFilter }?.members ?: emptySet()).containsMedia(item)) }
    }
    BackHandler(selectionMode || folder != null || albumFilter != null || favoriteFilter) {
        if(selectionMode){selectionMode=false;selection=emptyList()}else{folder=null;albumFilter=null;favoriteFilter=false}
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        NavigationBar(containerColor = Paper, tonalElevation = 0.dp) {
            Destination.entries.forEach { tab -> NavigationBarItem(selected = destination == tab, onClick = {
                destination = tab; folder = null; albumFilter = null; favoriteFilter = false
            }, icon = { Icon(tab.icon, null) }, label = { Text(tab.title) }) }
        }
    }) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CIEL VITRINE", style = MaterialTheme.typography.labelSmall, color = Accent, letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp))
                    Text(when (destination) { Destination.PHOTOS -> folder ?: albumFilter ?: if (favoriteFilter) "Избранное" else "Медиатека"
                        Destination.ALBUMS -> "Ваши коллекции"; Destination.CLOUD -> "Облачное хранилище"; Destination.SETTINGS -> "Настройки" },
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = { when (destination) { Destination.ALBUMS -> addAlbum = true; Destination.CLOUD -> cloudModel.recheck(); else -> model.refresh() } }, modifier = Modifier.background(Mist, CircleShape)) {
                    Icon(if (destination == Destination.ALBUMS) Icons.Outlined.Add else Icons.Outlined.Refresh, "Обновить или создать альбом")
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
            if(archive.checkingRelease)Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Проверяем обе облачные копии перед освобождением места. Оригиналы пока на телефоне.",style=MaterialTheme.typography.bodySmall)
            }
            when (destination) {
                Destination.PHOTOS -> {
                    CloudActivityCard(archive,yandex){destination=Destination.CLOUD}
                    if (state.access != MediaAccess.FULL) InfoCard(
                        if (state.access == MediaAccess.NONE) "Откройте свою медиатеку" else "Доступна часть медиатеки",
                        if (state.access == MediaAccess.NONE) "Фото и видео остаются на телефоне. Вы сами выбираете, к чему дать доступ." else "Отсутствие доступа не означает, что файл удалён.",
                        Icons.Outlined.PhotoLibrary, "Выбрать фото и видео", { requestMedia() })
                    if (library.isNotEmpty()) Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !favoriteFilter, onClick = { favoriteFilter = false }, label = { Text("Все") })
                        FilterChip(selected = favoriteFilter, onClick = { favoriteFilter = !favoriteFilter }, label = { Text("Избранное") }, leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(16.dp)) })
                    }
                    if(library.isNotEmpty())Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={selectionMode=!selectionMode;if(!selectionMode)selection=emptyList()}){Text(if(selectionMode)"Готово · ${selectionItems.size}" else "Выбрать")}
                        if(selectionMode)TextButton(onClick={selection=filtered.mapNotNull{it.binding}.take(240)}){Text("Выбрать все")}
                    }
                    if(selectionMode && selectionItems.isNotEmpty()) {
                        Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=!archive.busy && selectionLocalItems.isNotEmpty() && selectionItems.size<=240,onClick={completeSet=false;saveDialog=true}){Text(if(selectionLocalItems.isEmpty())"Уже в облаке" else "В облако (${selectionLocalItems.size})")}
                            TextButton(onClick={selectionItems.filter{!state.favorites.containsMedia(it)}.forEach{model.favorite(it)}}){Text("В избранное")}
                            TextButton(onClick={addAlbum=true}){Text("В альбом")}
                        }
                        Text("Выберите: оставить оригиналы на телефоне или освободить место после сохранения холодного архива и рабочей копии.",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
                    }
                    if (filtered.isEmpty() && state.access != MediaAccess.NONE && !state.loading) EmptyState("Здесь будут ваши снимки", "Добавьте фото на телефон или измените выбранный доступ.", Icons.Outlined.Photo)
                    else MediaTimeline(filtered, Modifier.weight(1f),open={if(selectionMode)selectItem(it)else selected=it},select={selectItem(it)},selected=selection.toSet(),
                        cloudStatuses=archive.mediaStatus,cloudDetails={_,status->cloudDetail=status})
                }
                Destination.ALBUMS -> LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { CollectionCard("Избранное", collectionSummary(state.favorites, library), library.firstOrNull { state.favorites.containsMedia(it) }, Icons.Outlined.FavoriteBorder) { favoriteFilter = true; destination = Destination.PHOTOS } }
                    if (state.favorites.any { it.startsWith("content:") } || state.albums.any { a -> a.members.any { it.startsWith("content:") } }) item {
                        Text("Прежние записи альбомов сохранены. Добавьте нужные фото заново, чтобы подтвердить их привязку.", style = MaterialTheme.typography.bodySmall, color = Accent)
                    }
                    if (state.albums.isNotEmpty()) item { Text("АЛЬБОМЫ", style = MaterialTheme.typography.labelMedium, color = Accent) }
                    state.albums.forEach { a -> item { CollectionCard(a.name, collectionSummary(a.members, library), library.firstOrNull { a.members.containsMedia(it) }, Icons.Outlined.CollectionsBookmark) { albumFilter = a.name; destination = Destination.PHOTOS } } }
                    item { Text("ПАПКИ УСТРОЙСТВА", style = MaterialTheme.typography.labelMedium, color = Accent) }
                    state.media.groupBy { it.folder }.forEach { (name, members) -> item { CollectionCard(name, "${members.size} файлов", members.first(), Icons.Outlined.FolderOpen) { folder = name; destination = Destination.PHOTOS } } }
                }
                Destination.CLOUD -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { CloudActivityCard(archive,yandex){} }
                    if (state.cloud?.provider != "Google Drive" || yandex.account != null) item {
                        YandexAccountCard(yandex, { signInYandex() }, { cloudModel.recheck() }, { cloudModel.disconnect() })
                    } else item { InfoCard("Google Drive", "Подключение Google Drive появится позже. Сейчас доступен вход через Яндекс Диск.", Icons.Outlined.CloudQueue, "Выбрать Яндекс Диск", { model.chooseCloud("Яндекс Диск") }) }
                    if (yandex.account == null && !yandex.busy && yandex.connection != YandexConnection.STORAGE_ERROR) item {
                        InfoCard("Одно облако. Две зоны.", "Сначала — проверенный холодный архив, затем — рабочая копия галереи. Политика сохраняется при выборе облака.", Icons.Outlined.CloudQueue, "Выбрать облако", { providerDialog = true })
                    }
                    item { ZoneCard("01", "Холодный архив", "Точные исходные файлы. Отдельный жизненный цикл, восстановление без личного ключа.", Icons.Outlined.Inventory2) }
                    item { ZoneCard("02", "Рабочая галерея", "Отдельная копия оригинала для просмотра. Холодный архив сохраняется независимо.", Icons.Outlined.PhotoLibrary) }
                    val readyPlans=archive.plans.filter{it.stage in setOf(ProtectionStage.READY,ProtectionStage.ANDROID_REQUESTED) && ProtectionRules.setReady(it,archive.plans)}.take(32)
                    if(readyPlans.isNotEmpty())item { Button(enabled=!archive.busy,onClick={releaseLocal(readyPlans.map{it.coldId})},modifier=Modifier.padding(horizontal=16.dp).fillMaxWidth()) {
                        Text("Освободить место · ${readyPlans.size} файлов")
                    } }
                    archive.plans.filter{it.mode==SaveMode.FREE_LOCAL || it.stage==ProtectionStage.ERROR}.take(32).forEach { plan ->
                        item(key="plan:${plan.coldId}"){ProtectionCard(plan,archive.busy,{releaseLocal(listOf(plan.coldId))},{archiveModel.retryProtection(plan.coldId)},ProtectionRules.setReady(plan,archive.plans))}
                    }
                    item { InfoCard("Два способа загрузки", "Выделите фото в ленте → «В облако». Можно оставить оригиналы на телефоне или освободить место после проверки обеих копий. Для удаления Android покажет подтверждение.", Icons.Outlined.VerifiedUser) }
                    item { InfoCard("Архивировать доступную медиатеку", "Подготовьте очередь до входа в Яндекс. Учтём все доступные фото и видео, включая большие файлы. Большие файлы загружаются проверяемыми частями без пересжатия. Если Android разрешил только часть медиатеки, архивируем эту часть.", Icons.Outlined.Inventory2,
                        if (!archive.busy) (if (state.access == MediaAccess.NONE) "Разрешить доступ" else "Подготовить все фото и видео") else null,
                        { if (state.access == MediaAccess.NONE) requestMedia() else allInfo = true }) }
                    item { InfoCard("Сохранить отдельный файл", "Добавить файл можно до входа. Загружаем без пересжатия и полностью читаем обратно в сети без тарификации. Исходник остаётся на телефоне.", Icons.Outlined.FolderOpen,
                        if (!archive.busy) "Выбрать файл" else null,
                        { coldInfo = true }) }
                    item { InfoCard("Очередь: ${archive.counts.total}", "Проверено: ${archive.counts.verified} · приостановлено: ${archive.counts.deferred}. Рабочих копий в очереди: ${archive.counts.gallery}.", Icons.Outlined.Schedule,
                        if (!archive.busy) (if (archive.paused) "Продолжить очередь" else "Приостановить очередь") else null,
                        { archiveModel.pause(!archive.paused) }) }
                    archive.batches.forEach { batch -> item(key = batch.id) {
                        InfoCard("Список от ${dateLabel(batch.createdAt)}", when (batch.phase) {
                            com.cielvitrine.archive.ScanPhase.DISCOVERING -> if (archive.paused) "Сканирование на паузе. Позиция сохранена." else "Собираем список порциями. Уже найденные файлы сохранены."
                            com.cielvitrine.archive.ScanPhase.COMPLETE -> "Список доступных файлов собран. Завершение списка не означает завершение загрузки."
                            com.cielvitrine.archive.ScanPhase.PAUSED -> batch.notice ?: "Сканирование остановлено."
                        }, Icons.Outlined.ListAlt, if (batch.phase == com.cielvitrine.archive.ScanPhase.PAUSED && !archive.busy) "Повторить сканирование" else null,
                            { archiveModel.resumeScan(batch.id) })
                    } }
                    item { InfoCard("Рабочие копии из архива", "Отметьте проверенные холодные файлы полного набора. Для составного снимка выберите изображение и все связанные видео. Перед созданием рабочих копий заново прочитаем весь архивный набор.", Icons.Outlined.PhotoLibrary,
                        if (coldSelection.isNotEmpty() && !archive.busy) "Создать рабочий набор: ${coldSelection.size}" else null, { groupInfo = true }) }
                    archive.jobs.forEach { job -> item(key = job.id) {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 24.dp), colors = CardDefaults.cardColors(containerColor = Mist)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(job.name, fontWeight = FontWeight.Medium)
                                Text(when (job.phase) {
                                    com.cielvitrine.archive.ArchivePhase.QUEUED -> "В очереди: ожидает сети без тарификации и доступного аккаунта"
                                    com.cielvitrine.archive.ArchivePhase.PREPARED -> "Контейнер проверен на телефоне. Ожидает загрузки"
                                    com.cielvitrine.archive.ArchivePhase.UPLOAD_UNCERTAIN -> "Загрузка отправлена или могла начаться. Ожидает полного обратного чтения"
                                    com.cielvitrine.archive.ArchivePhase.VERIFIED -> "Копия прочитана полностью. SHA-256 и длина совпали"
                                    com.cielvitrine.archive.ArchivePhase.SOURCE_ERROR -> "Исходный файл недоступен"
                                    com.cielvitrine.archive.ArchivePhase.UNSUPPORTED_SIZE -> "Файл ожидает перевода на загрузку частями"
                                }, style = MaterialTheme.typography.bodyMedium)
                                Text("${if (job.zone == com.cielvitrine.archive.OriginalZone.COLD) "Холодный архив" else "Рабочая копия"} · ${job.sourceBytes?.let { "$it байт" } ?: "размер уточняется"}", style = MaterialTheme.typography.bodySmall)
                                Text(if(archive.plans.any{it.coldId==job.id && it.stage==ProtectionStage.LOCAL_REMOVED})"На телефоне осталась миниатюра" else "Оригинал сохраняется на телефоне до отдельного подтверждения", style = MaterialTheme.typography.bodySmall)
                                job.sourceStamp?.let { Text("${it.width} × ${it.height}${if (it.durationMillis > 0) " · ${it.durationMillis / 1000} с" else ""}", style = MaterialTheme.typography.bodySmall) }
                                job.verifiedAt?.let { Text("Проверено ${dateLabel(it)}. Доступность облачной копии может измениться.", style = MaterialTheme.typography.bodySmall) }
                                job.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if(job.phase==com.cielvitrine.archive.ArchivePhase.VERIFIED) {
                                    if(job.mime.startsWith("image/")) {
                                        Text(archive.tiles[job.id] ?: "Готовим фрагменты",style=MaterialTheme.typography.bodySmall)
                                        if(archive.tiles[job.id]!="Фрагменты готовы")TextButton(onClick={archiveModel.prepareTiles(job.id)}){Text("Подготовить фрагменты")}
                                    }
                                    TextButton(onClick={if(job.id !in archive.benchmarkIds)archiveModel.measure(job.id);selected=job.mediaItem()}){Text("Открыть облачную копию")}
                                }
                                if (job.deferred && !archive.busy) TextButton(onClick = { archiveModel.retry(job.id) }) { Text(if (job.phase == com.cielvitrine.archive.ArchivePhase.UPLOAD_UNCERTAIN) "Повторить проверку" else "Продолжить") }
                                if (job.phase == com.cielvitrine.archive.ArchivePhase.VERIFIED && job.zone == com.cielvitrine.archive.OriginalZone.COLD) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(job.id in coldSelection, onCheckedChange = { checked -> if (!checked) coldSelection = coldSelection - job.id else if (coldSelection.size < 32) coldSelection = coldSelection + job.id })
                                    Text("В рабочий набор", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    } }
                    if (archive.hasMore) item { TextButton(onClick = { archiveModel.more() }, modifier = Modifier.padding(horizontal = 24.dp)) { Text("Следующие записи очереди") } }
                    if (archive.olderPage) item { TextButton(onClick = { archiveModel.first() }, modifier = Modifier.padding(horizontal = 24.dp)) { Text("К новым записям") } }
                    item { PerformanceCard(archive.benchmarkIds) }
                }
                Destination.SETTINGS -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { PlaybackSettingsCard() }
                    item { InfoCard("Восстановление без личного ключа", "Оригиналы хранятся в обратимом контейнере. Облако технически может прочитать их, включая встроенные GPS и EXIF. Это выбранная политика восстановления.", Icons.Outlined.Key) }
                    item { InfoCard("Переносимый оригинал", "Сохраняет байты фотографии и доступные исходные даты. Повторно читает результат для проверки. Восстановление без личного ключа; старые архивы поддерживаются. Исходник остаётся на телефоне.", Icons.Outlined.FactCheck, "Открыть инструменты", { archiveInfo = true }) }
                    item { InfoCard("Корзина на 60 дней", "Будущая корзина галереи сохранит область удаления. Холодный архив удаляется только отдельным выбором. Последняя копия не исчезает по таймеру без разрешения.", Icons.Outlined.DeleteOutline) }
                    item { InfoCard("Доступ к медиатеке", "Можно открыть все фото или только выбранные. Папки и альбомы доступны на вкладке «Альбомы».", Icons.Outlined.PermMedia, "Изменить доступ", { requestMedia() }) }
                    item { InfoCard("Исходные метаданные", "Для точного архива Android должен разрешить чтение встроенного местоположения. Без этого доступа файл не будет принят за полный оригинал.", Icons.Outlined.LocationOn, "Разрешить исходные метаданные", { originalPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }) }
                    item { Text("Небесная витрина · ${BuildConfig.VERSION_NAME}\nВход через Яндекс Диск", Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall, color = Accent) }
                }
            }
        }
    }

    selected?.let { item ->
        val pages=if(item.cloudId!=null)(archive.remoteMedia+archive.jobs.filter{it.phase==com.cielvitrine.archive.ArchivePhase.VERIFIED}.map{it.mediaItem()}).distinctBy{it.uri}
            else filtered.map{local-> archive.jobs.firstOrNull{it.zone==com.cielvitrine.archive.OriginalZone.COLD && it.matches(local)}?.let{job->local.copy(performanceId=job.id)}?:local}
        GalleryViewer(pages,item,close={selected=null},isFavorite={state.favorites.containsMedia(it)},favorite={model.favorite(it)},
            album={selected=it;addAlbum=true},changed={selected=it})
    }
    cloudDetail?.let { status -> AlertDialog(onDismissRequest={cloudDetail=null},title={Text(status.title)},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(status.detail)
            if(status.coldVerified)Text("Холодный архив проверен. Значок отражает последнюю проверку; доступность облака может измениться.",style=MaterialTheme.typography.bodySmall)
            status.progress?.let{LinearProgressIndicator(progress={it},modifier=Modifier.fillMaxWidth());Text("${(it*100).toInt()}% текущей части")}
            Text("Облачко с галочкой — сохранено; коробка — только архив; перечёркнутое облако — только телефон; стрелка — загрузка; восклицание — нужна проверка.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={cloudDetail=null;destination=Destination.CLOUD}){Text("Открыть облако")}},dismissButton={TextButton(onClick={cloudDetail=null}){Text("Закрыть")}}) }
    if(saveDialog)AlertDialog(onDismissRequest={saveDialog=false},title={Text("Сохранить ${selectionLocalItems.size} файлов")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Сначала создадим холодный архив, затем отдельную рабочую копию. Оригиналы не пересжимаются.")
            Button(onClick={saveMode=SaveMode.KEEP_LOCAL;saveDialog=false;pendingSelected=true;originalPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)},modifier=Modifier.fillMaxWidth()){Text("Загрузить и оставить на телефоне")}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(completeSet,{completeSet=it});Text("Выбраны все части снимков, включая отдельные видео Live Photo и сопутствующие файлы",style=MaterialTheme.typography.bodySmall)}
            OutlinedButton(enabled=completeSet && Build.VERSION.SDK_INT>=30,onClick={saveMode=SaveMode.FREE_LOCAL;autoReleaseAttempted=emptyList();saveDialog=false;pendingSelected=true;originalPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)},modifier=Modifier.fillMaxWidth()){Text("Загрузить и освободить место")}
            Text("На телефоне останутся миниатюры. Когда все проверки закончатся, открытая галерея покажет подтверждение Android. Отменённое удаление можно повторить на вкладке «Облако». Для форматов без проверенного просмотра удаление заблокировано.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={saveDialog=false}){Text("Закрыть")}})
    if (addAlbum) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addAlbum = false }, title = { Text(if (selected == null) "Новый альбом" else "Добавить в альбом") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                if (selected != null || selectionMode) state.albums.forEach { a -> TextButton(onClick = { if(selectionMode)selectionItems.forEach{model.addToAlbum(a.name,it)}else model.addToAlbum(a.name,selected); addAlbum = false }) { Text(a.name) } }
            } }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { if(selectionMode)selectionItems.forEach{model.addToAlbum(name,it)}else model.addToAlbum(name,selected); addAlbum = false }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { addAlbum = false }) { Text("Отмена") } })
    }
    if (providerDialog) AlertDialog(onDismissRequest = { providerDialog = false }, title = { Text("Сначала холодный архив") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Выбор сохраняется один раз. Рабочая облачная копия будет ждать полной проверки архива этого снимка. Сейчас можно подключить Яндекс Диск; Google Drive появится позже.")
            listOf("Яндекс Диск", "Google Drive").forEach { provider -> OutlinedButton(onClick = { model.chooseCloud(provider); providerDialog = false }, modifier = Modifier.fillMaxWidth()) { Text(provider) } }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { providerDialog = false }) { Text("Позже") } })
    if (coldInfo) AlertDialog(onDismissRequest = { coldInfo = false }, title = { Text("Сохранить холодную копию") }, text = {
        Text("Будет создан отдельный холодный архив на Яндекс Диске без пересжатия. Ключ восстановления находится внутри: облако технически может прочитать файл. Большие файлы сохраняются частями. Все части Live Photo нужно добавить отдельно. После загрузки проверим исходные байты. Файл останется на телефоне.")
    }, confirmButton = { TextButton(onClick = { coldInfo = false; selectColdFile.launch(arrayOf("*/*")) }) { Text("Выбрать файл") } },
        dismissButton = { TextButton(onClick = { coldInfo = false }) { Text("Отмена") } })
    if (allInfo) AlertDialog(onDismissRequest = { allInfo = false }, title = { Text("Подготовить холодный архив") }, text = {
        Text("Добавим доступные Android фото и видео в очередь. Для точных оригиналов нужно разрешение на встроенные метаданные, включая местоположение. Контейнеры восстанавливаются без личного ключа: облако сможет прочитать их. Большие файлы будут учтены отдельно. Исходники сохраняются.")
    }, confirmButton = { TextButton(onClick = { allInfo = false; pendingAll = true; originalPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }) { Text("Подготовить") } },
        dismissButton = { TextButton(onClick = { allInfo = false }) { Text("Отмена") } })
    if (groupInfo) AlertDialog(onDismissRequest = { groupInfo = false }, title = { Text("Полный набор ресурсов") }, text = {
        Text("Выбрано файлов: ${coldSelection.size}. Приложение пока не распознаёт пары Live Photo автоматически. Подтвердите, что выбраны все ресурсы снимка. Создадим новые контейнеры в отдельной папке рабочей галереи; телефонные оригиналы сохраняются.")
    }, confirmButton = { TextButton(onClick = { groupInfo = false; archiveModel.gallery(coldSelection.toList()); coldSelection = emptySet() }) { Text("Набор полный, создать") } },
        dismissButton = { TextButton(onClick = { groupInfo = false }) { Text("Отмена") } })
    if (archiveInfo) AlertDialog(onDismissRequest = { archiveInfo = false }, title = { Text("Переносимый оригинал") }, text = {
        Text("Выберите исходный файл, затем место для контейнера. Он не скрывает содержимое от владельца облака. После записи приложение прочитает контейнер обратно и сравнит байты. Восстановление тоже проверяет сохранённый результат.")
    }, confirmButton = { TextButton(onClick = { archiveInfo = false; restore = false; openFile.launch(arrayOf("*/*")) }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = { archiveInfo = false; restore = true; openFile.launch(arrayOf("*/*")) }) { Text("Восстановить") } })
    if (state.fileOperationRunning) AlertDialog(onDismissRequest = {}, title = { Text("Запись и проверка файла") }, text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Идёт полное чтение. Исходный файл остаётся на месте.") } }, confirmButton = {})
}

@Composable
private fun YandexAccountCard(state: YandexCloudState, signIn: () -> Unit, recheck: () -> Unit, disconnect: () -> Unit) {
    val account = state.account
    val status = when (state.connection) {
        YandexConnection.RESTORING -> "Восстанавливаем подключение…"
        YandexConnection.SIGNING_IN -> "Вход через Яндекс…"
        YandexConnection.CHECKING -> "Проверяем подключение…"
        YandexConnection.CONNECTED -> "Яндекс Диск подключён"
        YandexConnection.DISCONNECTED -> "Подключить Яндекс Диск"
        YandexConnection.UNAVAILABLE -> "Подключение не подтверждено"
        YandexConnection.REAUTH_REQUIRED -> "Нужно повторить вход"
        YandexConnection.STORAGE_ERROR -> "Сохранённый вход недоступен"
    }
    Surface(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Mist.copy(alpha = .55f)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.CloudQueue, null, tint = Accent)
            Text(status, style = MaterialTheme.typography.titleMedium)
            if (account != null) Text(account.label, style = MaterialTheme.typography.bodyLarge)
            if (state.connection == YandexConnection.CONNECTED && account?.totalBytes != null && account.usedBytes != null) {
                Text("${"%.1f".format(account.usedBytes / 1073741824.0)} из ${"%.1f".format(account.totalBytes / 1073741824.0)} ГБ занято на Диске", style = MaterialTheme.typography.bodyMedium)
                Text("Доступ к папке приложения проверен. Очередь загружается в сети без тарификации.", style = MaterialTheme.typography.bodyMedium)
            } else Text(if (account == null) "Войдите в свой аккаунт. Подключение сохранится на этом устройстве." else "Сохранённый аккаунт остаётся выбранным. Проверка не меняет облачные файлы.", style = MaterialTheme.typography.bodyMedium)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                if (state.connection != YandexConnection.STORAGE_ERROR && state.connection != YandexConnection.CONNECTED) Button(onClick = signIn) { Text("Войти через Яндекс") }
                if (account != null && state.connection != YandexConnection.STORAGE_ERROR) TextButton(onClick = recheck) { Text("Проверить подключение") }
                if (account != null || state.connection == YandexConnection.STORAGE_ERROR) TextButton(onClick = disconnect) { Text("Отключить на этом устройстве") }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, icon: ImageVector, action: String? = null, onAction: () -> Unit = {}) {
    Surface(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Mist.copy(alpha = .55f)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = Accent)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Ink.copy(alpha = .75f))
            if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun ZoneCard(number: String, title: String, body: String, icon: ImageVector) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 24.dp), shape = RoundedCornerShape(24.dp), color = Card) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(number, style = MaterialTheme.typography.headlineMedium, color = Accent.copy(alpha = .55f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodyMedium, color = Ink.copy(alpha = .75f)) }
            Icon(icon, null, tint = Accent)
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = Accent.copy(alpha = .5f)); Text(title, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodyMedium, color = Accent)
    }
}

@Composable
internal fun MediaThumb(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.uri, item.binding, item.modifiedSeconds, item.generation, item.bytes) {
        value = null
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { CurrentThumbnail.load(context,item) }.getOrNull()
        }
    }
    Box(modifier.background(Mist), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(if (item.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image, item.name, tint = Accent)
        if (item.isVideo || item.mime == "image/gif") Surface(Modifier.align(Alignment.BottomEnd).padding(6.dp), shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = .5f)) {
            Text(if (item.isVideo) "${item.duration / 60000}:${(item.duration / 1000 % 60).toString().padStart(2, '0')}" else "GIF", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CollectionCard(title: String, subtitle: String, cover: MediaItem?, icon: ImageVector, click: () -> Unit) {
    Surface(onClick = click, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Card) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (cover != null) MediaThumb(cover, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
            else Box(Modifier.size(72.dp).background(Mist, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Accent) }
            Icon(Icons.Outlined.ChevronRight, null, tint = Accent)
        }
    }
}
