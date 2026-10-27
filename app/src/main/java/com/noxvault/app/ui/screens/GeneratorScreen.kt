package com.noxvault.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.noxvault.app.crypto.PasswordGenerator
import com.noxvault.app.ui.components.NoxCard
import com.noxvault.app.ui.components.SectionTitle
import com.noxvault.app.ui.components.StrengthBar
import com.noxvault.app.viewmodel.VaultViewModel

/** Экран 7. Генератор паролей (SecureRandom) + компактный диалог для формы записи. */
@Composable
fun GeneratorScreen(viewModel: VaultViewModel, navController: NavController) {
    var options by remember { mutableStateOf(PasswordGenerator.Options()) }
    var password by remember { mutableStateOf(PasswordGenerator.generate(options)) }

    fun regenerate(newOptions: PasswordGenerator.Options = options) {
        options = newOptions
        password = runCatching { PasswordGenerator.generate(newOptions) }.getOrElse { password }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("Генератор паролей", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Случайность — из криптографического генератора SecureRandom",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))

        PasswordResultCard(password)
        Spacer(Modifier.height(10.dp))
        StrengthBar(password)
        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { regenerate() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text("  Новый пароль")
            }
            Spacer(Modifier.height(0.dp))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { viewModel.copySecret("Generated password", password) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Text("  Копировать пароль")
        }

        SectionTitle("Настройки")
        GeneratorOptionsControls(options) { regenerate(it) }
        Spacer(Modifier.height(32.dp))
    }
}

/** Диалог генератора при создании записи: «Использовать» подставляет пароль в поле. */
@Composable
fun GeneratorDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var options by remember { mutableStateOf(PasswordGenerator.Options()) }
    var password by remember { mutableStateOf(PasswordGenerator.generate(options)) }

    fun regenerate(newOptions: PasswordGenerator.Options = options) {
        options = newOptions
        password = runCatching { PasswordGenerator.generate(newOptions) }.getOrElse { password }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сгенерировать пароль") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PasswordResultCard(password)
                Spacer(Modifier.height(10.dp))
                StrengthBar(password)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { regenerate() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("  Другой вариант")
                }
                Spacer(Modifier.height(8.dp))
                GeneratorOptionsControls(options) { regenerate(it) }
            }
        },
        confirmButton = {
            Button(onClick = { onUse(password) }) { Text("Использовать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun PasswordResultCard(password: String) {
    NoxCard {
        Text(
            text = password,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large)
                .padding(16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorOptionsControls(
    options: PasswordGenerator.Options,
    onChange: (PasswordGenerator.Options) -> Unit
) {
    Column {
        Text("Длина: ${options.length}", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = options.length.toFloat(),
            onValueChange = { onChange(options.copy(length = it.toInt())) },
            valueRange = 8f..64f,
            steps = 55
        )
        OptionSwitch("Строчные буквы (a–z)", options.lowercase) { onChange(options.copy(lowercase = it)) }
        OptionSwitch("Заглавные буквы (A–Z)", options.uppercase) { onChange(options.copy(uppercase = it)) }
        OptionSwitch("Цифры (0–9)", options.digits) { onChange(options.copy(digits = it)) }
        OptionSwitch("Спецсимволы (!@#…)", options.symbols) { onChange(options.copy(symbols = it)) }
        OptionSwitch("Без похожих символов (I, l, 1, O, 0)", options.excludeAmbiguous) {
            onChange(options.copy(excludeAmbiguous = it))
        }
        if (!options.lowercase && !options.uppercase && !options.digits && !options.symbols) {
            Text(
                "Включите хотя бы один тип символов",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
