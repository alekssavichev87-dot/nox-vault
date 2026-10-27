package com.noxvault.app.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Низкоуровневые криптографические примитивы NOX Vault.
 *
 * Используется только проверенная криптография из JDK/Android:
 *  - AES-256-GCM (шифрование + проверка целостности, тег 128 бит);
 *  - SecureRandom (криптографически стойкий ГСЧ) для ключей, солей и nonce.
 *
 * Никаких самодельных алгоритмов. Класс не зависит от Android API,
 * поэтому полностью тестируется обычными JVM unit-тестами.
 */
object CryptoManager {

    const val KEY_SIZE_BYTES = 32          // 256 бит
    const val NONCE_SIZE_BYTES = 12        // рекомендуемый размер nonce для GCM
    const val SALT_SIZE_BYTES = 16         // 128 бит соли KDF
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    /** Случайные байты из криптографически стойкого генератора. */
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    /** Новый случайный ключ данных (256 бит). */
    fun generateDataKey(): ByteArray = randomBytes(KEY_SIZE_BYTES)

    /** Новый уникальный nonce. Вызывать для КАЖДОЙ операции шифрования. */
    fun generateNonce(): ByteArray = randomBytes(NONCE_SIZE_BYTES)

    /** Новая случайная соль для KDF. */
    fun generateSalt(): ByteArray = randomBytes(SALT_SIZE_BYTES)

    /**
     * AES-256-GCM шифрование.
     * @param key  ключ 32 байта
     * @param nonce уникальный nonce 12 байт (НИКОГДА не повторять с одним ключом)
     * @param plaintext открытые данные
     * @param aad дополнительные аутентифицируемые данные (контекст операции)
     * @return шифртекст с тегом подлинности в конце
     */
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "Ключ должен быть 256 бит" }
        require(nonce.size == NONCE_SIZE_BYTES) { "Nonce должен быть 12 байт" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * AES-256-GCM расшифровка. При неверном ключе, nonce, AAD или любом
     * повреждении шифртекста бросает исключение (AEADBadTagException) —
     * частично расшифрованные данные наружу никогда не отдаются.
     */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "Ключ должен быть 256 бит" }
        require(nonce.size == NONCE_SIZE_BYTES) { "Nonce должен быть 12 байт" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /** Оборачивание (шифрование) ключа данных другим ключом. Возвращает nonce+шифртекст. */
    fun wrapKey(wrappingKey: ByteArray, dataKey: ByteArray, aad: ByteArray): WrappedKey {
        val nonce = generateNonce()
        val wrapped = aesGcmEncrypt(wrappingKey, nonce, dataKey, aad)
        return WrappedKey(nonce = nonce, ciphertext = wrapped)
    }

    /** Разворачивание ключа данных. Бросает исключение при неверном wrappingKey. */
    fun unwrapKey(wrappingKey: ByteArray, wrapped: WrappedKey, aad: ByteArray): ByteArray {
        val key = aesGcmDecrypt(wrappingKey, wrapped.nonce, wrapped.ciphertext, aad)
        require(key.size == KEY_SIZE_BYTES) { "Повреждён обёрнутый ключ" }
        return key
    }

    /** Best-effort очистка чувствительных данных из памяти. */
    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    fun wipe(chars: CharArray?) {
        chars?.fill('\u0000')
    }

    data class WrappedKey(val nonce: ByteArray, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WrappedKey) return false
            return nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
    }
}

/** AAD-контексты: не дают перенести шифртекст из одного слота в другой. */
object CryptoAad {
    val PASSWORD_SLOT: ByteArray = "NOX-v1/password-slot".toByteArray(Charsets.UTF_8)
    val RECOVERY_SLOT: ByteArray = "NOX-v1/recovery-slot".toByteArray(Charsets.UTF_8)
    val PAYLOAD: ByteArray = "NOX-v1/payload".toByteArray(Charsets.UTF_8)
    val BIOMETRIC_SLOT: ByteArray = "NOX-v1/biometric-slot".toByteArray(Charsets.UTF_8)
}
