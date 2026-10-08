package com.cielvitrine.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cielvitrine.provider.YandexDiskClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class YandexConnection { RESTORING, DISCONNECTED, SIGNING_IN, CHECKING, CONNECTED, UNAVAILABLE, REAUTH_REQUIRED, STORAGE_ERROR }
data class YandexAccount(val uid: String, val label: String, val totalBytes: Long? = null, val usedBytes: Long? = null)
data class YandexCloudState(val connection: YandexConnection = YandexConnection.RESTORING,
    val account: YandexAccount? = null, val message: String? = null,
) { val busy get() = connection in setOf(YandexConnection.RESTORING, YandexConnection.SIGNING_IN, YandexConnection.CHECKING) }

/** Credentials never enter StateFlow, Compose, SavedStateHandle, logs or gallery preferences. */
class YandexCloudModel internal constructor(app: Application, private val store: YandexSessionStore,
    private val probe: (String) -> YandexDiskClient.Account,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, YandexSessionStore(app), { token ->
        YandexDiskClient { token }.connectionInfo(BuildConfig.YANDEX_CLIENT_ID)
    })
    private var session: YandexSession? = null
    private val operations = Mutex()
    private val mutable = MutableStateFlow(YandexCloudState())
    val state = mutable.asStateFlow()

    init { viewModelScope.launch { operations.withLock {
        try {
            session = withContext(Dispatchers.IO) { store.load() }
            val saved = session
            if (saved == null) mutable.value = YandexCloudState(YandexConnection.DISCONNECTED)
            else {
                mutable.value = YandexCloudState(YandexConnection.CHECKING, YandexAccount(saved.uid, saved.label))
                verify(saved.token, saved.expiresAtMillis)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            mutable.value = YandexCloudState(YandexConnection.STORAGE_ERROR, message = "Не удалось открыть сохранённый вход. Сбросьте подключение и войдите снова.")
        }
    } } }

    fun beginLogin(): Boolean {
        if (mutable.value.busy || mutable.value.connection == YandexConnection.STORAGE_ERROR) return false
        mutable.value = mutable.value.copy(connection = YandexConnection.SIGNING_IN, message = null)
        return true
    }
    fun loginCancelled() {
        mutable.value = mutable.value.copy(connection = if (session == null) YandexConnection.DISCONNECTED else YandexConnection.UNAVAILABLE,
            message = "Вход отменён. Файлы не изменены.")
    }
    fun loginFailed() {
        mutable.value = mutable.value.copy(connection = if (session == null) YandexConnection.DISCONNECTED else YandexConnection.REAUTH_REQUIRED,
            message = "Не удалось выполнить вход. Проверьте Android package name и SHA-256 сертификата в настройках Яндекс OAuth.")
    }
    fun loginSucceeded(token: String, expiresInSeconds: Long) {
        // The callback may arrive after Android recreated the process; wait for restoration first.
        mutable.value = mutable.value.copy(connection = YandexConnection.CHECKING, message = null)
        val receivedAt = System.currentTimeMillis()
        val expiry = if (expiresInSeconds > 0) runCatching { Math.addExact(receivedAt, Math.multiplyExact(expiresInSeconds, 1000L)) }.getOrNull() else null
        viewModelScope.launch { operations.withLock {
            mutable.value = mutable.value.copy(connection = YandexConnection.CHECKING, message = null)
            verify(token, expiry)
        } }
    }
    fun recheck() {
        if (mutable.value.busy) return
        val saved = session ?: return
        mutable.value = mutable.value.copy(connection = YandexConnection.CHECKING, message = null)
        viewModelScope.launch { operations.withLock { verify(saved.token, saved.expiresAtMillis) } }
    }
    fun disconnect() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(connection = YandexConnection.CHECKING, message = null)
        viewModelScope.launch { operations.withLock {
            try {
                withContext(Dispatchers.IO) { store.clear() }
                session = null
                mutable.value = YandexCloudState(YandexConnection.DISCONNECTED, message = "Подключение удалено с устройства. Файлы на Диске сохранены.")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutable.value = mutable.value.copy(connection = YandexConnection.STORAGE_ERROR, message = "Не удалось удалить сохранённое подключение.") }
        } }
    }
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }

    private suspend fun verify(token: String, expiry: Long?) {
        if (expiry != null && expiry <= System.currentTimeMillis()) {
            mutable.value = mutable.value.copy(connection = YandexConnection.REAUTH_REQUIRED, message = "Срок входа истёк. Войдите снова.")
            return
        }
        try {
            val info = withContext(Dispatchers.IO) { probe(token) }
            val saved = session
            if (saved != null && !saved.matchesAccount(info.uid, info.appRootId)) {
                mutable.value = mutable.value.copy(connection = YandexConnection.REAUTH_REQUIRED,
                    message = "Выбран другой аккаунт или изменилась папка приложения. Сначала явно отключите прежнее подключение.")
                return
            }
            val updated = YandexSession(token, info.uid, info.label, expiry, info.appRootId)
            try { withContext(Dispatchers.IO) { store.save(updated) } }
            catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(connection = YandexConnection.STORAGE_ERROR, message = "Вход проверен, но сохранить его на устройстве не удалось.")
                return
            }
            session = updated
            mutable.value = YandexCloudState(YandexConnection.CONNECTED, YandexAccount(info.uid, info.label, info.totalBytes, info.usedBytes))
        } catch (e: CancellationException) { throw e
        } catch (e: YandexDiskClient.ConnectionException) {
            val message = when (e.problem) {
                YandexDiskClient.ConnectionProblem.UNAUTHORIZED -> "Доступ истёк или отозван. Войдите снова."
                YandexDiskClient.ConnectionProblem.DISK_PERMISSION -> "В Яндекс OAuth включите разрешение «Информация о Диске» и повторите вход."
                YandexDiskClient.ConnectionProblem.APP_FOLDER_PERMISSION -> "В Яндекс OAuth включите доступ к папке приложения на Диске и повторите вход."
                YandexDiskClient.ConnectionProblem.APP_FOLDER_UNAVAILABLE -> "Папка приложения недоступна. Проверьте права Яндекс OAuth и повторите вход."
                YandexDiskClient.ConnectionProblem.IDENTITY_MISMATCH -> "Не удалось подтвердить аккаунт и идентификатор OAuth-приложения."
                YandexDiskClient.ConnectionProblem.UNAVAILABLE -> "Яндекс Диск сейчас недоступен. Повторите проверку позже."
            }
            mutable.value = mutable.value.copy(connection = if (e.problem == YandexDiskClient.ConnectionProblem.UNAVAILABLE) YandexConnection.UNAVAILABLE else YandexConnection.REAUTH_REQUIRED, message = message)
        } catch (e: Exception) {
            // No raw SDK/network exception messages: they can contain credentials or transfer URLs.
            mutable.value = mutable.value.copy(connection = YandexConnection.UNAVAILABLE, message = "Не удалось проверить подключение. Проверьте сеть и повторите попытку.")
        }
    }
}
