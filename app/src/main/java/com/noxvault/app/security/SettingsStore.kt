package com.noxvault.app.security

import android.content.Context
import android.content.SharedPreferences

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/**
 * ТОЛЬКО несекретные настройки интерфейса и политики блокировки.
 * Никаких секретов, ключей и производных от пароля здесь нет и быть не должно —
 * поэтому обычный SharedPreferences допустим. Сами настройки тоже исключены
 * из системного бэкапа (см. res/xml/data_extraction_rules.xml).
 *
 * Счётчик неудачных попыток в prefs — это локальное замедление перебора
 * через интерфейс, а НЕ криптографическая защита: реальную стойкость даёт
 * Argon2id + AES-GCM. Злоумышленник с доступом к файлу может перебирать
 * пароль офлайн — именно поэтому мастер-пароль должен быть длинным.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.DARK.name)!!) }
            .getOrDefault(ThemeMode.DARK)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** Минуты в фоне до автоблокировки. 0 = сразу при сворачивании, -1 = никогда. */
    var autoLockMinutes: Int
        get() = prefs.getInt(KEY_AUTOLOCK, 5)
        set(value) = prefs.edit().putInt(KEY_AUTOLOCK, value).apply()

    /** Через сколько секунд очищать буфер после копирования секрета. 0 = не очищать. */
    var clipboardClearSeconds: Int
        get() = prefs.getInt(KEY_CLIPBOARD, 30)
        set(value) = prefs.edit().putInt(KEY_CLIPBOARD, value).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()

    /**
     * Разрешено ли NOX Vault отвечать системе как служба автозаполнения.
     * Отдельно от выбора службы в настройках Android: при false служба молчит,
     * даже если Android её вызывает. По умолчанию ВЫКЛ — функция включается осознанно.
     */
    var autofillEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOFILL, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOFILL, value).apply()

    /** Опасная опция из ТЗ: полное удаление после N неверных паролей подряд. По умолчанию ВЫКЛ. */
    var wipeOnFailedAttempts: Boolean
        get() = prefs.getBoolean(KEY_WIPE_ON_FAIL, false)
        set(value) = prefs.edit().putBoolean(KEY_WIPE_ON_FAIL, value).apply()

    var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED, value).apply()

    var lockoutUntilMs: Long
        get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKOUT_UNTIL, value).apply()

    fun resetLockout() {
        prefs.edit().putInt(KEY_FAILED, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).apply()
    }

    /**
     * Зарегистрировать неудачную попытку. После 5 ошибок — растущая пауза:
     * 30 с, 60 с, 120 с ... до 15 мин.
     * @return секунды до следующей разрешённой попытки (0 = можно сразу)
     */
    fun registerFailedAttempt(): Long {
        val attempts = failedAttempts + 1
        failedAttempts = attempts
        if (attempts < FREE_ATTEMPTS) return 0L
        val over = attempts - FREE_ATTEMPTS
        val seconds = minOf(30L shl over.coerceAtMost(5), 900L)
        lockoutUntilMs = System.currentTimeMillis() + seconds * 1000
        return seconds
    }

    fun remainingLockoutSeconds(): Long {
        val left = lockoutUntilMs - System.currentTimeMillis()
        return if (left > 0) (left + 999) / 1000 else 0L
    }

    companion object {
        private const val PREFS_NAME = "nox_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_AUTOLOCK = "autolock_minutes"
        private const val KEY_CLIPBOARD = "clipboard_seconds"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_AUTOFILL = "autofill_enabled"
        private const val KEY_WIPE_ON_FAIL = "wipe_on_failed"
        private const val KEY_FAILED = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        const val FREE_ATTEMPTS = 5
        const val WIPE_THRESHOLD = 10
    }
}
