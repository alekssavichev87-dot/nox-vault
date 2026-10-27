package com.noxvault.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noxvault.app.ui.components.BusyOverlay
import com.noxvault.app.ui.components.NoxCard
import com.noxvault.app.ui.components.PasswordField
import com.noxvault.app.ui.components.rememberOpenFilePicker
import com.noxvault.app.ui.components.StrengthBar
import com.noxvault.app.viewmodel.VaultViewModel

/** Экран 1. Первоначальная настройка: создание мастер-пароля или восстановление из .nox. */
@Composable
fun SetupScreen(viewModel: VaultViewModel) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var understood by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val busy by viewModel.busy.collectAsState()
    val authError by viewModel.authError.collectAsState()

    val pickImportFile = rememberOpenFilePicker(viewModel) { uri -> importUri = uri }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("NOX Vault", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Зашифрованное хранилище ваших паролей, ключей, заметок и документов. Только на этом устройстве.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))

        NoxCard {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow(Icons.Filled.Lock, "AES-256-GCM + Argon2id", "Данные шифруются мастер-паролем. Пароль нигде не сохраняется.")
                Spacer(Modifier.height(12.dp))
                InfoRow(Icons.Filled.CloudOff, "Полностью офлайн", "Нет аккаунтов, серверов, рекламы и аналитики. Интернет приложению не разрешён.")
                Spacer(Modifier.height(12.dp))
                InfoRow(Icons.Filled.VisibilityOff, "Забыли пароль — данные не вернуть", "После создания вы получите ключ восстановления. Сохраните его офлайн.")
            }
        }

        Spacer(Modifier.height(24.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it; localError = null; viewModel.clearAuthError() },
            label = "Мастер-пароль",
            supportingText = "Минимум 8 символов, надёжнее — длинная фраза из 4–5 слов",
            enabled = !busy
        )
        Spacer(Modifier.height(8.dp))
        StrengthBar(password)
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = confirm,
            onValueChange = { confirm = it; localError = null },
            label = "Повторите мастер-пароль",
            isError = confirm.isNotEmpty() && confirm != password,
            supportingText = if (confirm.isNotEmpty() && confirm != password) "Пароли не совпадают" else null,
            enabled = !busy
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = understood, onCheckedChange = { understood = it }, enabled = !busy)
            Text(
                "Понимаю: без мастер-пароля или ключа восстановления доступ к данным восстановить нельзя",
                style = MaterialTheme.typography.bodySmall
            )
        }

        (localError ?: authError)?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (busy) BusyOverlay()

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !busy && understood && password.length >= 8 && password == confirm,
            onClick = { viewModel.createVault(password) },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text("Создать хранилище")
        }

        Spacer(Modifier.height(26.dp))
        HorizontalDivider()
        Spacer(Modifier.height(18.dp))
        Text(
            "У вас уже есть файл резервной копии .nox с другого устройства?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            enabled = !busy,
            onClick = { pickImportFile() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Восстановить из резервной копии")
        }
    }

    importUri?.let { uri ->
        ImportBackupDialog(
            viewModel = viewModel,
            uri = uri,
            hasExistingVault = false,
            onDismiss = { importUri = null },
            onSuccess = { importUri = null }
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
