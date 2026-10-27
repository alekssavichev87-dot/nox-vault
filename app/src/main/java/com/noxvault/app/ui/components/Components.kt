package com.noxvault.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noxvault.app.crypto.PasswordGenerator
import com.noxvault.app.domain.model.ItemCategory
import com.noxvault.app.domain.model.VaultItem
import com.noxvault.app.ui.theme.CategoryPalette
import com.noxvault.app.ui.theme.NoxError
import com.noxvault.app.ui.theme.NoxSuccess
import com.noxvault.app.ui.theme.NoxWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Утилиты отображения ----------

fun categoryIcon(category: ItemCategory): ImageVector = when (category) {
    ItemCategory.PASSWORD -> Icons.Filled.Lock
    ItemCategory.ACCOUNT -> Icons.Filled.Person
    ItemCategory.API_KEY -> Icons.Filled.Api
    ItemCategory.APP_SETTINGS -> Icons.Filled.Tune
    ItemCategory.NOTE -> Icons.Filled.NoteAlt
    ItemCategory.FILE -> Icons.Filled.Description
    ItemCategory.WIFI -> Icons.Filled.Wifi
    ItemCategory.SERVER -> Icons.Filled.Dns
    ItemCategory.LICENSE -> Icons.Filled.Badge
    ItemCategory.RECOVERY -> Icons.Filled.Pin
    ItemCategory.CUSTOM -> Icons.Filled.Widgets
}

fun categoryColor(category: ItemCategory) =
    CategoryPalette[category.ordinal % CategoryPalette.size]

fun formatDateTime(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale("ru", "RU")).format(Date(epochMs))
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format(Locale.US, "%.1f МБ", bytes / 1024.0 / 1024.0)
}

// ---------- Базовые блоки ----------

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = 22.dp, bottom = 10.dp)
    )
}

@Composable
fun NoxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = colors,
            border = border
        ) { content() }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = colors,
            border = border
        ) { content() }
    }
}

@Composable
fun CategoryAvatar(category: ItemCategory, size: Int = 46) {
    val color = categoryColor(category)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = categoryIcon(category),
            contentDescription = category.titleRu,
            tint = color,
            modifier = Modifier.size((size * 0.52f).dp)
        )
    }
}

@Composable
fun ItemCard(item: VaultItem, folderName: String?, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    NoxCard(onClick = onClick, modifier = Modifier.padding(vertical = 5.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryAvatar(item.category())
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { "Без названия" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val preview = item.previewText()
                val sub = listOfNotNull(
                    preview.takeIf { it.isNotBlank() },
                    folderName?.let { "Папка: $it" }
                ).joinToString("  ·  ")
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.tags.isNotEmpty()) {
                    Text(
                        text = item.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (item.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (item.favorite) "Убрать из избранного" else "В избранное",
                    tint = if (item.favorite) NoxWarning else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------- Поля ввода ----------

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Скрыть пароль" else "Показать пароль"
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun StrengthBar(password: String) {
    val score = PasswordGenerator.strengthScore(password)
    val color = when (score) {
        0, 1 -> NoxError
        2 -> NoxWarning
        else -> NoxSuccess
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { (score + 1) / 5f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(MaterialTheme.shapes.extraSmall),
            color = color,
            trackColor = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "Надёжность: ${PasswordGenerator.strengthLabel(score)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MaskedValue(value: String, visible: Boolean) {
    Text(
        text = if (visible) value else "•".repeat(value.length.coerceIn(6, 18)),
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        fontWeight = if (visible) FontWeight.Normal else FontWeight.Bold
    )
}

// ---------- Диалоги ----------

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "Удалить",
    dismissText: String = "Отмена",
    dangerous: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (dangerous) NoxError else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        }
    )
}

/** Простой выбор из списка (вместо хрупких выпадающих меню — надёжно и доступно). */
@Composable
fun OptionPickerDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(index) }
                            .padding(vertical = 13.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (index == selectedIndex) "●" else "○",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(28.dp)
                        )
                        Text(option, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

/** Выбор категории новой записи (шаблон полей подставится автоматически). */
@Composable
fun CategoryPickerDialog(onPick: (ItemCategory) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Тип новой записи") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ItemCategory.entries.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(category) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CategoryAvatar(category, size = 40)
                        Spacer(Modifier.width(12.dp))
                        Text(category.titleRu, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun BusyOverlay() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "Выполняется шифрование…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
