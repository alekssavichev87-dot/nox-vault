package com.noxvault.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.noxvault.app.data.importer.ColumnMapping
import com.noxvault.app.data.importer.CsvPasswordImporter
import com.noxvault.app.ui.components.BusyOverlay
import com.noxvault.app.ui.components.ConfirmDialog
import com.noxvault.app.ui.components.EmptyState
import com.noxvault.app.ui.components.NoxCard
import com.noxvault.app.ui.components.OptionPickerDialog
import com.noxvault.app.ui.components.rememberOpenFilePicker
import com.noxvault.app.ui.components.SectionTitle
import com.noxvault.app.ui.navigation.Routes
import com.noxvault.app.ui.theme.NoxWarning
import com.noxvault.app.viewmodel.VaultViewModel

private enum class MappingTarget(val label: String) {
    TITLE("Название"), URL("Сайт (URL)"), USERNAME("Логин"), PASSWORD("Пароль"), NOTE("Заметка")
}

/**
 * Импорт паролей из CSV браузера: предпросмотр и настройка перед сохранением.
 * Пароли на этом экране никогда не показываются открыто — только «•••• (N симв.)».
 * Файл CSV остаётся незашифрованным на устройстве пользователя: после импорта
 * приложение настойчиво напоминает удалить его.
 */
