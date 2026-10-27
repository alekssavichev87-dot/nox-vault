package com.noxvault.app.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Получение ключа шифрования (KEK) из мастер-пароля.
 *
 * Основной алгоритм — Argon2id (победитель Password Hashing Competition,
 * рекомендация OWASP) в реализации Bouncy Castle (lightweight API,
 * JCA-провайдер в Android не регистрируется и не используется).
 *
 * Параметры по умолчанию: m = 64 МиБ, t = 3, p = 1 — баланс между стойкостью
 * к перебору на GPU и временем разблокировки на среднем телефоне (~0.5–1 с).
 * Параметры и соль хранятся в заголовке контейнера, поэтому в будущих
 * версиях их можно повышать без потери совместимости.
 *
 * PBKDF2-HMAC-SHA256 оставлен как документированный fallback-тип KDF
 * (поддерживается при чтении контейнеров, если такие появятся).
 */
object KeyDerivation {

    const val KDF_ARGON2ID = "argon2id"
    const val KDF_PBKDF2_SHA256 = "pbkdf2-sha256"

    const val DEFAULT_MEMORY_KB = 65_536   // 64 МиБ
    const val DEFAULT_ITERATIONS = 3
    const val DEFAULT_PARALLELISM = 1
    const val PBKDF2_DEFAULT_ITERATIONS = 600_000  // рекомендация OWASP для PBKDF2-HMAC-SHA256

    data class KdfParams(
        val type: String = KDF_ARGON2ID,
        val memoryKb: Int = DEFAULT_MEMORY_KB,
        val iterations: Int = DEFAULT_ITERATIONS,
        val parallelism: Int = DEFAULT_PARALLELISM
    )

    /** Вывести KEK (32 байта) из пароля и соли по указанным параметрам. */
    fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        return when (params.type) {
            KDF_ARGON2ID -> argon2id(password, salt, params)
            KDF_PBKDF2_SHA256 -> pbkdf2(password, salt, params.iterations)
            else -> throw IllegalArgumentException("Неизвестный KDF: ${params.type}")
        }
    }

    private fun argon2id(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val argonParams = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKb)
            .withParallelism(params.parallelism)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(argonParams)
        val out = ByteArray(CryptoManager.KEY_SIZE_BYTES)
        generator.generateBytes(password, out)
        return out
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, CryptoManager.KEY_SIZE_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** SHA-256 — используется только как вспомогательный хэш (не как KDF пароля!). */
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

/**
 * Ключ восстановления: 32 случайных байта (256 бит энтропии).
 * Полноценный случайный ключ, поэтому KDF ему не нужен — он напрямую
 * используется как KEK для recovery-слота. Пользователю показывается
 * один раз в виде HEX-групп: XXXX-XXXX-... (64 символа).
 */
object RecoveryKey {

    fun generate(): ByteArray = CryptoManager.randomBytes(32)

    fun formatForDisplay(keyBytes: ByteArray): String {
        require(keyBytes.size == 32) { "Ключ восстановления должен быть 32 байта" }
        val hex = keyBytes.joinToString("") { "%02X".format(it) }
        return hex.chunked(4).joinToString("-")
    }

    /** Парсинг ввода пользователя: игнорирует дефисы, пробелы и регистр. Null при ошибке формата. */
    fun parse(input: String): ByteArray? {
        val cleaned = input.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != 64) return null
        if (!cleaned.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return ByteArray(32) { i ->
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            ((hi shl 4) + lo).toByte()
        }
    }
}
