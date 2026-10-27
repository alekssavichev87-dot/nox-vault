package com.noxvault.app.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Файловое хранение зашифрованного контейнера в приватном каталоге приложения.
 *
 * Атомарное сохранение:
 *  1) новый контейнер полностью пишется во временный файл + fsync;
 *  2) текущий рабочий файл переименовывается в .bak (предыдущая хорошая версия);
 *  3) временный файл переименовывается на место основного.
 * Сбой питания/процесса на любом шаге не уничтожает последнюю рабочую версию.
 * Открытых временных копий секретов не существует: временный файл — уже шифртекст.
 */
class VaultStorage(context: Context) {

    private val dir: File = context.filesDir

    val mainFile: File get() = File(dir, MAIN_NAME)
    private val backupFile: File get() = File(dir, BACKUP_NAME)
    private val tempFile: File get() = File(dir, TEMP_NAME)
    val biometricFile: File get() = File(dir, BIOMETRIC_NAME)

    fun exists(): Boolean = mainFile.exists() && mainFile.length() > 0L

    fun readMain(): ByteArray? = if (mainFile.exists()) runCatching { mainFile.readBytes() }.getOrNull() else null

    fun readPreviousGood(): ByteArray? =
        if (backupFile.exists()) runCatching { backupFile.readBytes() }.getOrNull() else null

    @Throws(IOException::class)
    fun atomicWrite(bytes: ByteArray) {
        // Шаг 1: временный файл + sync на диск.
        FileOutputStream(tempFile).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (tempFile.length() != bytes.size.toLong()) {
            tempFile.delete()
            throw IOException("Не удалось полностью записать хранилище (нехватка места?)")
        }
        // Шаг 2: сохранить предыдущую хорошую версию.
        if (mainFile.exists()) {
            backupFile.delete()
            if (!mainFile.renameTo(backupFile)) {
                // fallback: копирование (тот же каталог — rename обычно работает)
                mainFile.copyTo(backupFile, overwrite = true)
                mainFile.delete()
            }
        }
        // Шаг 3: новый файл на место основного.
        if (!tempFile.renameTo(mainFile)) {
            tempFile.copyTo(mainFile, overwrite = true)
            tempFile.delete()
        }
    }

    /** Полное удаление хранилища и служебных файлов (после подтверждения пользователя). */
    fun deleteAll() {
        listOf(mainFile, backupFile, tempFile, biometricFile).forEach { runCatching { it.delete() } }
    }

    fun deleteBiometricBlob() {
        runCatching { biometricFile.delete() }
    }

    companion object {
        const val MAIN_NAME = "vault.nox"
        private const val BACKUP_NAME = "vault.nox.bak"
        private const val TEMP_NAME = "vault.tmp"
        private const val BIOMETRIC_NAME = "biometric.key"
    }
}
