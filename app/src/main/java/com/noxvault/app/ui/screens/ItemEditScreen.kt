package com.noxvault.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.noxvault.app.domain.model.FieldType
import com.noxvault.app.domain.model.ItemCategory
import com.noxvault.app.domain.model.Templates
import com.noxvault.app.domain.model.VaultField
import com.noxvault.app.domain.model.VaultItem
import com.noxvault.app.ui.components.CategoryPickerDialog
import com.noxvault.app.ui.components.NoxCard
import com.noxvault.app.ui.components.rememberOpenFilePicker
import com.noxvault.app.ui.components.OptionPickerDialog
import com.noxvault.app.ui.components.SectionTitle
import com.noxvault.app.ui.components.formatBytes
import com.noxvault.app.viewmodel.VaultViewModel
import kotlinx.coroutines.launch

/**
 * Экран 5. Создание и редактирование записи: универсальные поля любых типов,
 * шаблоны по категориям, генератор паролей прямо в форме, папки, теги,
 * вложения (до 2 МБ, шифруются вместе с хранилищем) и связи между записями.
 */
@Composable
fun ItemEditScreen(
    viewModel: VaultViewModel,
    navController: NavController,
    itemId: String?,
    initialCategoryName: String?
) {
    val allItems by viewModel.items.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val existing = remember(itemId, allItems.size) { itemId?.let { id -> allItems.firstOrNull { it.id == id } } }
    val scope = rememberCoroutineScope()

    val initialCategory = existing?.category()
        ?: initialCategoryName?.let { runCatching { ItemCategory.valueOf(it) }.getOrNull() }
        ?: ItemCategory.CUSTOM

    var title by remember(itemId) { mutableStateOf(existing?.title ?: "") }
    var category by remember(itemId) { mutableStateOf(initialCategory) }
    var folderId by remember(itemId) { mutableStateOf(existing?.folderId) }
    var tagsText by remember(itemId) { mutableStateOf(existing?.tags?.joinToString(", ") ?: "") }
    var favorite by remember(itemId) { mutableStateOf(existing?.favorite ?: false) }
    var fields by remember(itemId) {
        mutableStateOf(existing?.fields ?: Templates.fieldsFor(initialCategory))
    }
    var attachments by remember(itemId) { mutableStateOf(existing?.attachments ?: emptyList()) }
    var linkedIds by remember(itemId) { mutableStateOf(existing?.linkedIds?.toSet() ?: emptySet()) }

    var visibleSecrets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var pendingFolderName by remember { mutableStateOf<String?>(null) }
    var typePickerFieldIndex by remember { mutableStateOf<Int?>(null) }
    var generatorFieldIndex by remember { mutableStateOf<Int?>(null) }
    var showLinkedPicker by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf(false) }

    // После создания папки из этой формы — сразу выбрать её.
    LaunchedEffect(folders, pendingFolderName) {
        val name = pendingFolderName ?: return@LaunchedEffect
        folders.firstOrNull { it.name == name }?.let {
            folderId = it.id
            pendingFolderName = null
        }
    }

    // Устойчивый выбор файла с запасными способами для устройств без работающего DocumentsUI.
    val pickAttachmentFile = rememberOpenFilePicker(viewModel) { uri ->
        scope.launch {
            // readAttachment не бросает исключений, но страхуемся и здесь:
            // необработанное исключение в этой корутине раньше роняло приложение.
            try {
                val result = viewModel.readAttachment(uri)
                val attachment = result.attachment
                if (attachment != null) {
                    attachments = attachments + attachment
                    viewModel.message("Вложение добавлено: ${attachment.fileName}")
                } else {
                    viewModel.message(result.error ?: "Не удалось добавить файл.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.message("Не удалось добавить файл. Попробуйте другой файл.")
            }
        }
    }

    fun updateField(index: Int, transform: (VaultField) -> VaultField) {
        fields = fields.mapIndexed { i, field -> if (i == index) transform(field) else field }
    }

    fun save() {
        if (title.isBlank()) {
            titleError = true
            return
        }
        val tags = tagsText.split(',', ';', '#')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val now = System.currentTimeMillis()
        val item = if (existing != null) {
            existing.copy(
                type = category.name,
                title = title.trim(),
                folderId = folderId,
                tags = tags,
                fields = fields,
                attachments = attachments,
                linkedIds = linkedIds.toList(),
                favorite = favorite,
                updatedAt = now,
                version = existing.version + 1
            )
        } else {
            VaultItem(
                type = category.name,
                title = title.trim(),
                folderId = folderId,
                tags = tags,
                fields = fields,
                attachments = attachments,
                linkedIds = linkedIds.toList(),
                favorite = favorite,
                createdAt = now,
                updatedAt = now
            )
        }
        viewModel.saveItem(item) { navController.popBackStack() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Row(modifier = Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад без сохранения")
            }
            Text(
                if (existing == null) "Новая запись" else "Изменить запись",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { save() }) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text("  Сохранить")
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it; titleError = false },
            label = { Text("Название *") },
            placeholder = { Text("Например: Почта, Домашний Wi-Fi, GitHub…") },
            singleLine = true,
            isError = titleError,
            supportingText = if (titleError) ({ Text("Укажите название записи") }) else null,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { showCategoryPicker = true }, modifier = Modifier.weight(1f)) {
                Text("Тип: ${category.titleRu}")
            }
            OutlinedButton(onClick = { showFolderPicker = true }, modifier = Modifier.weight(1f)) {
                val folderName = folders.firstOrNull { it.id == folderId }?.name ?: "Без папки"
                Text("📁 $folderName")
            }
        }
        if (existing == null) {
            Text(
                "При смене типа у новой записи шаблон полей заменится целиком.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = tagsText,
            onValueChange = { tagsText = it },
            label = { Text("Теги через запятую") },
            placeholder = { Text("работа, финансы, важное") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⭐ В избранном", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = favorite, onCheckedChange = { favorite = it })
        }

        SectionTitle("Поля записи")
        fields.forEachIndexed { index, field ->
            FieldEditor(
                field = field,
                secretVisible = field.id in visibleSecrets,
                onLabelChange = { updateField(index) { f -> f.copy(label = it) } },
                onValueChange = { updateField(index) { f -> f.copy(value = it) } },
                onSecretChange = { updateField(index) { f -> f.copy(secret = it) } },
                onToggleVisible = {
                    visibleSecrets = if (field.id in visibleSecrets) visibleSecrets - field.id
                    else visibleSecrets + field.id
                },
                onPickType = { typePickerFieldIndex = index },
                onGenerate = { generatorFieldIndex = index },
                onDelete = { fields = fields.filterIndexed { i, _ -> i != index } }
            )
        }
        OutlinedButton(
            onClick = { fields = fields + VaultField(label = "Поле ${fields.size + 1}") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  Добавить поле")
        }

        SectionTitle("Вложения")
        if (attachments.isEmpty()) {
            Text(
                "Можно приложить небольшой файл (до 2 МБ): он зашифруется вместе со всем хранилищем.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        attachments.forEach { attachment ->
            NoxCard(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(attachment.fileName, style = MaterialTheme.typography.bodyLarge)
                        Text(formatBytes(attachment.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { attachments = attachments - attachment }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Убрать вложение")
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { pickAttachmentFile() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.AttachFile, contentDescription = null)
            Text("  Приложить файл")
        }

        SectionTitle("Связанные записи")
        val linkedTitles = allItems.filter { it.id in linkedIds }.map { it.title.ifBlank { "Без названия" } }
        Text(
            if (linkedTitles.isEmpty()) "Нет связей" else linkedTitles.joinToString(", "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showLinkedPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Link, contentDescription = null)
            Text("  Выбрать связанные записи")
        }

        Spacer(Modifier.height(22.dp))
        Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (existing == null) "Создать запись" else "Сохранить изменения")
        }
        Spacer(Modifier.height(36.dp))
    }

    if (showCategoryPicker) {
        CategoryPickerDialog(
            onPick = { picked ->
                category = picked
                if (existing == null) fields = Templates.fieldsFor(picked)
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false }
        )
    }

    if (showFolderPicker) {
        val options = listOf("Без папки") + folders.map { it.name } + listOf("＋ Создать новую папку…")
        val selected = when {
            folderId == null -> 0
            else -> folders.indexOfFirst { it.id == folderId }.let { if (it >= 0) it + 1 else 0 }
        }
        OptionPickerDialog(
            title = "Папка записи",
            options = options,
            selectedIndex = selected,
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

    typePickerFieldIndex?.let { fieldIndex ->
        OptionPickerDialog(
            title = "Тип поля",
            options = FieldType.entries.map { it.titleRu },
            selectedIndex = FieldType.entries.indexOf(fields[fieldIndex].fieldType()),
            onPick = { picked ->
                val type = FieldType.entries[picked]
                updateField(fieldIndex) { f ->
                    f.copy(
                        type = type.name,
                        secret = f.secret || type.secretByDefault(),
                        value = if (type == FieldType.TOGGLE && f.value !in listOf("true", "false")) "false" else f.value
                    )
                }
                typePickerFieldIndex = null
            },
            onDismiss = { typePickerFieldIndex = null }
        )
    }

    generatorFieldIndex?.let { fieldIndex ->
        GeneratorDialog(
            onDismiss = { generatorFieldIndex = null },
            onUse = { generated ->
                updateField(fieldIndex) { f -> f.copy(value = generated) }
                generatorFieldIndex = null
            }
        )
    }

    if (showLinkedPicker) {
        LinkedPickerDialog(
            candidates = allItems.filter { it.id != existing?.id },
            selected = linkedIds,
            onToggle = { id ->
                linkedIds = if (id in linkedIds) linkedIds - id else linkedIds + id
            },
            onDismiss = { showLinkedPicker = false }
        )
    }
}

@Composable
private fun FieldEditor(
    field: VaultField,
    secretVisible: Boolean,
    onLabelChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onSecretChange: (Boolean) -> Unit,
    onToggleVisible: () -> Unit,
    onPickType: () -> Unit,
    onGenerate: () -> Unit,
    onDelete: () -> Unit
) {
    val type = field.fieldType()
    val secret = field.isSecretEffective()
    NoxCard(modifier = Modifier.padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = field.label,
                    onValueChange = onLabelChange,
                    label = { Text("Подпись поля") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onPickType) { Text(type.titleRu) }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить поле")
                }
            }
            Spacer(Modifier.height(10.dp))

            if (type == FieldType.TOGGLE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (field.value == "true") "Включено" else "Выключено",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = field.value == "true",
                        onCheckedChange = { onValueChange(it.toString()) }
                    )
                }
            } else {
                OutlinedTextField(
                    value = field.value,
                    onValueChange = onValueChange,
                    label = { Text("Значение") },
                    placeholder = {
                        Text(
                            when (type) {
                                FieldType.DATE -> "например, 31.12.2027"
                                FieldType.URL -> "https://…"
                                else -> ""
                            }
                        )
                    },
                    visualTransformation = if (secret && !secretVisible) PasswordVisualTransformation()
                    else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (type) {
                            FieldType.NUMBER -> KeyboardType.Number
                            FieldType.URL -> KeyboardType.Uri
                            FieldType.PASSWORD, FieldType.SECRET -> KeyboardType.Password
                            else -> KeyboardType.Text
                        },
                        autoCorrect = false,
                        capitalization = KeyboardCapitalization.None
                    ),
                    singleLine = type != FieldType.MULTILINE && type != FieldType.SECRET,
                    minLines = if (type == FieldType.MULTILINE || type == FieldType.SECRET) 3 else 1,
                    trailingIcon = {
                        Row {
                            if (type == FieldType.PASSWORD || type == FieldType.SECRET) {
                                IconButton(onClick = onGenerate) {
                                    Icon(Icons.Filled.AutoFixHigh, contentDescription = "Сгенерировать пароль")
                                }
                            }
                            if (secret) {
                                IconButton(onClick = onToggleVisible) {
                                    Icon(
                                        if (secretVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (secretVisible) "Скрыть значение" else "Показать значение"
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🔒 Секретное (скрывать значение)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = secret,
                    enabled = !type.secretByDefault(),
                    onCheckedChange = onSecretChange
                )
            }
        }
    }
}

@Composable
private fun LinkedPickerDialog(
    candidates: List<VaultItem>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Связанные записи") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Text("Других записей пока нет.")
                }
                candidates.sortedBy { it.title.lowercase() }.forEach { candidate ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = candidate.id in selected,
                            onCheckedChange = { onToggle(candidate.id) }
                        )
                        Text(candidate.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Готово") }
        }
    )
}