@Composable
fun CsvImportScreen(viewModel: VaultViewModel, navController: NavController) {
    val session by viewModel.csvSession.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var showPasteDialog by remember { mutableStateOf(false) }

    // Устойчивый выбор файла: SAF -> GET_CONTENT -> системный chooser.
    val pickCsvFile = rememberOpenFilePicker(viewModel) { uri ->
        viewModel.loadCsvForImport(uri) { ok, msg -> if (!ok) viewModel.message(msg) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(modifier = Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                viewModel.clearCsvImport()
                navController.popBackStack()
            }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Импорт из браузера (CSV)", style = MaterialTheme.typography.titleLarge)
        }

        val current = session
        if (current == null) {
            Spacer(Modifier.height(12.dp))
            EmptyState(
                Icons.Filled.UploadFile,
                "Выберите файл с паролями",
                "Сначала экспортируйте пароли из браузера в CSV:\n" +
                    "• Chrome/Edge: Настройки → Автозаполнение → Менеджер паролей → ⋮ → Экспорт паролей\n" +
                    "• Firefox: Пароли (about:logins) → ⋯ → Экспортировать логины\n" +
                    "• Safari: Файл → Экспорт → Пароли\n" +
                    "Затем перенесите файл на телефон и выберите его здесь."
            )
            if (busy) BusyOverlay()
            Button(
                onClick = { pickCsvFile() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Text("  Выбрать CSV-файл")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { showPasteDialog = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Не открывается выбор файлов? Вставить CSV текстом")
            }
            return@Column
        }

        CsvImportContent(
            viewModel = viewModel,
            navController = navController,
            busy = busy,
            onPickAnotherFile = { pickCsvFile() }
        )
    }

    if (showPasteDialog) {
        var pasted by remember { mutableStateOf("") }
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Вставить CSV текстом") },
            text = {
                Column {
                    Text(
                        "Откройте файл CSV в любом текстовом редакторе на телефоне, скопируйте всё содержимое и вставьте сюда. После импорта очистите буфер обмена (скопируйте что-нибудь нейтральное) и удалите сам файл.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        placeholder = { Text("name,url,username,password\n...") },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        clipboardManager.getText()?.let { pasted = it.text }
                    }) { Text("Вставить из буфера обмена") }
                }
            },
            confirmButton = {
                Button(
                    enabled = pasted.isNotBlank(),
                    onClick = {
                        showPasteDialog = false
                        viewModel.loadCsvFromText(pasted) { ok, msg ->
                            if (!ok) viewModel.message(msg)
                        }
                    }
                ) { Text("Разобрать CSV") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun CsvImportContent(
    viewModel: VaultViewModel,
    navController: NavController,
    busy: Boolean,
    onPickAnotherFile: () -> Unit
) {
    val session by viewModel.csvSession.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val current = session ?: return

    var selected by remember(current.fileName) { mutableStateOf<Set<Int>>(emptySet()) }
    var folderId by remember { mutableStateOf<String?>(null) }
    var tagsText by remember { mutableStateOf("импорт-браузер") }
    var skipDuplicates by remember { mutableStateOf(true) }
    var filterText by remember { mutableStateOf("") }
    var mappingTarget by remember { mutableStateOf<MappingTarget?>(null) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var pendingFolderName by remember { mutableStateOf<String?>(null) }
    var showConfirm by remember { mutableStateOf(false) }

    // При смене файла или переназначении столбцов: по умолчанию выбраны строки с паролем.
    LaunchedEffect(current.candidates) {
        selected = current.candidates.mapIndexedNotNull { index, candidate ->
            if (candidate.password.isNotBlank()) index else null
        }.toSet()
    }
    LaunchedEffect(folders, pendingFolderName) {
        val name = pendingFolderName ?: return@LaunchedEffect
        folders.firstOrNull { it.name == name }?.let {
            folderId = it.id
            pendingFolderName = null
        }
    }

    val existingKeys = remember(current.candidates) { viewModel.csvExistingKeys() }
    val duplicateKinds = remember(current.candidates, existingKeys) {
        val seen = mutableSetOf<String>()
        current.candidates.map { candidate ->
            val key = candidate.dedupKey()
            when {
                key in existingKeys -> "уже есть в хранилище"
                key in seen -> "повтор в этом файле"
                else -> {
                    seen.add(key)
                    null
                }
            }
        }
    }
    val selectedCandidates = selected.sorted().map { current.candidates[it] }
    val effectiveCount = if (!skipDuplicates) selected.size
    else selected.count { duplicateKinds.getOrNull(it) == null }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Spacer(Modifier.height(10.dp))
                NoxCard {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = NoxWarning)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "CSV — незашифрованный файл со всеми паролями. После импорта обязательно удалите его из «Загрузок» и с компьютера.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Файл: ${current.fileName}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Формат: ${current.format.labelRu} · строк: ${current.parsed.rows.size} · распознано записей: ${current.candidates.size} · выбрано: ${selected.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onPickAnotherFile) { Text("Выбрать другой файл") }
                    }
                }

                SectionTitle("Столбцы файла")
                Text(
                    if (current.mapping.isUsable())
                        "Приложение само определило столбцы. Если записи ниже выглядят неправильно — переназначьте:"
                    else "Не получилось определить столбец пароля. Укажите столбцы вручную:",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current.mapping.isUsable()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(MappingTarget.entries, key = { _, t -> t.name }) { _, target ->
                val columnIndex = when (target) {
                    MappingTarget.TITLE -> current.mapping.title
                    MappingTarget.URL -> current.mapping.url
                    MappingTarget.USERNAME -> current.mapping.username
                    MappingTarget.PASSWORD -> current.mapping.password
                    MappingTarget.NOTE -> current.mapping.note
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(target.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { mappingTarget = target }) {
                        Text(
                            columnIndex?.let { current.parsed.headers.getOrElse(it) { "?" } } ?: "— нет —",
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            item {
                SectionTitle("Куда импортировать")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showFolderPicker = true }, modifier = Modifier.weight(1f)) {
                        Text("📁 " + (folders.firstOrNull { it.id == folderId }?.name ?: "Без папки"))
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Тег для всех импортированных") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Пропускать дубликаты", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Не добавлять записи с тем же названием, логином и сайтом",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = skipDuplicates, onCheckedChange = { skipDuplicates = it })
                }

                SectionTitle("Записи (${current.candidates.size})")
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    placeholder = { Text("Фильтр списка…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { selected = current.candidates.indices.toSet() }) {
                        Text("Выбрать все")
                    }
                    OutlinedButton(onClick = { selected = emptySet() }) {
                        Text("Сбросить выбор")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (current.candidates.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Filled.UploadFile,
                        "Записи не распознаны",
                        "Проверьте назначение столбцов выше: нужен как минимум столбец «Пароль» или «Логин»."
                    )
                }
            } else {
                val visibleIndices = current.candidates.indices.filter { index ->
                    val c = current.candidates[index]
                    filterText.isBlank() ||
                        c.title.contains(filterText, ignoreCase = true) ||
                        c.username.contains(filterText, ignoreCase = true) ||
                        c.url.contains(filterText, ignoreCase = true)
                }
                itemsIndexed(visibleIndices, key = { _, idx -> idx }) { _, index ->
                    val candidate = current.candidates[index]
                    val duplicate = duplicateKinds.getOrNull(index)
                    NoxCard(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = index in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + index else selected - index
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    candidate.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                val sub = listOfNotNull(
                                    candidate.username.takeIf { it.isNotBlank() },
                                    CsvPasswordImporter.hostFromUrl(candidate.url)
                                        .ifBlank { candidate.url }.takeIf { it.isNotBlank() }
                                ).joinToString("  ·  ")
                                if (sub.isNotBlank()) {
                                    Text(
                                        sub, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                val badges = listOfNotNull(
                                    if (candidate.password.isBlank()) "без пароля"
                                    else "пароль •••••• (${candidate.password.length} симв.)",
                                    duplicate,
                                    "строка ${candidate.rowNumber}"
                                ).joinToString("  ·  ")
                                Text(
                                    badges,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (duplicate != null) NoxWarning
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(110.dp)) }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 14.dp)
        ) {
            if (busy) BusyOverlay()
            Button(
                enabled = !busy && selected.isNotEmpty() && current.mapping.isUsable(),
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text("  Импортировать: будет добавлено $effectiveCount из выбранных ${selected.size}")
            }
        }
    }

    mappingTarget?.let { target ->
        val currentIndex = when (target) {
            MappingTarget.TITLE -> current.mapping.title
            MappingTarget.URL -> current.mapping.url
            MappingTarget.USERNAME -> current.mapping.username
            MappingTarget.PASSWORD -> current.mapping.password
            MappingTarget.NOTE -> current.mapping.note
        }
        OptionPickerDialog(
            title = "Столбец для «${target.label}»",
            options = listOf("— нет столбца —") + current.parsed.headers,
            selectedIndex = (currentIndex ?: -1) + 1,
            onPick = { picked ->
                val column = if (picked == 0) null else picked - 1
                val m: ColumnMapping = when (target) {
                    MappingTarget.TITLE -> current.mapping.copy(title = column)
                    MappingTarget.URL -> current.mapping.copy(url = column)
                    MappingTarget.USERNAME -> current.mapping.copy(username = column)
                    MappingTarget.PASSWORD -> current.mapping.copy(password = column)
                    MappingTarget.NOTE -> current.mapping.copy(note = column)
                }
                viewModel.updateCsvMapping(m)
                mappingTarget = null
            },
            onDismiss = { mappingTarget = null }
        )
    }

    if (showFolderPicker) {
        val options = listOf("Без папки") + folders.map { it.name } + listOf("＋ Создать новую папку…")
        val selectedIdx = when {
            folderId == null -> 0
            else -> folders.indexOfFirst { it.id == folderId }.let { if (it >= 0) it + 1 else 0 }
        }
        OptionPickerDialog(
            title = "Папка для импортированных записей",
            options = options,
            selectedIndex = selectedIdx,
            onPick = { index ->
                showFolderPicker = false
                when {
                    index == 0 -> folderId = null
                    index == options.lastIndex -> showNewFolder = true
                    else -> folderId = folders[index - 1].id
                }
            },
            onDismiss = { showFolderPicker = false }
        )
    }

    if (showNewFolder) {
        NewFolderDialog(
            onCreate = { name ->
                pendingFolderName = name.trim()
                viewModel.addFolder(name)
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false }
        )
    }

    if (showConfirm) {
        ConfirmDialog(
            title = "Импортировать пароли?",
            text = "Будет добавлено записей: $effectiveCount" +
                (if (skipDuplicates && effectiveCount < selected.size)
                    " (дубликаты будут пропущены: ${selected.size - effectiveCount})" else "") +
                ".\n\nВсе записи попадут в категорию «Пароли» и будут зашифрованы вместе с хранилищем.\n\n" +
                "Не забудьте после импорта удалить CSV-файл «${current.fileName}» — он содержит пароли в открытом виде.",
            confirmText = "Импортировать",
            dangerous = false,
            onConfirm = {
                showConfirm = false
                viewModel.importCsvCandidates(
                    selected = selectedCandidates,
                    folderId = folderId,
                    tagsText = tagsText,
                    skipDuplicates = skipDuplicates
                ) { ok, msg ->
                    viewModel.message(msg)
                    if (ok) {
                        navController.navigate(Routes.VAULT) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                        }
                    }
                }
            },
            onDismiss = { showConfirm = false }
        )
    }
}
