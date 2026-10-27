package com.noxvault.app.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Формат зашифрованного контейнера NOX Vault (.nox), версия 1.
 * Полное описание — docs/FORMAT.md.
 *
 * Структура файла:
 *   [0..3]   magic "NOXV"
 *   [4]      версия формата = 1
 *   [5..8]   длина заголовка (big-endian int)
 *   [9..]    заголовок JSON (НЕ секретный: только соли, nonce и ОБЁРНУТЫЕ ключи)
 *   [...]    шифртекст AES-256-GCM + тег 128 бит (всё содержимое хранилища)
 *
 * Схема ключей (envelope encryption):
 *   мастер-пароль --Argon2id(соль)--> KEK --AES-GCM--> обёрнутый DataKey
 *   ключ восстановления (256 бит случайных) --AES-GCM--> обёрнутый DataKey (2-й слот)
 *   DataKey --AES-256-GCM(свежий nonce на каждое сохранение)--> все данные
 *
 * Открытые данные в заголовке допустимы: соли и nonce не секретны,
 * а DataKey хранится только в зашифрованном (обёрнутом) виде.
 */

open class VaultException(message: String) : Exception(message)
class WrongPasswordException : VaultException("Неверный пароль или ключ восстановления")
class CorruptedVaultException(detail: String = "") : VaultException("Файл хранилища повреждён. $detail".trim())
class UnsupportedVersionException(version: Int) :
    VaultException("Версия формата $version не поддерживается этой версией приложения")

/** Слот пароля: всё, что нужно, чтобы из пароля получить DataKey. */
@Serializable
data class PasswordSlot(
    val kdf: String = KeyDerivation.KDF_ARGON2ID,
    val saltB64: String,
    val memoryKb: Int = KeyDerivation.DEFAULT_MEMORY_KB,
    val iterations: Int = KeyDerivation.DEFAULT_ITERATIONS,
    val parallelism: Int = KeyDerivation.DEFAULT_PARALLELISM,
    val wrapNonceB64: String,
    val wrappedDataKeyB64: String
)

/** Заголовок контейнера (сериализуется в JSON внутри файла). */
@Serializable
data class ContainerHeader(
    val formatVersion: Int = VaultContainerCodec.FORMAT_VERSION,
    val createdAtEpochMs: Long,
    val isBackup: Boolean = false,
    val passwordSlot: PasswordSlot,
    val recoveryWrapNonceB64: String? = null,
    val recoveryWrappedDataKeyB64: String? = null,
    val payloadNonceB64: String
)

/** Результат успешной разблокировки контейнера. */
data class UnlockedContainer(
    val dataKey: ByteArray,
    val plaintext: ByteArray,
    val header: ContainerHeader
) {
    override fun equals(other: Any?): Boolean =
        other is UnlockedContainer && dataKey.contentEquals(other.dataKey) &&
            plaintext.contentEquals(other.plaintext) && header == other.header

    override fun hashCode(): Int = header.hashCode()
}

