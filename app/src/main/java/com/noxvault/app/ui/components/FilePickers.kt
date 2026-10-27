package com.noxvault.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.noxvault.app.viewmodel.VaultViewModel

/**
 * Устойчивый выбор файлов для устройств, где стандартный ACTION_OPEN_DOCUMENT
 * не работает (отключён/удалён DocumentsUI — приложение «Файлы», кастомные прошивки):
 *
 *   попытка 1: ACTION_OPEN_DOCUMENT (системный SAF-диалог);
 *   попытка 2: ACTION_GET_CONTENT (его умеют сторонние файловые менеджеры и галереи);
 *   попытка 3: тот же GET_CONTENT через системный chooser (ResolverActivity есть почти всегда).
 *
 * Если упали все три — показываем диагностику (класс и текст первой ошибки),
 * чтобы проблему можно было точно описать, а не гадать.
 * Пауза автоблокировки (begin/endExternalActivity) управляется здесь централизованно.
 */

private fun describeError(e: Throwable): String {
    val detail = e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
    return "${e.javaClass.simpleName}$detail".take(160)
}

/** Выбор существующего файла. Возвращает функцию запуска для onClick. */
@Composable
fun rememberOpenFilePicker(
    viewModel: VaultViewModel,
    onPicked: (Uri) -> Unit
): () -> Unit {
    val chooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.endExternalActivity()
        result.data?.data?.let(onPicked)
    }
    val getContentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        viewModel.endExternalActivity()
        if (uri != null) onPicked(uri)
    }
    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.endExternalActivity()
        if (uri != null) onPicked(uri)
    }

    return remember {
        {
            viewModel.beginExternalActivity()
            val primaryError = runCatching {
                openDocumentLauncher.launch(arrayOf("*/*"))
            }.exceptionOrNull()
            if (primaryError != null) {
                val fallbackError = runCatching {
                    getContentLauncher.launch("*/*")
                }.exceptionOrNull()
                if (fallbackError != null) {
                    val chooserError = runCatching {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        chooserLauncher.launch(Intent.createChooser(intent, "Выберите файл"))
                    }.exceptionOrNull()
                    if (chooserError != null) {
                        viewModel.endExternalActivity()
                        viewModel.message(
                            "Не удалось открыть выбор файлов (${describeError(primaryError)}). " +
                                "Проверьте, что системное приложение «Файлы» не отключено в настройках телефона."
                        )
                    }
                }
            }
        }
    }
}

/** Выбор места сохранения нового файла. Возвращает функцию запуска: (имя файла) -> Unit. */
@Composable
fun rememberSaveFilePicker(
    viewModel: VaultViewModel,
    mimeType: String,
    onPicked: (Uri) -> Unit
): (String) -> Unit {
    val chooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.endExternalActivity()
        result.data?.data?.let(onPicked)
    }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType)
    ) { uri ->
        viewModel.endExternalActivity()
        if (uri != null) onPicked(uri)
    }

    return remember {
        { fileName: String ->
            viewModel.beginExternalActivity()
            val primaryError = runCatching {
                createDocumentLauncher.launch(fileName)
            }.exceptionOrNull()
            if (primaryError != null) {
                val chooserError = runCatching {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        type = mimeType
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_TITLE, fileName)
                    }
                    chooserLauncher.launch(Intent.createChooser(intent, "Сохранить файл"))
                }.exceptionOrNull()
                if (chooserError != null) {
                    viewModel.endExternalActivity()
                    viewModel.message(
                        "Не удалось открыть сохранение файла (${describeError(primaryError)}). " +
                            "Проверьте, что системное приложение «Файлы» не отключено в настройках телефона."
                    )
                }
            }
        }
    }
}
