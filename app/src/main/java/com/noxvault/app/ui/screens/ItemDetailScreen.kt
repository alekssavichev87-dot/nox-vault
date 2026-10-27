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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.noxvault.app.domain.model.AttachmentMeta
import com.noxvault.app.domain.model.FieldType
import com.noxvault.app.ui.components.CategoryAvatar
import com.noxvault.app.ui.components.ConfirmDialog
import com.noxvault.app.ui.components.MaskedValue
import com.noxvault.app.ui.components.NoxCard
import com.noxvault.app.ui.components.rememberSaveFilePicker
import com.noxvault.app.ui.components.SectionTitle
import com.noxvault.app.ui.components.formatBytes
import com.noxvault.app.ui.components.formatDateTime
import com.noxvault.app.ui.navigation.Routes
import com.noxvault.app.ui.theme.NoxError
import com.noxvault.app.ui.theme.NoxWarning
import com.noxvault.app.viewmodel.VaultViewModel

/** Экран 6. Просмотр записи: секретные поля скрыты, копирование с автоочисткой буфера. */
@Composable
fun ItemDetailScreen(viewModel: VaultViewModel, navController: NavController, itemId: String) {
    val items by viewModel.items.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val item = items.firstOrNull { it.id == itemId }
    var visibleFields by remember(itemId) { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingAttachment by remember { mutableStateOf<AttachmentMeta?>(null) }

    val saveAttachmentFile = rememberSaveFilePicker(viewModel, "*/*") { uri ->
        val attachment = pendingAttachment
        pendingAttachment = null
        if (attachment != null) viewModel.exportAttachment(attachment, uri)
    }

    LaunchedEffect(itemId) { viewModel.markOpened(itemId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Row(
            modifier = Modifier.padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Spacer(Modifier.weight(1f))
            if (item != null) {
                Button(onClick = { navController.navigate(Routes.edit(itemId = item.id)) }) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Text("  Изменить")
                }
                IconButton(onClick = { viewModel.toggleFavorite(item) }) {
                    Icon(
                        if (item.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Избранное",
                        tint = if (item.favorite) NoxWarning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить запись", tint = NoxError)
                }
            }
        }

        if (item == null) {
            Spacer(Modifier.height(40.dp))
            Text("Запись не найдена (возможно, она была удалена).", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryAvatar(item.category(), size = 58)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(item.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.headlineMedium)
                val folder = folders.firstOrNull { it.id == item.folderId }?.name
                Text(
                    listOfNotNull(item.category().titleRu, folder?.let { "📁 $it" }).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.tags.isNotEmpty()) {
                    Text(
                        item.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        SectionTitle("Поля")
        if (item.fields.isEmpty()) {
            Text("Полей нет. Нажмите «Изменить», чтобы добавить.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item.fields.forEach { field ->
            val secret = field.isSecretEffective()
            val visible = field.id in visibleFields
            NoxCard(modifier = Modifier.padding(vertical = 5.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        field.label.ifBlank { field.fieldType().titleRu } + if (secret) "  🔒" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            when {
                                field.value.isEmpty() -> Text(
                                    "(пусто)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                field.fieldType() == FieldType.TOGGLE -> Text(
                                    if (field.value == "true") "Включено" else "Выключено",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                secret -> MaskedValue(field.value, visible)
                                else -> Text(field.value, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (secret && field.value.isNotEmpty()) {
                            IconButton(onClick = {
                                visibleFields = if (visible) visibleFields - field.id else visibleFields + field.id
                            }) {
                                Icon(
                                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (visible) "Скрыть" else "Показать"
                                )
                            }
                        }
                        if (field.value.isNotEmpty() && field.fieldType() != FieldType.TOGGLE) {
                            IconButton(onClick = {
                                if (secret) viewModel.copySecret(field.label, field.value)
                                else viewModel.copyText(field.label, field.value)
                            }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать поле")
                            }
                        }
                    }
                }
            }
        }

        if (item.attachments.isNotEmpty()) {
            SectionTitle("Вложения (зашифрованы внутри хранилища)")
            item.attachments.forEach { attachment ->
                NoxCard(modifier = Modifier.padding(vertical = 5.dp)) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(attachment.fileName, style = MaterialTheme.typography.bodyLarge)
                            Text(formatBytes(attachment.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = {
                            pendingAttachment = attachment
                            saveAttachmentFile(attachment.fileName)
                        }) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Text("  Сохранить")
                        }
                    }
                }
            }
            Text(
                "«Сохранить» запишет файл в открытом виде в выбранное вами место. Делайте это осознанно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (item.linkedIds.isNotEmpty()) {
            SectionTitle("Связанные записи")
            item.linkedIds.forEach { linkedId ->
                val linked = items.firstOrNull { it.id == linkedId }
                if (linked != null) {
                    NoxCard(
                        modifier = Modifier.padding(vertical = 4.dp),
                        onClick = { navController.navigate(Routes.detail(linked.id)) }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(linked.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }

        SectionTitle("Сведения")
        NoxCard {
            Column(modifier = Modifier.padding(14.dp)) {
                MetaLine("Создано", formatDateTime(item.createdAt))
                MetaLine("Изменено", formatDateTime(item.updatedAt))
                if (item.lastOpenedAt > 0) MetaLine("Последнее открытие", formatDateTime(item.lastOpenedAt))
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showDeleteConfirm && item != null) {
        ConfirmDialog(
            title = "Удалить запись?",
            text = "«${item.title.ifBlank { "Без названия" }}» будет удалена из хранилища навсегда. Восстановить её можно будет только из ранее сделанной резервной копии .nox.",
            confirmText = "Удалить навсегда",
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteItem(item.id) { navController.popBackStack() }
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(170.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
