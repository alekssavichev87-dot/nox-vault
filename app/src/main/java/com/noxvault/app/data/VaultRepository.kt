package com.noxvault.app.data

import android.content.Context
import com.noxvault.app.crypto.ContainerHeader
import com.noxvault.app.crypto.CryptoManager
import com.noxvault.app.crypto.RecoveryKey
import com.noxvault.app.crypto.VaultContainerCodec
import com.noxvault.app.crypto.WrongPasswordException
import com.noxvault.app.domain.model.Folder
import com.noxvault.app.domain.model.VaultData
import com.noxvault.app.domain.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Единая точка работы с хранилищем (Repository pattern).
 * ВСЕ операции чтения/записи проходят через этот класс: UI никогда не трогает
 * файлы и криптографию напрямую. Расшифрованные данные и DataKey живут только
 * в памяти этого объекта и очищаются при lock().
 *
 * Тяжёлая криптография (Argon2id) выполняется на Dispatchers.Default,
 * файловая запись — на Dispatchers.IO. Mutex сериализует операции,
 * чтобы параллельные сохранения не повредили контейнер.
 */
class VaultRepository(private val storage: VaultStorage) {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // --- Состояние только в памяти; null == заблокировано ---
    // @Volatile: хранилищем теперь пользуются два потока — UI и binder-потоки
    // Android Autofill Framework. Сами данные неизменяемы и заменяются целиком
    // под Mutex, поэтому чтение volatile-ссылки службой безопасно.
    @Volatile private var dataKey: ByteArray? = null
    @Volatile private var header: ContainerHeader? = null

    @Volatile var data: VaultData? = null
        private set

    /** Когда сессия последний раз была заведомо активна на переднем плане. */
    @Volatile var lastSessionActivityMs: Long = 0L
        private set

    val isUnlocked: Boolean get() = dataKey != null && data != null

    /** Отметить активность открытой сессии (для таймаута службы автофилла). */
    fun touchSession(now: Long = System.currentTimeMillis()) {
        if (isUnlocked) lastSessionActivityMs = now
    }

    fun vaultExists(): Boolean = storage.exists()

    /** Текущий DataKey нужен только BiometricHelper при включении биометрии. Отдаём копию. */
    fun currentDataKeyCopy(): ByteArray? = dataKey?.copyOf()

    fun hasRecoverySlot(): Boolean = header?.recoveryWrappedDataKeyB64 != null

    // ---------- Создание / разблокировка / блокировка ----------

    /**
     * Создать новое пустое хранилище.
     * @return ключ восстановления для ЕДИНСТВЕННОГО показа пользователю.
     */
    suspend fun createVault(masterPassword: CharArray): String = mutex.withLock {
        try {
            val empty = VaultData()
            val plaintext = json.encodeToString(empty).toByteArray(Charsets.UTF_8)
            val created = withContext(Dispatchers.Default) {
                VaultContainerCodec.createNew(masterPassword, plaintext)
            }
            withContext(Dispatchers.IO) { storage.atomicWrite(created.bytes) }
            val recoveryFormatted = RecoveryKey.formatForDisplay(created.recoveryKey)
            CryptoManager.wipe(created.recoveryKey)
            this.dataKey = created.dataKey
            this.header = created.header
            this.data = empty
            this.lastSessionActivityMs = System.currentTimeMillis()
            recoveryFormatted
        } finally {
            CryptoManager.wipe(masterPassword)
        }
    }

    /**
     * Разблокировка мастер-паролем. Если основной файл повреждён,
     * пробуем предыдущую хорошую версию (.bak) и восстанавливаем её.
     * @throws WrongPasswordException при неверном пароле
     */
    suspend fun unlock(masterPassword: CharArray) = mutex.withLock {
        try {
            val bytes = withContext(Dispatchers.IO) { storage.readMain() }
                ?: throw IllegalStateException("Хранилище не существует")
            try {
                applyUnlocked(withContext(Dispatchers.Default) {
                    VaultContainerCodec.unlockWithPassword(bytes, masterPassword)
                })
            } catch (e: WrongPasswordException) {
                // Пароль может быть верным для .bak, если основной файл повреждён записью.
                if (!tryUnlockFromPreviousGood { bakBytes ->
                        VaultContainerCodec.unlockWithPassword(bakBytes, masterPassword)
                    }
                ) throw e
            }
        } finally {
            CryptoManager.wipe(masterPassword)
        }
    }

