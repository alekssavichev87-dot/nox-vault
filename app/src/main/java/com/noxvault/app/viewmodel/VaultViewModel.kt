package com.noxvault.app.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noxvault.app.crypto.RecoveryKey
import com.noxvault.app.crypto.WrongPasswordException
import com.noxvault.app.crypto.CorruptedVaultException
import com.noxvault.app.data.VaultRepository
import com.noxvault.app.data.VaultStorage
import com.noxvault.app.data.importer.BrowserFormat
import com.noxvault.app.data.importer.ColumnMapping
import com.noxvault.app.data.importer.CsvPasswordImporter
import com.noxvault.app.data.importer.ImportCandidate
import com.noxvault.app.data.importer.ParsedCsv
import com.noxvault.app.domain.model.AttachmentMeta
import com.noxvault.app.domain.model.Folder
import com.noxvault.app.domain.model.VaultItem
import com.noxvault.app.security.BiometricHelper
import com.noxvault.app.security.ClipboardHelper
import com.noxvault.app.security.SettingsStore
import com.noxvault.app.security.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import javax.crypto.Cipher

enum class AuthState { LOADING, NO_VAULT, LOCKED, UNLOCKED }

/** Сессия импорта CSV: распарсенный файл живёт только в памяти и очищается после импорта/отмены. */
data class CsvImportSession(
    val fileName: String,
    val parsed: ParsedCsv,
    val format: BrowserFormat,
    val mapping: ColumnMapping,
    val candidates: List<ImportCandidate>
)

/**
 * Главный ViewModel (MVVM). Держит состояние экранов, вызывает Repository,
 * управляет блокировкой, попытками входа и несекретными настройками.
 * Пароли приходят из UI строками и сразу превращаются в CharArray,
 * который затирается в Repository после использования (сама строка Compose —
 * известное ограничение платформы, см. docs/SECURITY.md).
 */