/** Результат создания нового хранилища: recoveryKey показывается пользователю ОДИН раз. */
data class CreatedContainer(
    val bytes: ByteArray,
    val dataKey: ByteArray,
    val recoveryKey: ByteArray,
    val header: ContainerHeader
) {
    override fun equals(other: Any?): Boolean =
        other is CreatedContainer && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

object VaultContainerCodec {

    const val FORMAT_VERSION = 1
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'O'.code.toByte(), 'X'.code.toByte(), 'V'.code.toByte())
    private const val MAX_HEADER_BYTES = 65_536
    private const val MAX_CONTAINER_BYTES = 64 * 1024 * 1024  // защита от абсурдных файлов при импорте

    private val json = Json {
        ignoreUnknownKeys = true   // прямая совместимость: новые поля заголовка не ломают чтение
        encodeDefaults = true
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String): ByteArray = try {
        Base64.getDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        throw CorruptedVaultException("Некорректный Base64 в заголовке")
    }

    // ---------- Создание ----------

    /** Создать слот пароля для заданного DataKey (свежая соль и nonce каждый раз). */
    fun createPasswordSlot(password: CharArray, dataKey: ByteArray): PasswordSlot {
        val salt = CryptoManager.generateSalt()
        val params = KeyDerivation.KdfParams()
        val kek = KeyDerivation.deriveKey(password, salt, params)
        try {
            val wrapped = CryptoManager.wrapKey(kek, dataKey, CryptoAad.PASSWORD_SLOT)
            return PasswordSlot(
                kdf = params.type,
                saltB64 = b64(salt),
                memoryKb = params.memoryKb,
                iterations = params.iterations,
                parallelism = params.parallelism,
                wrapNonceB64 = b64(wrapped.nonce),
                wrappedDataKeyB64 = b64(wrapped.ciphertext)
            )
        } finally {
            CryptoManager.wipe(kek)
        }
    }

    /** Обернуть DataKey ключом восстановления. Возвращает (nonceB64, wrappedB64). */
    fun createRecoveryWrap(recoveryKey: ByteArray, dataKey: ByteArray): Pair<String, String> {
        val wrapped = CryptoManager.wrapKey(recoveryKey, dataKey, CryptoAad.RECOVERY_SLOT)
        return b64(wrapped.nonce) to b64(wrapped.ciphertext)
    }

    /** Полностью новое хранилище: новый DataKey + новый ключ восстановления. */
    fun createNew(password: CharArray, plaintext: ByteArray): CreatedContainer {
        val dataKey = CryptoManager.generateDataKey()
        val recoveryKey = RecoveryKey.generate()
        val slot = createPasswordSlot(password, dataKey)
        val (recNonce, recWrapped) = createRecoveryWrap(recoveryKey, dataKey)
        val headerWithoutNonce = ContainerHeader(
            createdAtEpochMs = System.currentTimeMillis(),
            isBackup = false,
            passwordSlot = slot,
            recoveryWrapNonceB64 = recNonce,
            recoveryWrappedDataKeyB64 = recWrapped,
            payloadNonceB64 = ""  // заполняется в assemble()
        )
        val (bytes, header) = assemble(headerWithoutNonce, dataKey, plaintext)
        return CreatedContainer(bytes, dataKey, recoveryKey, header)
    }

    /**
     * Собрать файл контейнера из существующих слотов и новых данных.
     * Используется при каждом сохранении: payload всегда шифруется со СВЕЖИМ nonce.
     */
    fun assemble(template: ContainerHeader, dataKey: ByteArray, plaintext: ByteArray): Pair<ByteArray, ContainerHeader> {
        val payloadNonce = CryptoManager.generateNonce()
        val header = template.copy(payloadNonceB64 = b64(payloadNonce))
        val ciphertext = CryptoManager.aesGcmEncrypt(dataKey, payloadNonce, plaintext, CryptoAad.PAYLOAD)
        val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
        require(headerBytes.size <= MAX_HEADER_BYTES) { "Заголовок слишком большой" }
        val buffer = ByteBuffer.allocate(MAGIC.size + 1 + 4 + headerBytes.size + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(FORMAT_VERSION.toByte())
        buffer.putInt(headerBytes.size)
        buffer.put(headerBytes)
        buffer.put(ciphertext)
        return buffer.array() to header
    }

    /**
     * Резервная копия с ОТДЕЛЬНЫМ паролем бэкапа.
     * DataKey тот же => recovery-слот копируется как есть и продолжит работать после импорта.
     * Копия не зависит от Android Keystore исходного устройства.
     */
    fun createBackup(
        sourceHeader: ContainerHeader,
        dataKey: ByteArray,
        plaintext: ByteArray,
        backupPassword: CharArray
    ): ByteArray {
        val slot = createPasswordSlot(backupPassword, dataKey)
        val header = ContainerHeader(
            createdAtEpochMs = System.currentTimeMillis(),
            isBackup = true,
            passwordSlot = slot,
            recoveryWrapNonceB64 = sourceHeader.recoveryWrapNonceB64,
            recoveryWrappedDataKeyB64 = sourceHeader.recoveryWrappedDataKeyB64,
            payloadNonceB64 = ""
        )
        return assemble(header, dataKey, plaintext).first
    }

    // ---------- Чтение / разблокировка ----------

    private data class Parsed(val header: ContainerHeader, val ciphertext: ByteArray)

    /** Разобрать заголовок без расшифровки (проверки формата при импорте). */
    fun parseHeader(bytes: ByteArray): ContainerHeader = parse(bytes).header

    fun isProbablyContainer(bytes: ByteArray): Boolean = try {
        parse(bytes); true
    } catch (e: Exception) {
        false
    }

    private fun parse(bytes: ByteArray): Parsed {
        if (bytes.size > MAX_CONTAINER_BYTES) throw CorruptedVaultException("Файл слишком большой")
        if (bytes.size < MAGIC.size + 1 + 4 + 2) throw CorruptedVaultException("Файл слишком маленький")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4); buffer.get(magic)
        if (!magic.contentEquals(MAGIC)) throw CorruptedVaultException("Это не файл NOX Vault")
        val version = buffer.get().toInt()
        if (version != FORMAT_VERSION) throw UnsupportedVersionException(version)
        val headerLen = buffer.int
        if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES || headerLen > buffer.remaining()) {
            throw CorruptedVaultException("Некорректная длина заголовка")
        }
        val headerBytes = ByteArray(headerLen); buffer.get(headerBytes)
        val header = try {
            json.decodeFromString<ContainerHeader>(String(headerBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw CorruptedVaultException("Заголовок повреждён")
        }
        val ciphertext = ByteArray(buffer.remaining()); buffer.get(ciphertext)
        if (ciphertext.size < 16) throw CorruptedVaultException("Отсутствуют зашифрованные данные")
        return Parsed(header, ciphertext)
    }

    private fun decryptPayload(parsed: Parsed, dataKey: ByteArray): ByteArray {
        return try {
            CryptoManager.aesGcmDecrypt(
                dataKey,
                unb64(parsed.header.payloadNonceB64),
                parsed.ciphertext,
                CryptoAad.PAYLOAD
            )
        } catch (e: AEADBadTagException) {
            throw CorruptedVaultException("Проверка целостности не пройдена: данные изменены или повреждены")
        }
    }

    /** Разблокировка мастер-паролем (или паролем резервной копии — слот устроен одинаково). */
    fun unlockWithPassword(bytes: ByteArray, password: CharArray): UnlockedContainer {
        val parsed = parse(bytes)
        val slot = parsed.header.passwordSlot
        val params = KeyDerivation.KdfParams(
            type = slot.kdf,
            memoryKb = slot.memoryKb,
            iterations = slot.iterations,
            parallelism = slot.parallelism
        )
        val kek = KeyDerivation.deriveKey(password, unb64(slot.saltB64), params)
        val dataKey = try {
            CryptoManager.unwrapKey(
                kek,
                CryptoManager.WrappedKey(unb64(slot.wrapNonceB64), unb64(slot.wrappedDataKeyB64)),
                CryptoAad.PASSWORD_SLOT
            )
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        } finally {
            CryptoManager.wipe(kek)
        }
        return UnlockedContainer(dataKey, decryptPayload(parsed, dataKey), parsed.header)
    }

    /** Разблокировка ключом восстановления (если мастер-пароль забыт). */
    fun unlockWithRecoveryKey(bytes: ByteArray, recoveryKey: ByteArray): UnlockedContainer {
        val parsed = parse(bytes)
        val nonceB64 = parsed.header.recoveryWrapNonceB64
        val wrappedB64 = parsed.header.recoveryWrappedDataKeyB64
        if (nonceB64 == null || wrappedB64 == null) {
            throw VaultException("В этом хранилище нет слота восстановления")
        }
        val dataKey = try {
            CryptoManager.unwrapKey(
                recoveryKey,
                CryptoManager.WrappedKey(unb64(nonceB64), unb64(wrappedB64)),
                CryptoAad.RECOVERY_SLOT
            )
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
        return UnlockedContainer(dataKey, decryptPayload(parsed, dataKey), parsed.header)
    }

    /** Расшифровка уже известным DataKey (путь биометрической разблокировки). */
    fun unlockWithDataKey(bytes: ByteArray, dataKey: ByteArray): UnlockedContainer {
        val parsed = parse(bytes)
        return UnlockedContainer(dataKey.copyOf(), decryptPayload(parsed, dataKey), parsed.header)
    }
}