    /** Разблокировка ключом восстановления (пароль забыт). Далее UI заставляет задать новый пароль. */
    suspend fun unlockWithRecovery(recoveryKeyBytes: ByteArray) = mutex.withLock {
        try {
            val bytes = withContext(Dispatchers.IO) { storage.readMain() }
                ?: throw IllegalStateException("Хранилище не существует")
            try {
                applyUnlocked(withContext(Dispatchers.Default) {
                    VaultContainerCodec.unlockWithRecoveryKey(bytes, recoveryKeyBytes)
                })
            } catch (e: WrongPasswordException) {
                if (!tryUnlockFromPreviousGood { bakBytes ->
                        VaultContainerCodec.unlockWithRecoveryKey(bakBytes, recoveryKeyBytes)
                    }
                ) throw e
            }
        } finally {
            CryptoManager.wipe(recoveryKeyBytes)
        }
    }

    /** Разблокировка готовым DataKey (путь после успешной биометрии). Ключ-переданный затирается вызывающим. */
    suspend fun unlockWithDataKey(key: ByteArray) = mutex.withLock {
        val bytes = withContext(Dispatchers.IO) { storage.readMain() }
            ?: throw IllegalStateException("Хранилище не существует")
        try {
            applyUnlocked(withContext(Dispatchers.Default) {
                VaultContainerCodec.unlockWithDataKey(bytes, key)
            })
        } catch (e: Exception) {
            if (!tryUnlockFromPreviousGood { bakBytes ->
                    VaultContainerCodec.unlockWithDataKey(bakBytes, key)
                }
            ) throw e
        }
    }

