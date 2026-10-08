package com.cielvitrine.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.CachePolicy
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthSdk
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFF253D50)
private val Paper = Color(0xFFF8F7F3)
private val Mist = Color(0xFFE5ECEB)
private val Accent = Color(0xFF53746F)
private val russian = Locale.forLanguageTag("ru")
private fun dateLabel(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM yyyy", russian))
private fun monthLabel(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("LLLL yyyy", russian)).replaceFirstChar { it.titlecase(russian) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, onPrimary = Color.White,
                background = Paper, surface = Paper, onSurface = Ink, onBackground = Ink, secondaryContainer = Mist)) {
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
    var addAlbum by remember { mutableStateOf(false) }
    var providerDialog by remember { mutableStateOf(false) }
    var archiveInfo by remember { mutableStateOf(false) }
    var coldInfo by remember { mutableStateOf(false) }
    var allInfo by remember { mutableStateOf(false) }
    var groupInfo by remember { mutableStateOf(false) }
    var coldSelection by remember { mutableStateOf(setOf<String>()) }
    var pendingAll by rememberSaveable { mutableStateOf(false) }
    val originalPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (pendingAll) { pendingAll = false; archiveModel.prepareAll() } }
    val selectColdFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) archiveModel.add(uri) }
    var pendingSource by rememberSaveable { mutableStateOf<String?>(null) }
    var restore by rememberSaveable { mutableStateOf(false) }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingSource
        if (uri != null && source != null) model.portableFile(android.net.Uri.parse(source), uri, restore)
        pendingSource = null
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingSource = uri.toString()
            createFile.launch(if (restore) "restored-original.bin" else "original.cv2")
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    fun requestMedia() = permissions.launch(if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { model.refresh() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); model.message(null) }
    }
    LaunchedEffect(archive.message) { archive.message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); archiveModel.clearMessage() } }
    LaunchedEffect(yandex.message) { yandex.message?.let { model.message(it); cloudModel.clearMessage() } }
    LaunchedEffect(yandex.connection, yandex.account?.uid) { if (yandex.account != null && state.cloud?.provider != "Яндекс Диск") model.chooseCloud("Яндекс Диск")
        if (yandex.connection == YandexConnection.CONNECTED) archiveModel.accountConnected() }
    val filtered = remember(state.media, state.favorites, state.albums, folder, albumFilter, favoriteFilter) {
        state.media.filter { item -> (folder == null || item.folder == folder) && (!favoriteFilter || item.uri.toString() in state.favorites) &&
            (albumFilter == null || item.uri.toString() in (state.albums.firstOrNull { it.name == albumFilter }?.members ?: emptySet())) }
    }
    BackHandler(folder != null || albumFilter != null || favoriteFilter) { folder = null; albumFilter = null; favoriteFilter = false }

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
            when (destination) {
                Destination.PHOTOS -> {
                    if (state.access != MediaAccess.FULL) InfoCard(
                        if (state.access == MediaAccess.NONE) "Откройте свою медиатеку" else "Доступна часть медиатеки",
                        if (state.access == MediaAccess.NONE) "Фото и видео остаются на телефоне. Вы сами выбираете, к чему дать доступ." else "Отсутствие доступа не означает, что файл удалён.",
                        Icons.Outlined.PhotoLibrary, "Выбрать фото и видео", { requestMedia() })
                    if (state.media.isNotEmpty()) Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !favoriteFilter, onClick = { favoriteFilter = false }, label = { Text("Все") })
                        FilterChip(selected = favoriteFilter, onClick = { favoriteFilter = !favoriteFilter }, label = { Text("Избранное") }, leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(16.dp)) })
                    }
                    if (filtered.isEmpty() && state.access != MediaAccess.NONE && !state.loading) EmptyState("Здесь будут ваши снимки", "Добавьте фото на телефон или измените выбранный доступ.", Icons.Outlined.Photo)
                    else MediaGrid(filtered, state.hasMore, { model.more() }) { selected = it }
                }
                Destination.ALBUMS -> LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { CollectionCard("Избранное", "${state.favorites.size} сохранено", state.media.firstOrNull { it.uri.toString() in state.favorites }, Icons.Outlined.FavoriteBorder) { favoriteFilter = true; destination = Destination.PHOTOS } }
                    if (state.albums.isNotEmpty()) item { Text("АЛЬБОМЫ", style = MaterialTheme.typography.labelMedium, color = Accent) }
                    state.albums.forEach { a -> item { CollectionCard(a.name, "${a.members.size} файлов", state.media.firstOrNull { it.uri.toString() in a.members }, Icons.Outlined.CollectionsBookmark) { albumFilter = a.name; destination = Destination.PHOTOS } } }
                    item { Text("ПАПКИ УСТРОЙСТВА", style = MaterialTheme.typography.labelMedium, color = Accent) }
                    state.media.groupBy { it.folder }.forEach { (name, members) -> item { CollectionCard(name, "${members.size} в загруженной части списка", members.first(), Icons.Outlined.FolderOpen) { folder = name; destination = Destination.PHOTOS } } }
                    if (state.hasMore) item { OutlinedButton(onClick = { model.more() }) { Text("Загрузить следующие файлы") } }
                }
                Destination.CLOUD -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.cloud?.provider != "Google Drive" || yandex.account != null) item {
                        YandexAccountCard(yandex, { signInYandex() }, { cloudModel.recheck() }, { cloudModel.disconnect() })
                    } else item { InfoCard("Google Drive", "Подключение Google Drive появится позже. Сейчас доступен вход через Яндекс Диск.", Icons.Outlined.CloudQueue, "Выбрать Яндекс Диск", { model.chooseCloud("Яндекс Диск") }) }
                    if (yandex.account == null && !yandex.busy && yandex.connection != YandexConnection.STORAGE_ERROR) item {
                        InfoCard("Одно облако. Две зоны.", "Сначала — проверенный холодный архив, затем — рабочая копия галереи. Политика сохраняется при выборе облака.", Icons.Outlined.CloudQueue, "Выбрать облако", { providerDialog = true })
                    }
                    item { ZoneCard("01", "Холодный архив", "Точные исходные файлы. Отдельный жизненный цикл, восстановление без личного ключа.", Icons.Outlined.Inventory2) }
                    item { ZoneCard("02", "Рабочая галерея", "Отдельные оригиналы уже можно создать из архива. Миниатюры и открытие из облака — следующий этап.", Icons.Outlined.PhotoLibrary) }
                    item { InfoCard("Освобождение места — отдельное действие", "Загрузка не удаляет оригиналы с телефона. Удаление будет доступно после проверки копий и подтверждения Android.", Icons.Outlined.VerifiedUser) }
                    item { InfoCard("Архивировать доступную медиатеку", "Подготовьте очередь до входа в Яндекс. Учтём все доступные фото и видео, включая большие файлы. Загрузка сейчас поддерживает файлы до 128 МиБ. Если Android разрешил только часть медиатеки, архивируем эту часть.", Icons.Outlined.Inventory2,
                        if (!archive.busy) (if (state.access == MediaAccess.NONE) "Разрешить доступ" else "Подготовить все фото и видео") else null,
                        { if (state.access == MediaAccess.NONE) requestMedia() else allInfo = true }) }
                    item { InfoCard("Сохранить отдельный файл", "Добавить файл можно до входа. Загружаем без пересжатия и полностью читаем обратно в сети без тарификации. Исходник остаётся на телефоне.", Icons.Outlined.FolderOpen,
                        if (!archive.busy) "Выбрать файл" else null,
                        { coldInfo = true }) }
                    item { InfoCard("Очередь: ${archive.counts.total}", "Проверено: ${archive.counts.verified} · приостановлено: ${archive.counts.deferred} · свыше лимита: ${archive.counts.phases[com.cielvitrine.archive.ArchivePhase.UNSUPPORTED_SIZE] ?: 0}. Рабочих копий в очереди: ${archive.counts.gallery}.", Icons.Outlined.Schedule,
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
                                    com.cielvitrine.archive.ArchivePhase.UNSUPPORTED_SIZE -> "Файл учтён; загрузка свыше 128 МиБ пока недоступна"
                                }, style = MaterialTheme.typography.bodyMedium)
                                Text("${if (job.zone == com.cielvitrine.archive.OriginalZone.COLD) "Холодный архив" else "Рабочая копия"} · ${job.sourceBytes?.let { "$it байт" } ?: "размер уточняется"}", style = MaterialTheme.typography.bodySmall)
                                Text("${job.uid?.let { "Аккаунт Яндекса: $it" } ?: "Аккаунт будет привязан перед первой загрузкой"} · исходник сохранён", style = MaterialTheme.typography.bodySmall)
                                job.sourceStamp?.let { Text("${it.width} × ${it.height}${if (it.durationMillis > 0) " · ${it.durationMillis / 1000} с" else ""}", style = MaterialTheme.typography.bodySmall) }
                                job.verifiedAt?.let { Text("Проверено ${dateLabel(it)}. Доступность облачной копии может измениться.", style = MaterialTheme.typography.bodySmall) }
                                job.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
                }
                Destination.SETTINGS -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { InfoCard("Восстановление без личного ключа", "Оригиналы хранятся в обратимом контейнере. Облако технически может прочитать их, включая встроенные GPS и EXIF. Это выбранная политика восстановления.", Icons.Outlined.Key) }
                    item { InfoCard("Переносимый оригинал CVPOS2", "AES-256-GCM с ключом восстановления внутри файла, SHA-256 и полное повторное чтение. Старые POS v1 тоже можно восстановить. Исходник не удаляется.", Icons.Outlined.FactCheck, "Открыть инструменты", { archiveInfo = true }) }
                    item { InfoCard("Корзина на 60 дней", "Будущая корзина галереи сохранит область удаления. Холодный архив удаляется только отдельным выбором. Последняя копия не исчезает по таймеру без разрешения.", Icons.Outlined.DeleteOutline) }
                    item { InfoCard("Доступ к медиатеке", "Можно открыть все фото или только выбранные. Папки и альбомы доступны на вкладке «Альбомы».", Icons.Outlined.PermMedia, "Изменить доступ", { requestMedia() }) }
                    item { InfoCard("Исходные метаданные", "Для точного архива Android должен разрешить чтение встроенного местоположения. Без этого доступа файл не будет принят за полный оригинал.", Icons.Outlined.LocationOn, "Разрешить исходные метаданные", { originalPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }) }
                    item { Text("Небесная витрина · ${BuildConfig.VERSION_NAME}\nВход через Яндекс Диск", Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall, color = Accent) }
                }
            }
        }
    }

    selected?.let { item -> Viewer(item, item.uri.toString() in state.favorites, { selected = null }, { model.favorite(item) }, { addAlbum = true }) }
    if (addAlbum) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addAlbum = false }, title = { Text(if (selected == null) "Новый альбом" else "Добавить в альбом") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                if (selected != null) state.albums.forEach { a -> TextButton(onClick = { model.addToAlbum(a.name, selected); addAlbum = false }) { Text(a.name) } }
            } }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { model.addToAlbum(name, selected); addAlbum = false }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { addAlbum = false }) { Text("Отмена") } })
    }
    if (providerDialog) AlertDialog(onDismissRequest = { providerDialog = false }, title = { Text("Сначала холодный архив") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Выбор сохраняется один раз. Рабочая облачная копия будет ждать полной проверки архива этого снимка. Сейчас можно подключить Яндекс Диск; Google Drive появится позже.")
            listOf("Яндекс Диск", "Google Drive").forEach { provider -> OutlinedButton(onClick = { model.chooseCloud(provider); providerDialog = false }, modifier = Modifier.fillMaxWidth()) { Text(provider) } }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { providerDialog = false }) { Text("Позже") } })
    if (coldInfo) AlertDialog(onDismissRequest = { coldInfo = false }, title = { Text("Сохранить холодную копию") }, text = {
        Text("Будет создан отдельный контейнер на Яндекс Диске. Ключ и описание восстановления находятся внутри: облако технически может прочитать файл. На этом этапе выбирается один файл до 128 МиБ; пары Live Photo нужно сохранять по отдельности. Проверка потребует загрузить контейнер и скачать его полностью. Исходник сохранится.")
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
    Surface(Modifier.fillMaxWidth().padding(horizontal = 24.dp), shape = RoundedCornerShape(24.dp), color = Color.White) {
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
private fun MediaGrid(media: List<MediaItem>, hasMore: Boolean, more: () -> Unit, open: (MediaItem) -> Unit) {
    val groups = remember(media) { media.groupBy { monthLabel(it.date) } }
    LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Text("Недавно добавленные · ${media.size}", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = Accent) }
        groups.forEach { (date, members) ->
            item(key = "month:$date", span = { GridItemSpan(maxLineSpan) }) { Text(date, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium) }
            items(members, key = { it.uri.toString() }) { item -> MediaThumb(item, Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { open(item) }) }
        }
        if (hasMore) item(span = { GridItemSpan(maxLineSpan) }) { TextButton(onClick = more, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Загрузить следующие файлы") } }
    }
}

@Composable
private fun MediaThumb(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(item.uri, android.util.Size(320, 320), null) }.getOrNull()
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
    Surface(onClick = click, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (cover != null) MediaThumb(cover, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
            else Box(Modifier.size(72.dp).background(Mist, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Accent) }
            Icon(Icons.Outlined.ChevronRight, null, tint = Accent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Viewer(item: MediaItem, favorite: Boolean, close: () -> Unit, toggleFavorite: () -> Unit, album: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(containerColor = Color(0xFF18262D), contentColor = Color.White, topBar = {
            TopAppBar(title = { Text(dateLabel(item.date), style = MaterialTheme.typography.titleMedium) }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF18262D), titleContentColor = Color.White, navigationIconContentColor = Color.White))
        }, bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(item.name, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                Text("${item.width} × ${item.height} · ${"%.1f".format(item.bytes / 1048576.0)} МБ · ${item.mime}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .65f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = toggleFavorite) { Icon(if (favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, "Избранное", tint = if (favorite) Color(0xFFE8AEA5) else Color.White) }
                    IconButton(onClick = album) { Icon(Icons.Outlined.CreateNewFolder, "Добавить в альбом", tint = Color.White) }
                }
            }
        }) { inset ->
            if (item.isVideo) Video(item, Modifier.fillMaxSize().padding(inset))
            else {
                val context = LocalContext.current
                val loader = remember { ImageLoader.Builder(context).diskCache(null).components { add(AnimatedImageDecoder.Factory()) }.build() }
                DisposableEffect(loader) { onDispose { loader.shutdown() } }
                var scale by remember(item.uri) { mutableFloatStateOf(1f) }
                var x by remember(item.uri) { mutableFloatStateOf(0f) }
                var y by remember(item.uri) { mutableFloatStateOf(0f) }
                Box(Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(0.dp)).pointerInput(item.uri) {
                    detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); x = if (scale == 1f) 0f else x + pan.x; y = if (scale == 1f) 0f else y + pan.y }
                }, contentAlignment = Alignment.Center) {
                    AsyncImage(model = ImageRequest.Builder(context).data(item.uri).diskCachePolicy(CachePolicy.DISABLED).crossfade(true).build(), imageLoader = loader,
                        contentDescription = item.name, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = x, translationY = y), contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Video(item: MediaItem, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(item.uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(PlayerMediaItem.fromUri(item.uri)); prepare() } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = modifier)
}
