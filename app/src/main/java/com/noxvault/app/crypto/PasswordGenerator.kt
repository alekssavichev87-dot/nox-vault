package com.noxvault.app.crypto

import java.security.SecureRandom

/**
 * Генератор паролей на SecureRandom (криптографически стойкий ГСЧ).
 * Обычный kotlin.random.Random для секретов НЕ используется нигде в проекте.
 */
object PasswordGenerator {

    data class Options(
        val length: Int = 20,
        val uppercase: Boolean = true,
        val lowercase: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val excludeAmbiguous: Boolean = true
    )

    private val random = SecureRandom()

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/~"
    private const val AMBIGUOUS = "Il1O0|`'\""

    fun generate(options: Options): String {
        val groups = mutableListOf<String>()
        if (options.lowercase) groups += filtered(LOWER, options.excludeAmbiguous)
        if (options.uppercase) groups += filtered(UPPER, options.excludeAmbiguous)
        if (options.digits) groups += filtered(DIGITS, options.excludeAmbiguous)
        if (options.symbols) groups += filtered(SYMBOLS, options.excludeAmbiguous)
        require(groups.isNotEmpty()) { "Выберите хотя бы один тип символов" }
        val length = options.length.coerceIn(4, 128)

        val all = groups.joinToString("")
        val chars = CharArray(length)
        // Гарантируем минимум по одному символу из каждой включённой группы.
        val guaranteed = groups.map { it[random.nextInt(it.length)] }
        for (i in chars.indices) {
            chars[i] = if (i < guaranteed.size && length >= guaranteed.size) guaranteed[i]
                       else all[random.nextInt(all.length)]
        }
        // Перемешивание Фишера–Йетса на SecureRandom.
        for (i in chars.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        val result = String(chars)
        chars.fill('\u0000')
        return result
    }

    private fun filtered(source: String, excludeAmbiguous: Boolean): String =
        if (excludeAmbiguous) source.filter { it !in AMBIGUOUS } else source

    /** Грубая эвристическая оценка надёжности пароля: 0 (слабый) .. 4 (отличный). */
    fun strengthScore(password: String): Int {
        if (password.isEmpty()) return 0
        var score = 0
        if (password.length >= 10) score++
        if (password.length >= 14) score++
        val classes = listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() }
        ).count { it }
        if (classes >= 3) score++
        if (classes == 4 && password.length >= 12) score++
        if (password.length < 8) score = minOf(score, 1)
        return score.coerceIn(0, 4)
    }

    fun strengthLabel(score: Int): String = when (score) {
        0 -> "Очень слабый"
        1 -> "Слабый"
        2 -> "Средний"
        3 -> "Надёжный"
        else -> "Отличный"
    }
}
