package com.noxvault.app.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Безопасное копирование секретов:
 *  - на Android 13+ помечает содержимое чувствительным (система прячет превью);
 *  - через заданный интервал очищает буфер, НО только если там всё ещё лежит
 *    именно скопированный нами текст (чужие данные пользователя не трогаем).
 * Ограничение платформы: другие приложения с доступом к буферу всё равно могут
 * прочитать секрет в течение этих секунд — об этом честно написано в docs/SECURITY.md.
 */
class ClipboardHelper(private val context: Context, private val scope: CoroutineScope) {

    private var clearJob: Job? = null

    fun copy(label: String, text: String, clearAfterSeconds: Int) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        manager.setPrimaryClip(clip)

        clearJob?.cancel()
        if (clearAfterSeconds > 0) {
            clearJob = scope.launch {
                delay(clearAfterSeconds * 1000L)
                clearIfStillOurs(text)
            }
        }
    }

    private fun clearIfStillOurs(copiedText: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = runCatching {
            manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        if (current == copiedText) {
            // Очищаем только свой секрет, чужое содержимое не уничтожаем.
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