    private suspend fun tryUnlockFromPreviousGood(
        attempt: (ByteArray) -> com.noxvault.app.crypto.UnlockedContainer
    ): Boolean {
        val bakBytes = withContext(Dispatchers.IO) { storage.readPreviousGood() } ?: return false
        return try {
            val unlocked = withContext(Dispatchers.Default) { attempt(bakBytes) }
            withContext(Dispatchers.IO) { storage.atomicWrite(bakBytes) }  // восстановить основной файл
            applyUnlocked(unlocked)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun applyUnlocked(unlocked: com.noxvault.app.crypto.UnlockedContainer) {
        val parsed = json.decodeFromString<VaultData>(String(unlocked.plaintext, Charsets.UTF_8))
        this.dataKey = unlocked.dataKey
        this.header = unlocked.header
        this.data = parsed
        this.lastSessionActivityMs = System.currentTimeMillis()
    }

    /** Блокировка: стереть ключ и расшифрованные данные из памяти (best-effort). */
    fun lock() {
        CryptoManager.wipe(dataKey)
        dataKey = null
        header = null
        data = null
        lastSessionActivityMs = 0L
    }

    /** Проверить текущий мастер-пароль (для смены пароля и опасных операций). */
    suspend fun verifyMasterPassword(password: CharArray): Boolean = mutex.withLock {
        try {
            val key = dataKey ?: return@withLock false
            val bytes = withContext(Dispatchers.IO) { storage.readMain() } ?: return@withLock false
            return@withLock try {
                val unlocked = withContext(Dispatchers.Default) {
                    VaultContainerCodec.unlockWithPassword(bytes, password)
                }
                val ok = unlocked.dataKey.contentEquals(key)
                CryptoManager.wipe(unlocked.dataKey)
                ok
            } catch (e: WrongPasswordException) {
                false
            }
        } finally {
            CryptoManager.wipe(password)
        }
    }

    // ---------- Сохранение и CRUD ----------

    /** Перешифровать и атомарно сохранить текущее содержимое. Вызывается после каждого изменения. */
    private suspend fun persistLocked() {
        val key = dataKey ?: throw IllegalStateException("Хранилище заблокировано")
        val currentHeader = header ?: throw IllegalStateException("Хранилище заблокировано")
        val currentData = data ?: throw IllegalStateException("Хранилище заблокировано")
        val plaintext = json.encodeToString(currentData).toByteArray(Charsets.UTF_8)
        val (bytes, newHeader) = withContext(Dispatchers.Default) {
            VaultContainerCodec.assemble(currentHeader, key, plaintext)
        }
        withContext(Dispatchers.IO) { storage.atomicWrite(bytes) }
        header = newHeader
    }

    suspend fun upsertItem(item: VaultItem) = mutex.withLock {
        val current = data ?: throw IllegalStateException("Хранилище заблокировано")
        val items = current.items.toMutableList()
        val index = items.indexOfFirst { it.id == item.id }
        val stamped = item.copy(updatedAt = System.currentTimeMillis())
        if (index >= 0) items[index] = stamped else items += stamped
        data = current.copy(items = items)
        persistLocked()
    }

    /** Пакетное добавление (импорт CSV): все записи пишутся ОДНИМ атомарным сохранением. */
    suspend fun addItems(newItems: List<VaultItem>) = mutex.withLock {
        if (newItems.isEmpty()) return@withLock
        val current = data ?: throw IllegalStateException("Хранилище заблокировано")
        data = current.copy(items = current.items + newItems)
        persistLocked()
    }

    suspend fun deleteItem(itemId: String) = mutex.withLock {
        val current = data ?: throw IllegalStateException("Хранилище заблокировано")
        val items = current.items.filterNot { it.id == itemId }
            .map { if (itemId in it.linkedIds) it.copy(linkedIds = it.linkedIds - itemId) else it }
        data = current.copy(items = items)
        persistLocked()
    }

    suspend fun setFavorite(itemId: String, favorite: Boolean) = mutex.withLock {
        val current = data ?: throw IllegalStateException("Хранилище заблокировано")
        data = current.copy(items = current.items.map {
            if (it.id == itemId) it.copy(favorite = favorite) else it
        })
        persistLocked()
    }

    suspend fun markOpened(itemId: String) = mutex.withLock {
        val current = data ?: return@withLock
        data = current.copy(items = current.items.map {
            if (it.id == itemId) it.copy(lastOpenedAt = System.currentTimeMillis()) else it
        })
        persistLocked()
    }

    suspend fun addFolder(name: String): Folder = mutex.withLock {
        val current = data ?: throw IllegalStateException("Хранилище заблокировано")
        val folder = Folder(name = name.trim())
        data = current.copy(folders = current.folders + folder)
        persistLocked()
        folder
    }

    suspend fun deleteFolder(folderId: String) = mutex.withLock {
        val current = data ?: throw IllegalStateException("Хранилище заблокировано")
        data = current.copy(
            folders = current.folders.filterNot { it.id == folderId },
            items = current.items.map { if (it.folderId == folderId) it.copy(folderId = null) else it }
        )
        persistLocked()
    }

    // ---------- Пароль и восстановление ----------

    /** Смена мастер-пароля: DataKey НЕ меняется, меняется только его обёртка. Быстро и безопасно. */
    suspend fun changeMasterPassword(newPassword: CharArray) = mutex.withLock {
        try {
            val key = dataKey ?: throw IllegalStateException("Хранилище заблокировано")
            val currentHeader = header ?: throw IllegalStateException("Хранилище заблокировано")
            val newSlot = withContext(Dispatchers.Default) {
                VaultContainerCodec.createPasswordSlot(newPassword, key)
            }
            header = currentHeader.copy(passwordSlot = newSlot)
            persistLocked()
        } finally {
            CryptoManager.wipe(newPassword)
        }
    }

    /** Новый ключ восстановления (старый немедленно перестаёт работать). */
    suspend fun regenerateRecoveryKey(): String = mutex.withLock {
        val key = dataKey ?: throw IllegalStateException("Хранилище заблокировано")
        val currentHeader = header ?: throw IllegalStateException("Хранилище заблокировано")
        val newRecovery = RecoveryKey.generate()
        val (nonceB64, wrappedB64) = VaultContainerCodec.createRecoveryWrap(newRecovery, key)
        header = currentHeader.copy(
            recoveryWrapNonceB64 = nonceB64,
            recoveryWrappedDataKeyB64 = wrappedB64
        )
        persistLocked()
        val formatted = RecoveryKey.formatForDisplay(newRecovery)
        CryptoManager.wipe(newRecovery)
        formatted
    }

    // ---------- Резервные копии (.nox) ----------

    /** Экспорт: отдельный пароль резервной копии, SAF-поток задаёт UI. */
    suspend fun exportBackup(output: OutputStream, backupPassword: CharArray) = mutex.withLock {
        try {
            val key = dataKey ?: throw IllegalStateException("Хранилище заблокировано")
            val currentHeader = header ?: throw IllegalStateException("Хранилище заблокировано")
            val currentData = data ?: throw IllegalStateException("Хранилище заблокировано")
            val plaintext = json.encodeToString(currentData).toByteArray(Charsets.UTF_8)
            val backupBytes = withContext(Dispatchers.Default) {
                VaultContainerCodec.createBackup(currentHeader, key, plaintext, backupPassword)
            }
            withContext(Dispatchers.IO) {
                output.use { it.write(backupBytes) }
            }
        } finally {
            CryptoManager.wipe(backupPassword)
        }
    }

    /**
     * Импорт резервной копии: ЗАМЕНЯЕТ текущее хранилище.
     * Пользователь задаёт новый мастер-пароль для этого устройства;
     * ключ восстановления из копии продолжает работать.
     */
    suspend fun importBackup(
        input: InputStream,
        backupPassword: CharArray,
        newMasterPassword: CharArray
    ) = mutex.withLock {
        try {
            val backupBytes = withContext(Dispatchers.IO) { input.use { it.readBytes() } }
            // Проверка целостности/версии произойдёт внутри unlock (GCM-тег + parse).
            val unlocked = withContext(Dispatchers.Default) {
                VaultContainerCodec.unlockWithPassword(backupBytes, backupPassword)
            }
            applyImported(unlocked, newMasterPassword)
        } finally {
            CryptoManager.wipe(backupPassword)
            CryptoManager.wipe(newMasterPassword)
        }
    }

    /** Импорт, когда пароль копии забыт, но есть ключ восстановления. */
    suspend fun importBackupWithRecovery(
        input: InputStream,
        recoveryKeyBytes: ByteArray,
        newMasterPassword: CharArray
    ) = mutex.withLock {
        try {
            val backupBytes = withContext(Dispatchers.IO) { input.use { it.readBytes() } }
            val unlocked = withContext(Dispatchers.Default) {
                VaultContainerCodec.unlockWithRecoveryKey(backupBytes, recoveryKeyBytes)
            }
            applyImported(unlocked, newMasterPassword)
        } finally {
            CryptoManager.wipe(recoveryKeyBytes)
            CryptoManager.wipe(newMasterPassword)
        }
    }

    private suspend fun applyImported(
        unlocked: com.noxvault.app.crypto.UnlockedContainer,
        newMasterPassword: CharArray
    ) {
        val parsed = json.decodeFromString<VaultData>(String(unlocked.plaintext, Charsets.UTF_8))
        val newSlot = withContext(Dispatchers.Default) {
            VaultContainerCodec.createPasswordSlot(newMasterPassword, unlocked.dataKey)
        }
        lock()  // стереть старое состояние перед заменой
        this.dataKey = unlocked.dataKey
        this.header = ContainerHeader(
            createdAtEpochMs = unlocked.header.createdAtEpochMs,
            isBackup = false,
            passwordSlot = newSlot,
            recoveryWrapNonceB64 = unlocked.header.recoveryWrapNonceB64,
            recoveryWrappedDataKeyB64 = unlocked.header.recoveryWrappedDataKeyB64,
            payloadNonceB64 = unlocked.header.payloadNonceB64
        )
        this.data = parsed
        this.lastSessionActivityMs = System.currentTimeMillis()
        persistLocked()  // записать как локальное хранилище этого устройства
        storage.deleteBiometricBlob()  // биометрия старого хранилища недействительна
    }

    /** Полное удаление хранилища с устройства (необратимо). */
    suspend fun wipeEverything() = mutex.withLock {
        lock()
        withContext(Dispatchers.IO) { storage.deleteAll() }
    }

    companion object {
        @Volatile private var instance: VaultRepository? = null

        /**
         * Один Repository на процесс: главный UI, AutofillAuthActivity и служба
         * автозаполнения обязаны видеть ОДНУ и ту же сессию в памяти, иначе автофилл
         * не узнает, что хранилище уже разблокировано в приложении (и наоборот).
         * Никаких ключей на диске синглтон не создаёт — это тот же in-memory объект.
         */
        fun getInstance(context: Context): VaultRepository =
            instance ?: synchronized(this) {
                instance ?: VaultRepository(VaultStorage(context.applicationContext)).also { instance = it }
            }
    }
}
