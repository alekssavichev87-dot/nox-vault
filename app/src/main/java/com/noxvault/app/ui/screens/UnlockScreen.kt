package com.noxvault.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.noxvault.app.security.BiometricHelper
import com.noxvault.app.ui.components.BusyOverlay
import com.noxvault.app.ui.components.PasswordField
import com.noxvault.app.viewmodel.VaultViewModel

/**
 * Экран 2. Разблокировка: мастер-пароль, биометрия (дополнительно),
 * ключ восстановления (если пароль забыт). Содержимое хранилища не существует
 * в интерфейсе до успешной криптографической проверки.
 */
@Composable
fun UnlockScreen(viewModel: VaultViewModel) {
    var password by remember { mutableStateOf("") }
    var recoveryMode by remember { mutableStateOf(false) }
    var recoveryText by remember { mutableStateOf("") }
    val busy by viewModel.busy.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val context = LocalContext.current
    val biometricReady = remember { viewModel.biometricReadyToUse() }

    fun startBiometric() {
        val activity = context as? FragmentActivity ?: return
        if (busy) return
        try {
            val cipher = viewModel.biometric.decryptCipher(viewModel.biometricFile)
            if (cipher == null) {
                viewModel.disableBiometric(silent = true)
                return
            }
            viewModel.biometric.authenticate(
                activity = activity,
                cipher = cipher,
                title = "NOX Vault",
                subtitle = "Подтвердите вход отпечатком или лицом",
                onSuccess = { authorized -> viewModel.unlockWithBiometricCipher(authorized) },
                onUsePassword = { /* остаёмся на вводе пароля */ },
                onError = { viewModel.message(it) }
            )
        } catch (e: Exception) {
            if (BiometricHelper.isKeyInvalidated(e)) {
                viewModel.disableBiometric(silent = true)
                viewModel.message("Биометрия на устройстве изменилась. Введите мастер-пароль и включите биометрию заново в настройках.")
            }
        }
    }

    // Один автоматический запрос биометрии при появлении экрана.
    LaunchedEffect(Unit) {
        if (biometricReady) startBiometric()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("NOX Vault", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (recoveryMode) "Введите ключ восстановления из 64 символов"
            else "Хранилище заблокировано. Введите мастер-пароль.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(26.dp))

        if (recoveryMode) {
            OutlinedTextField(
                value = recoveryText,
                onValueChange = { recoveryText = it; viewModel.clearAuthError() },
                label = { Text("Ключ восстановления") },
                supportingText = { Text("Можно вводить без дефисов и пробелов, регистр не важен") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !busy && recoveryText.isNotBlank(),
                onClick = { viewModel.unlockWithRecoveryKey(recoveryText) },
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text("Войти по ключу восстановления") }
            TextButton(onClick = { recoveryMode = false; viewModel.clearAuthError() }) {
                Text("Вернуться к вводу пароля")
            }
        } else {
            PasswordField(
                value = password,
                onValueChange = { password = it; viewModel.clearAuthError() },
                label = "Мастер-пароль",
                enabled = !busy
            )
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !busy && password.isNotEmpty(),
                onClick = { viewModel.unlock(password) },
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text("Разблокировать") }

            if (biometricReady) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    enabled = !busy,
                    onClick = { startBiometric() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Text("  Войти по биометрии")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { recoveryMode = true; viewModel.clearAuthError() }) {
                Text("Забыли пароль? Ключ восстановления")
            }
        }

        authError?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        if (busy) BusyOverlay()

        Spacer(Modifier.height(24.dp))
        Text(
            "После нескольких неверных попыток ввод временно блокируется — это защита от подбора пароля через интерфейс.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