class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = VaultStorage(application)
    // Общий на процесс Repository: UI и служба автозаполнения видят одну сессию.
    private val repository = VaultRepository.getInstance(application)
    val settings = SettingsStore(application)
    val biometric = BiometricHelper(application)
    private val clipboard = ClipboardHelper(application, viewModelScope)

    val biometricFile: File get() = storage.biometricFile

    private val _authState = MutableStateFlow(AuthState.LOADING)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    /** Ключ восстановления для одноразового показа (после создания/перегенерации). */
    private val _pendingRecoveryKey = MutableStateFlow<String?>(null)
    val pendingRecoveryKey: StateFlow<String?> = _pendingRecoveryKey.asStateFlow()

    /** True после входа по ключу восстановления: UI требует задать новый мастер-пароль. */
    private val _needsPasswordReset = MutableStateFlow(false)
    val needsPasswordReset: StateFlow<Boolean> = _needsPasswordReset.asStateFlow()

    private val _themeMode = MutableStateFlow(settings.themeMode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var backgroundedAtMs: Long = 0L

    init {
        syncWithRepository()
    }

    /**
     * Синхронизировать этот ViewModel с общей in-memory сессией Repository.
     * Нужно, когда другой экран процесса (AutofillAuthActivity) разблокировал
     * или заблокировал хранилище, пока этот ViewModel жил своей жизнью.
     */
    fun syncWithRepository() {
        if (repository.isUnlocked) {
            refreshFromRepo()
            _authState.value = AuthState.UNLOCKED
            backgroundedAtMs = System.currentTimeMillis()
        } else {
            _items.value = emptyList()
            _folders.value = emptyList()
            _authState.value = if (repository.vaultExists()) AuthState.LOCKED else AuthState.NO_VAULT
        }
    }

    /** Открыта ли общая сессия прямо сейчас (для AutofillAuthActivity). */
    fun repositoryUnlocked(): Boolean = repository.isUnlocked

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    fun clearAuthError() {
        _authError.value = null
    }

    fun dismissRecoveryKey() {
        _pendingRecoveryKey.value = null
    }

    // ---------- Создание и разблокировка ----------

    fun createVault(password: String) {
        if (_busy.value) return
        _busy.value = true
        _authError.value = null
        viewModelScope.launch {
            try {
                val recovery = repository.createVault(password.toCharArray())
                settings.resetLockout()
                _pendingRecoveryKey.value = recovery
                refreshFromRepo()
                _authState.value = AuthState.UNLOCKED
            } catch (e: Exception) {
                // Не создаём «полуготовое» хранилище при ошибке криптоинициализации.
                repository.lock()
                _authError.value = "Не удалось создать хранилище: ${e.userMessage()}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun unlock(password: String) {
        if (_busy.value) return
        val lockout = settings.remainingLockoutSeconds()
        if (lockout > 0) {
            _authError.value = "Слишком много неверных попыток. Подождите $lockout сек."
            return
        }
        _busy.value = true
        _authError.value = null
        viewModelScope.launch {
            try {
                repository.unlock(password.toCharArray())
                settings.resetLockout()
                refreshFromRepo()
                _authState.value = AuthState.UNLOCKED
            } catch (e: WrongPasswordException) {
                handleFailedAttempt()
            } catch (e: Exception) {
                repository.lock()
                _authError.value = e.userMessage()
            } finally {
                _busy.value = false
            }
        }
    }

    fun unlockWithRecoveryKey(recoveryText: String) {
        if (_busy.value) return
        val keyBytes = RecoveryKey.parse(recoveryText)
        if (keyBytes == null) {
            _authError.value = "Ключ восстановления введён неверно: нужно 64 символа (группы можно вводить без дефисов)."
            return
        }
        val lockout = settings.remainingLockoutSeconds()
        if (lockout > 0) {
            _authError.value = "Слишком много неверных попыток. Подождите $lockout сек."
            return
        }
        _busy.value = true
        _authError.value = null
        viewModelScope.launch {
            try {
                repository.unlockWithRecovery(keyBytes)  // массив затирается внутри
                settings.resetLockout()
                refreshFromRepo()
                _needsPasswordReset.value = true
                _authState.value = AuthState.UNLOCKED
            } catch (e: WrongPasswordException) {
                handleFailedAttempt()
            } catch (e: Exception) {
                repository.lock()
                _authError.value = e.userMessage()
            } finally {
                _busy.value = false
            }
        }
    }

    /** Вызывается UI после успешной биометрии с авторизованным Cipher. */
    fun unlockWithBiometricCipher(authorizedCipher: Cipher) {
        if (_busy.value) return
        _busy.value = true
        _authError.value = null
        viewModelScope.launch {
            try {
                val dataKey = withContext(Dispatchers.IO) {
                    biometric.extractDataKey(authorizedCipher, biometricFile)
                } ?: throw IllegalStateException("Не удалось расшифровать ключ биометрией")
                try {
                    repository.unlockWithDataKey(dataKey)
                } finally {
                    dataKey.fill(0)
                }
                settings.resetLockout()
                refreshFromRepo()
                _authState.value = AuthState.UNLOCKED
            } catch (e: Exception) {
                repository.lock()
                _authError.value = "Биометрическая разблокировка не удалась. Введите мастер-пароль."
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun handleFailedAttempt() {
        val pause = settings.registerFailedAttempt()
        if (settings.wipeOnFailedAttempts && settings.failedAttempts >= SettingsStore.WIPE_THRESHOLD) {
            repository.wipeEverything()
            settings.biometricEnabled = false
            settings.resetLockout()
            _items.value = emptyList()
            _folders.value = emptyList()
            _authState.value = AuthState.NO_VAULT
            _authError.value = "Хранилище удалено после ${SettingsStore.WIPE_THRESHOLD} неверных попыток (эта опция была включена в настройках)."
            return
        }
        _authError.value = if (pause > 0) {
            "Неверный пароль. Слишком много попыток — подождите $pause сек."
        } else {
            "Неверный пароль. Проверьте раскладку и попробуйте ещё раз."
        }
    }

    // ---------- Блокировка ----------

    fun lockNow() {
        repository.lock()
        _csvSession.value = null  // распарсенные CSV-пароли не переживают блокировку
        _items.value = emptyList()
        _folders.value = emptyList()
        _needsPasswordReset.value = false
        _authState.value = if (repository.vaultExists()) AuthState.LOCKED else AuthState.NO_VAULT
    }

    // --- Системные диалоги (выбор/сохранение файлов SAF) ---
    // Пока открыт системный выбор файлов, приложение формально «в фоне».
    // Без этой паузы автоблокировка выбрасывала пользователя на экран пароля
    // прямо посреди прикрепления файла или экспорта (особенно при режиме
    // «блокировать сразу при сворачивании»), теряя несохранённую форму.
    @Volatile
    private var externalActivityStartedAtMs: Long = 0L

    /** Вызывать НЕПОСРЕДСТВЕННО перед launch() любого системного выбора файла. */
    fun beginExternalActivity() {
        externalActivityStartedAtMs = System.currentTimeMillis()
    }

    /** Вызывать в колбэке результата выбора файла (в т.ч. при отмене — uri == null). */
    fun endExternalActivity() {
        externalActivityStartedAtMs = 0L
        backgroundedAtMs = System.currentTimeMillis()  // таймер автоблокировки стартует заново
    }

    private fun externalActivityInGracePeriod(now: Long): Boolean {
        val started = externalActivityStartedAtMs
        // Льгота не бесконечна: если системный диалог «висел» дольше 30 минут,
        // обычная логика автоблокировки снова вступает в силу.
        return started != 0L && now - started <= EXTERNAL_ACTIVITY_GRACE_MS
    }

    /** Приложение ушло в фон (ProcessLifecycleOwner ON_STOP). */
    fun onAppBackgrounded() {
        val now = System.currentTimeMillis()
        backgroundedAtMs = now
        if (externalActivityInGracePeriod(now)) return  // открыт системный выбор файла — не блокируем
        if (_authState.value == AuthState.UNLOCKED && settings.autoLockMinutes == 0) {
            lockNow()
        } else {
            // Таймаут для службы автофилла считается от ухода приложения в фон.
            repository.touchSession(now)
        }
    }

    /** Приложение вернулось на экран (ON_START). */
    fun onAppForegrounded() {
        // Сначала подтянуться к общей сессии: её могла открыть/закрыть служба автофилла.
        if (repository.isUnlocked && _authState.value != AuthState.UNLOCKED) {
            syncWithRepository()
            return
        }
        if (!repository.isUnlocked && _authState.value == AuthState.UNLOCKED) {
            syncWithRepository()
            return
        }
        if (_authState.value != AuthState.UNLOCKED) return
        val now = System.currentTimeMillis()
        if (externalActivityInGracePeriod(now)) {
            backgroundedAtMs = now  // вернулись из выбора файла — отсчёт автоблокировки сначала
            return
        }
        val minutes = settings.autoLockMinutes
        if (minutes < 0) {
            repository.touchSession(now)
            return  // «никогда»
        }
        if (backgroundedAtMs == 0L) return
        val elapsed = now - backgroundedAtMs
        if (elapsed >= minutes * 60_000L) lockNow() else repository.touchSession(now)
    }

    // ---------- Записи и папки ----------

    private fun refreshFromRepo() {
        val snapshot = repository.data
        _items.value = snapshot?.items ?: emptyList()
        _folders.value = snapshot?.folders ?: emptyList()
    }

    private fun runVaultOperation(errorPrefix: String, block: suspend () -> Unit) {
        if (_authState.value != AuthState.UNLOCKED) return
        viewModelScope.launch {
            try {
                block()
                refreshFromRepo()
            } catch (e: Exception) {
                message("$errorPrefix: ${e.userMessage()}")
            }
        }
    }

    fun saveItem(item: VaultItem, onDone: () -> Unit = {}) {
        runVaultOperation("Не удалось сохранить запись") {
            repository.upsertItem(item)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun deleteItem(itemId: String, onDone: () -> Unit = {}) {
        runVaultOperation("Не удалось удалить запись") {
            repository.deleteItem(itemId)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun toggleFavorite(item: VaultItem) {
        runVaultOperation("Ошибка избранного") {
            repository.setFavorite(item.id, !item.favorite)
        }
    }

    fun markOpened(itemId: String) {
        runVaultOperation("Ошибка") { repository.markOpened(itemId) }
    }

    fun addFolder(name: String) {
        if (name.isBlank()) return
        runVaultOperation("Не удалось создать папку") { repository.addFolder(name) }
    }

    fun deleteFolder(folderId: String) {
        runVaultOperation("Не удалось удалить папку") { repository.deleteFolder(folderId) }
    }

    fun itemById(id: String): VaultItem? = _items.value.firstOrNull { it.id == id }

    // ---------- Буфер обмена ----------

    fun copySecret(label: String, value: String) {
        clipboard.copy(label, value, settings.clipboardClearSeconds)
        val seconds = settings.clipboardClearSeconds
        message(if (seconds > 0) "Скопировано. Буфер очистится через $seconds сек." else "Скопировано в буфер обмена")
    }

    fun copyText(label: String, value: String) {
        clipboard.copy(label, value, settings.clipboardClearSeconds)
        message("Скопировано в буфер обмена")
    }

    // ---------- Вложения ----------

    /**
     * Результат чтения вложения: ровно одно из полей заполнено.
     * readAttachment НИКОГДА не бросает исключений наружу: в v1.0 любая ошибка
     * провайдера файлов (нет доступа, облачный файл, битый Uri) или слишком
     * большой файл приводили к падению приложения при прикреплении.
     */
    data class AttachmentReadResult(
        val attachment: AttachmentMeta? = null,
        val error: String? = null
    )

    /** Прочитать файл через SAF (лимит 2 МБ). Ошибки возвращаются текстом, а не крашем. */
    suspend fun readAttachment(uri: Uri): AttachmentReadResult = withContext(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            var name = "file"
            var declaredSize = -1L
            // Метаданные — best effort: отдельные провайдеры падают на query, это не повод ронять приложение.
            runCatching {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = runCatching { cursor.getString(nameIdx) }.getOrNull() ?: name
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            declaredSize = runCatching { cursor.getLong(sizeIdx) }.getOrDefault(-1L)
                        }
                    }
                }
            }
            if (declaredSize > MAX_ATTACHMENT_BYTES) {
                return@withContext AttachmentReadResult(
                    error = "Файл «$name» слишком большой для вложения (лимит 2 МБ). Выберите файл поменьше."
                )
            }
            val input = runCatching { resolver.openInputStream(uri) }.getOrNull()
                ?: return@withContext AttachmentReadResult(
                    error = "Не удалось открыть файл «$name». Если файл в облаке — сначала скачайте его на телефон и попробуйте снова."
                )
            // Читаем порциями с жёстким лимитом: неизвестный размер больше не грузится в память целиком.
            val bytes = input.use { readBytesLimited(it, MAX_ATTACHMENT_BYTES) }
                ?: return@withContext AttachmentReadResult(
                    error = "Файл «$name» больше лимита вложения (2 МБ) и не был добавлен."
                )
            AttachmentReadResult(
                attachment = AttachmentMeta(
                    fileName = name,
                    mimeType = runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream",
                    sizeBytes = bytes.size.toLong(),
                    contentB64 = Base64.getEncoder().encodeToString(bytes)
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AttachmentReadResult(error = "Не удалось добавить файл: ${e.userMessage()}")
        }
    }

    /** Записать вложение обратно в выбранный пользователем файл (расшифрованный экспорт одного файла). */
    fun exportAttachment(attachment: AttachmentMeta, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = Base64.getDecoder().decode(attachment.contentB64)
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(bytes)
                    } ?: throw IllegalStateException("Не удалось открыть файл для записи")
                }
                message("Файл «${attachment.fileName}» сохранён")
            } catch (e: Exception) {
                message("Не удалось сохранить файл: ${e.userMessage()}")
            }
        }
    }

    // ---------- Безопасность: пароль, восстановление, биометрия ----------

    fun changeMasterPassword(currentPassword: String, newPassword: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val ok = repository.verifyMasterPassword(currentPassword.toCharArray())
                if (!ok) {
                    onResult(false, "Текущий пароль введён неверно")
                    return@launch
                }
                repository.changeMasterPassword(newPassword.toCharArray())
                _needsPasswordReset.value = false
                onResult(true, "Мастер-пароль изменён")
            } catch (e: Exception) {
                onResult(false, e.userMessage())
            }
        }
    }

    /** После входа по ключу восстановления пароль просто заменяется (старый неизвестен). */
    fun setNewMasterPasswordAfterRecovery(newPassword: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                repository.changeMasterPassword(newPassword.toCharArray())
                _needsPasswordReset.value = false
                onResult(true, "Новый мастер-пароль установлен")
            } catch (e: Exception) {
                onResult(false, e.userMessage())
            }
        }
    }

    fun regenerateRecoveryKey(currentPassword: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val ok = repository.verifyMasterPassword(currentPassword.toCharArray())
                if (!ok) {
                    onError("Текущий пароль введён неверно")
                    return@launch
                }
                _pendingRecoveryKey.value = repository.regenerateRecoveryKey()
            } catch (e: Exception) {
                onError(e.userMessage())
            }
        }
    }

    fun hasRecoverySlot(): Boolean = repository.hasRecoverySlot()

    /** UI получил авторизованный Cipher при ВКЛючении биометрии в настройках. */
    fun enableBiometricWithCipher(authorizedCipher: Cipher) {
        viewModelScope.launch {
            try {
                val dataKey = repository.currentDataKeyCopy()
                    ?: throw IllegalStateException("Хранилище заблокировано")
                try {
                    withContext(Dispatchers.IO) {
                        biometric.storeDataKey(authorizedCipher, dataKey, biometricFile)
                    }
                } finally {
                    dataKey.fill(0)
                }
                settings.biometricEnabled = true
                message("Биометрическая разблокировка включена")
            } catch (e: Exception) {
                settings.biometricEnabled = false
                message("Не удалось включить биометрию: ${e.userMessage()}")
            }
        }
    }

    fun disableBiometric(silent: Boolean = false) {
        biometric.deleteBlob(biometricFile)
        settings.biometricEnabled = false
        if (!silent) message("Биометрическая разблокировка отключена")
    }

    fun biometricReadyToUse(): Boolean =
        settings.biometricEnabled && biometric.isAvailable() && biometric.hasBlob(biometricFile)

    // ---------- Резервные копии ----------

    fun exportBackup(uri: Uri, backupPassword: String, onResult: (Boolean, String) -> Unit) {
        _busy.value = true
        viewModelScope.launch {
            try {
                val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Не удалось открыть файл для записи")
                repository.exportBackup(output, backupPassword.toCharArray())
                onResult(true, "Зашифрованная резервная копия создана")
            } catch (e: Exception) {
                onResult(false, "Экспорт не удался: ${e.userMessage()}")
            } finally {
                _busy.value = false
            }
        }
    }

    fun importBackup(
        uri: Uri,
        backupPassword: String?,
        recoveryText: String?,
        newMasterPassword: String,
        onResult: (Boolean, String) -> Unit
    ) {
        _busy.value = true
        viewModelScope.launch {
            try {
                val resolver = getApplication<Application>().contentResolver
                if (!recoveryText.isNullOrBlank()) {
                    val keyBytes = RecoveryKey.parse(recoveryText)
                        ?: throw IllegalArgumentException("Ключ восстановления имеет неверный формат (нужно 64 символа)")
                    val input = resolver.openInputStream(uri)
                        ?: throw IllegalStateException("Не удалось открыть файл")
                    repository.importBackupWithRecovery(input, keyBytes, newMasterPassword.toCharArray())
                } else {
                    if (backupPassword.isNullOrEmpty()) {
                        throw IllegalArgumentException("Введите пароль резервной копии или ключ восстановления")
                    }
                    val input = resolver.openInputStream(uri)
                        ?: throw IllegalStateException("Не удалось открыть файл")
                    repository.importBackup(input, backupPassword.toCharArray(), newMasterPassword.toCharArray())
                }
                settings.biometricEnabled = false  // blob старого хранилища уже удалён в репозитории
                settings.resetLockout()
                refreshFromRepo()
                _authState.value = AuthState.UNLOCKED
                onResult(true, "Хранилище восстановлено из резервной копии")
            } catch (e: WrongPasswordException) {
                onResult(false, "Неверный пароль резервной копии или ключ восстановления")
            } catch (e: Exception) {
                onResult(false, "Импорт не удался: ${e.userMessage()}")
            } finally {
                _busy.value = false
            }
        }
    }

    // ---------- Импорт паролей из CSV браузера ----------

    private val _csvSession = MutableStateFlow<CsvImportSession?>(null)
    val csvSession: StateFlow<CsvImportSession?> = _csvSession.asStateFlow()

    /** Прочитать и распарсить CSV через SAF. Файл не копируется и не логируется. */
    fun loadCsvForImport(uri: Uri, onResult: (Boolean, String) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val resolver = getApplication<Application>().contentResolver
                val fileName = queryDisplayName(uri) ?: "passwords.csv"
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > CsvPasswordImporter.MAX_BYTES) {
                                throw IllegalArgumentException("Файл слишком большой (лимит 10 МБ)")
                            }
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    } ?: throw IllegalStateException("Не удалось открыть выбранный файл")
                }
                val session = withContext(Dispatchers.Default) {
                    makeCsvSession(CsvPasswordImporter.decodeBytes(bytes), fileName)
                }
                // Даже при 0 кандидатах открываем экран: столбцы можно назначить вручную.
                _csvSession.value = session
                onResult(true, "Файл прочитан: строк данных ${session.parsed.rows.size}")
            } catch (e: Exception) {
                onResult(false, "Не удалось прочитать CSV: ${e.userMessage()}")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Собрать сессию импорта из уже декодированного текста CSV. */
    private fun makeCsvSession(text: String, fileName: String): CsvImportSession {
        val parsed = CsvPasswordImporter.parse(text)
        if (parsed.rows.isEmpty()) {
            throw IllegalArgumentException("Не найдено строк с данными")
        }
        val mapping = CsvPasswordImporter.detectMapping(parsed)
        return CsvImportSession(
            fileName = fileName,
            parsed = parsed,
            format = CsvPasswordImporter.detectFormat(parsed),
            mapping = mapping,
            candidates = CsvPasswordImporter.mapRows(parsed, mapping)
        )
    }

    /**
     * Запасной путь, если системный выбор файлов на устройстве не работает:
     * пользователь вставляет содержимое CSV текстом (например, открыв файл
     * в текстовом редакторе и скопировав всё).
     */
    fun loadCsvFromText(csvText: String, onResult: (Boolean, String) -> Unit) {
        if (_busy.value) return
        if (csvText.length > CsvPasswordImporter.MAX_BYTES) {
            onResult(false, "Вставленный текст слишком большой (лимит 10 МБ).")
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.Default) {
                    makeCsvSession(csvText, fileName = "Вставленный CSV")
                }
                _csvSession.value = session
                onResult(true, "Текст разобран: строк данных ${session.parsed.rows.size}")
            } catch (e: Exception) {
                onResult(false, "Не удалось разобрать CSV: ${e.userMessage()}")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Пользователь вручную переназначил столбцы — пересобираем список кандидатов. */
    fun updateCsvMapping(mapping: ColumnMapping) {
        val session = _csvSession.value ?: return
        _csvSession.value = session.copy(
            mapping = mapping,
            candidates = CsvPasswordImporter.mapRows(session.parsed, mapping)
        )
    }

    fun clearCsvImport() {
        _csvSession.value = null
    }

    /** Ключи уже хранящихся записей для пометки дубликатов в предпросмотре. */
    fun csvExistingKeys(): Set<String> =
        _items.value.map { CsvPasswordImporter.existingDedupKey(it) }.toSet()

    /** Выполнить импорт выбранных кандидатов одним атомарным сохранением. */
    fun importCsvCandidates(
        selected: List<ImportCandidate>,
        folderId: String?,
        tagsText: String,
        skipDuplicates: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val tags = tagsText.split(',', ';', '#')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val existingKeys = csvExistingKeys().toMutableSet()
                val toAdd = mutableListOf<VaultItem>()
                var skipped = 0
                for (candidate in selected) {
                    val key = candidate.dedupKey()
                    if (skipDuplicates && key in existingKeys) {
                        skipped++
                        continue
                    }
                    if (skipDuplicates) existingKeys.add(key)
                    toAdd += CsvPasswordImporter.candidateToItem(candidate, folderId, tags)
                }
                repository.addItems(toAdd)
                refreshFromRepo()
                clearCsvImport()
                val suffix = if (skipped > 0) ", пропущено дубликатов: $skipped" else ""
                onResult(true, "Импортировано записей: ${toAdd.size}$suffix")
            } catch (e: Exception) {
                onResult(false, "Импорт не удался: ${e.userMessage()}")
            } finally {
                _busy.value = false
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }

    // ---------- Настройки и удаление ----------

    fun setThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        _themeMode.value = mode
    }

    /** Окончательное удаление хранилища требует ввода текущего мастер-пароля. */
    fun wipeVault(currentPassword: String, onResult: (Boolean, String) -> Unit) {
        _busy.value = true
        viewModelScope.launch {
            try {
                val ok = repository.verifyMasterPassword(currentPassword.toCharArray())
                if (!ok) {
                    onResult(false, "Пароль введён неверно — удаление отменено")
                    return@launch
                }
                repository.wipeEverything()
                biometric.deleteBlob(biometricFile)
                settings.biometricEnabled = false
                settings.resetLockout()
                _items.value = emptyList()
                _folders.value = emptyList()
                _authState.value = AuthState.NO_VAULT
                onResult(true, "Хранилище полностью удалено с этого устройства")
            } catch (e: Exception) {
                onResult(false, e.userMessage())
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024
        const val EXTERNAL_ACTIVITY_GRACE_MS = 30 * 60_000L
    }
}

/**
 * Чтение потока порциями с жёстким лимитом. Возвращает null, как только поток
 * превысил maxBytes, НЕ загружая остаток в память (защита от OOM на больших файлах
 * с неизвестным размером — облачные провайдеры часто не отдают SIZE).
 */
internal fun readBytesLimited(input: java.io.InputStream, maxBytes: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        total += read
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** Понятные человеку тексты ошибок без технических деталей и без секретов. */
private fun Exception.userMessage(): String = when (this) {
    is WrongPasswordException -> "Неверный пароль"
    is CorruptedVaultException -> message ?: "Файл хранилища повреждён"
    is IllegalArgumentException -> message ?: "Некорректные данные"
    is IllegalStateException -> message ?: "Операция сейчас недоступна"
    else -> message ?: "Произошла ошибка"
}
