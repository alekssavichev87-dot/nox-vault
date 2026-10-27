package com.noxvault.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.noxvault.app.ui.components.BusyOverlay
import com.noxvault.app.ui.components.PasswordField
import com.noxvault.app.ui.components.StrengthBar
import com.noxvault.app.viewmodel.VaultViewModel

/**
 * Шаг 1 экспорта: отдельный пароль именно для файла резервной копии.
 * Он не сохраняется нигде — забытый пароль копии означает бесполезный файл
 * (впрочем, копию можно открыть и ключом восстановления хранилища).
 */
@Composable
fun ExportPasswordDialog(
    onDismiss: () -> Unit,
    onReady: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пароль резервной копии") },
        text = {
            Column {
                Text(
                    "Файл .nox будет зашифрован этим паролем (Argon2id + AES-256-GCM). Пароль нигде не сохраняется. Храните файл и пароль в разных местах.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                PasswordField(password, { password = it; error = null }, "Пароль копии")
                Spacer(Modifier.height(8.dp))
                StrengthBar(password)
                Spacer(Modifier.height(10.dp))
                PasswordField(confirm, { confirm = it; error = null }, "Повторите пароль")
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    password.length < 8 -> error = "Минимум 8 символов (лучше 14+)"
                    password != confirm -> error = "Пароли не совпадают"
                    else -> onReady(password)
                }
            }) { Text("Выбрать файл…") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/**
 * Импорт .nox: проверка целостности и версии происходит криптографически
 * внутри репозитория; повреждённый или чужой файл просто не расшифруется.
 * Импорт ЗАМЕНЯЕТ текущее хранилище, о чём пользователь предупреждается заранее.
 */
@Composable
fun ImportBackupDialog(
    viewModel: VaultViewModel,
    uri: Uri,
    hasExistingVault: Boolean,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var backupPassword by remember { mutableStateOf("") }
    var useRecovery by remember { mutableStateOf(false) }
    var recoveryText by remember { mutableStateOf("") }
    var newMaster by remember { mutableStateOf("") }
    var confirmMaster by remember { mutableStateOf("") }
    var replaceConfirmed by remember { mutableStateOf(!hasExistingVault) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val canSubmit = replaceConfirmed && !working &&
        newMaster.length >= 8 && newMaster == confirmMaster &&
        (if (useRecovery) recoveryText.isNotBlank() else backupPassword.isNotEmpty())

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Восстановить из копии .nox") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (hasExistingVault) {
                    Text(
                        "ВНИМАНИЕ: текущее хранилище на этом устройстве будет полностью заменено содержимым резервной копии.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (useRecovery) {
                    OutlinedTextField(
                        value = recoveryText,
                        onValueChange = { recoveryText = it; error = null },
                        label = { Text("Ключ восстановления хранилища") },
                        supportingText = { Text("64 символа, можно без дефисов") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    PasswordField(
                        backupPassword,
                        { backupPassword = it; error = null },
                        "Пароль резервной копии"
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useRecovery, onCheckedChange = { useRecovery = it })
                    Text(
                        "Пароль копии забыт — использовать ключ восстановления",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(10.dp))
                PasswordField(newMaster, { newMaster = it; error = null }, "Новый мастер-пароль для этого устройства")
                Spacer(Modifier.height(8.dp))
                StrengthBar(newMaster)
                Spacer(Modifier.height(10.dp))
                PasswordField(confirmMaster, { confirmMaster = it; error = null }, "Повторите мастер-пароль")
                if (hasExistingVault) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = replaceConfirmed, onCheckedChange = { replaceConfirmed = it })
                        Text("Понимаю, что текущее хранилище будет заменено", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (working) BusyOverlay()
            }
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                onClick = {
                    working = true
                    error = null
                    viewModel.importBackup(
                        uri = uri,
                        backupPassword = if (useRecovery) null else backupPassword,
                        recoveryText = if (useRecovery) recoveryText else null,
                        newMasterPassword = newMaster
                    ) { ok, msg ->
                        working = false
                        if (ok) {
                            viewModel.message(msg)
                            onSuccess()
                        } else {
                            error = msg
                        }
                    }
                }
            ) { Text("Восстановить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("Отмена") }
        }
    )
}
