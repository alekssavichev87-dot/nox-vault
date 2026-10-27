package com.noxvault.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import com.noxvault.app.security.BiometricHelper
import com.noxvault.app.security.ThemeMode
import com.noxvault.app.ui.components.BusyOverlay
import com.noxvault.app.ui.components.ConfirmDialog
import com.noxvault.app.ui.components.NoxCard
import com.noxvault.app.ui.components.OptionPickerDialog
import com.noxvault.app.ui.components.PasswordField
import com.noxvault.app.ui.components.SectionTitle
import com.noxvault.app.ui.components.rememberOpenFilePicker
import com.noxvault.app.ui.components.rememberSaveFilePicker
import com.noxvault.app.ui.components.StrengthBar
import com.noxvault.app.ui.navigation.Routes
import com.noxvault.app.ui.theme.NoxError
import com.noxvault.app.viewmodel.VaultViewModel

/** Экран 8. Настройки: безопасность, биометрия, автоблокировка, тема, резервные копии. */
@Composable
fun SettingsScreen(viewModel: VaultViewModel, navController: NavController) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsState()
    val busy by viewModel.busy.collectAsState()

    fun isAutofillServiceSelected(): Boolean =
        context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true

    var biometricOn by remember {
        mutableStateOf(viewModel.settings.biometricEnabled && viewModel.biometric.hasBlob(viewModel.biometricFile))
    }
    var wipeOnFail by remember { mutableStateOf(viewModel.settings.wipeOnFailedAttempts) }
    var autoLockMinutes by remember { mutableStateOf(viewModel.settings.autoLockMinutes) }
    var clipboardSeconds by remember { mutableStateOf(viewModel.settings.clipboardClearSeconds) }
    var autofillOn by remember { mutableStateOf(viewModel.settings.autofillEnabled) }
    var autofillServiceSelected by remember { mutableStateOf(isAutofillServiceSelected()) }

    var showChangePassword by remember { mutableStateOf(false) }
    var showRegenRecovery by remember { mutableStateOf(false) }
    var showExportPassword by remember { mutableStateOf(false) }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var showAutoLockPicker by remember { mutableStateOf(false) }
    var showClipboardPicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var showWipePassword by remember { mutableStateOf(false) }

    // Новый ключ восстановления создан — закрыть диалог ввода пароля (глобальный диалог покажет сам ключ).
    val pendingRecovery by viewModel.pendingRecoveryKey.collectAsState()
    androidx.compose.runtime.LaunchedEffect(pendingRecovery) {
        if (pendingRecovery != null) showRegenRecovery = false
    }

    val saveBackupFile = rememberSaveFilePicker(viewModel, "application/octet-stream") { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (password != null) {
            viewModel.exportBackup(uri, password) { ok, msg ->
                viewModel.message(msg)
                if (!ok) { /* сообщение уже показано Snackbar-ом */ }
            }
        }
    }
    val pickImportFile = rememberOpenFilePicker(viewModel) { uri -> importUri = uri }

    val autofillSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.endExternalActivity()
        autofillServiceSelected = isAutofillServiceSelected()
    }

    fun openAutofillSystemSettings() {
        val requestIntent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            .setData(Uri.parse("package:${context.packageName}"))
        try {
            viewModel.beginExternalActivity()
            autofillSettingsLauncher.launch(requestIntent)
        } catch (e: Exception) {
            try {
                autofillSettingsLauncher.launch(Intent(Settings.ACTION_SETTINGS))
                viewModel.message("Откройте: Пароли и автозаполнение → Служба автозаполнения → NOX Vault")
            } catch (fallbackError: Exception) {
                viewModel.endExternalActivity()
                viewModel.message(
                    "Не удалось открыть настройки автозаполнения (${e.javaClass.simpleName}: ${e.message ?: "нет деталей"}). " +
                        "Откройте вручную: Настройки телефона → Пароли и автозаполнение."
                )
            }
        }
    }

    fun startEnableBiometric() {
        val activity = context as? FragmentActivity
        if (activity == null) {
            viewModel.message("Не удалось открыть биометрию")
            return
        }
        try {
            val cipher = viewModel.biometric.encryptCipher()
            viewModel.biometric.authenticate(
                activity = activity,
                cipher = cipher,
                title = "Включение биометрии",
                subtitle = "Подтвердите, чтобы разрешить разблокировку NOX Vault отпечатком или лицом",
                onSuccess = { authorized ->
                    viewModel.enableBiometricWithCipher(authorized)
                    biometricOn = true
                },
                onUsePassword = { biometricOn = false },
                onError = {
                    biometricOn = false
                    viewModel.message(it)
                }
            )
        } catch (e: Exception) {
            biometricOn = false
            viewModel.message("Биометрия сейчас недоступна: ${e.message ?: "ошибка Keystore"}")
        }
    }

    fun onBiometricToggle(want: Boolean) {
        if (!want) {
            viewModel.disableBiometric()
            biometricOn = false
            return
        }
        when (viewModel.biometric.availability()) {
            BiometricHelper.Availability.Available -> startEnableBiometric()
            BiometricHelper.Availability.NoHardware ->
                viewModel.message("На этом устройстве нет датчика отпечатка или распознавания лица")
            BiometricHelper.Availability.NotEnrolled ->
                viewModel.message("Сначала добавьте отпечаток или лицо в настройках телефона (Безопасность → Биометрия)")
            BiometricHelper.Availability.Unavailable ->
                viewModel.message("Биометрия временно недоступна на этом устройстве")
        }
    }

    val autoLockOptions = listOf(
        "Сразу при сворачивании" to 0,
        "Через 1 минуту" to 1,
        "Через 5 минут" to 5,
        "Через 15 минут" to 15,
        "Через 30 минут" to 30,
        "Никогда (не рекомендуется)" to -1
    )
    val clipboardOptions = listOf(
        "Через 15 секунд" to 15,
        "Через 30 секунд" to 30,
        "Через 60 секунд" to 60,
        "Не очищать автоматически" to 0
    )
    val themeOptions = listOf(
        "Тёмная (основная)" to ThemeMode.DARK,
        "Светлая" to ThemeMode.LIGHT,
        "Как в системе" to ThemeMode.SYSTEM
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Row(modifier = Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.lockNow() }) {
                Icon(Icons.Filled.Lock, contentDescription = "Заблокировать хранилище сейчас")
            }
        }
        if (busy) BusyOverlay()

        SectionTitle("Безопасность")
        NoxCard {
            Column {
                SettingRow(
                    Icons.Filled.VpnKey, "Сменить мастер-пароль",
                    "Данные не перешифровываются целиком — меняется только защита ключа"
                ) { showChangePassword = true }
                SettingDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Биометрическая разблокировка", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Дополнительно к паролю. Ключ хранится в Android Keystore только этого телефона",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = biometricOn, onCheckedChange = { onBiometricToggle(it) })
                }
                SettingDivider()
                SettingRow(
                    Icons.Filled.Timer, "Автоматическая блокировка",
                    autoLockOptions.firstOrNull { it.second == autoLockMinutes }?.first ?: "—",
                    showValue = true
                ) { showAutoLockPicker = true }
                SettingDivider()
                SettingRow(
                    Icons.Filled.ContentPaste, "Очистка буфера обмена",
                    clipboardOptions.firstOrNull { it.second == clipboardSeconds }?.first ?: "—",
                    showValue = true
                ) { showClipboardPicker = true }
                SettingDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = NoxError)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Удалить хранилище после 10 неверных паролей", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "ОПАСНО: кто угодно с телефоном в руках сможет уничтожить ваши данные. Сначала сделайте копию .nox!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = wipeOnFail, onCheckedChange = {
                        wipeOnFail = it
                        viewModel.settings.wipeOnFailedAttempts = it
                        if (it) viewModel.message("Включено удаление после 10 ошибок. Убедитесь, что копия .nox сохранена!")
                    })
                }
            }
        }

        SectionTitle("Автозаполнение")
        NoxCard {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Предлагать логины и пароли", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "В формах входа браузеров и приложений. Если хранилище закрыто — сначала мастер-пароль или биометрия",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autofillOn, onCheckedChange = {
                        autofillOn = it
                        viewModel.settings.autofillEnabled = it
                        if (it && !autofillServiceSelected) {
                            viewModel.message("Осталось выбрать NOX Vault службой автозаполнения Android — нажмите строку ниже")
                        }
                    })
                }
                SettingDivider()
                SettingRow(
                    Icons.Filled.Shield, "Служба автозаполнения Android",
                    if (autofillServiceSelected) "NOX Vault выбрана в настройках телефона"
                    else "Не выбрана — нажмите, чтобы открыть настройки телефона",
                    showValue = autofillServiceSelected
                ) { openAutofillSystemSettings() }
                SettingDivider()
                Text(
                    "Запись находится по сайту из поля «Ссылка (URL)», названию и имени приложения. " +
                        "Для предложения обязательно заполненное поле «Пароль». Эта версия только подставляет данные — " +
                        "новые пароли из других приложений она пока не сохраняет автоматически.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }
        }

        SectionTitle("Восстановление доступа")
        NoxCard {
            Column {
                SettingRow(
                    Icons.Filled.Key, "Новый ключ восстановления",
                    if (viewModel.hasRecoverySlot()) "Старый ключ перестанет работать сразу после создания нового"
                    else "Создать ключ восстановления для этого хранилища"
                ) { showRegenRecovery = true }
            }
        }

        SectionTitle("Оформление")
        NoxCard {
            Column {
                SettingRow(
                    Icons.Filled.Palette, "Тема",
                    themeOptions.firstOrNull { it.second == themeMode }?.first ?: "—",
                    showValue = true
                ) { showThemePicker = true }
            }
        }

        SectionTitle("Резервные копии")
        NoxCard {
            Column {
                SettingRow(
                    Icons.Filled.Backup, "Создать зашифрованную копию .nox",
                    "Один файл со всеми записями и вложениями, защищён отдельным паролем"
                ) { showExportPassword = true }
                SettingDivider()
                SettingRow(
                    Icons.Filled.Restore, "Восстановить из копии .nox",
                    "Заменит текущее хранилище на этом устройстве"
                ) { pickImportFile() }
                SettingDivider()
                SettingRow(
                    Icons.Filled.UploadFile, "Импорт паролей из браузера (CSV)",
                    "Chrome, Edge, Firefox, Safari: каждая строка файла станет отдельной записью. Файл CSV не зашифрован — удалите его после импорта!"
                ) { navController.navigate(Routes.CSV_IMPORT) }
            }
        }
        Text(
            "Совет: храните файл .nox и его пароль раздельно (например, файл — на флешке, пароль — на бумаге вместе с ключом восстановления).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        SectionTitle("О приложении")
        NoxCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("NOX Vault 1.2.0 — статус защиты", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "• Данные хранятся только на этом устройстве в зашифрованном файле vault.nox (AES-256-GCM, ключ из пароля — Argon2id).\n" +
                                "• Приложению НЕ разрешён интернет: нет серверов, аккаунтов, рекламы и аналитики.\n" +
                                "• Системный облачный бэкап Android для данных приложения отключён.\n" +
                                "• Скриншоты экрана и превью в переключателе задач заблокированы (FLAG_SECURE).\n" +
                                "• Перенос на другой телефон — только вручную через файл .nox.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        SectionTitle("Опасная зона")
        NoxCard {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showWipeConfirm = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = NoxError)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Полностью удалить хранилище", style = MaterialTheme.typography.bodyLarge, color = NoxError)
                        Text(
                            "Все записи на этом устройстве будут стёрты без возможности восстановления (кроме вашей копии .nox)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(36.dp))
    }

    // ---------- Диалоги ----------

    if (showChangePassword) {
        ChangePasswordDialog(
            onDismiss = { showChangePassword = false },
            onSubmit = { current, new, done ->
                viewModel.changeMasterPassword(current, new) { ok, msg ->
                    done(ok, msg)
                    if (ok) showChangePassword = false
                }
            }
        )
    }

    if (showRegenRecovery) {
        PasswordConfirmDialog(
            title = "Новый ключ восстановления",
            text = "Введите текущий мастер-пароль. Прежний ключ восстановления немедленно перестанет действовать.",
            confirmText = "Создать новый ключ",
            onDismiss = { showRegenRecovery = false },
            onSubmit = { password, done ->
                // Неверный пароль => done(ошибка), диалог остаётся. Успех => появится
                // pendingRecoveryKey, и LaunchedEffect выше закроет этот диалог.
                viewModel.regenerateRecoveryKey(password) { error -> done(error) }
            }
        )
    }

    if (showExportPassword) {
        ExportPasswordDialog(
            onDismiss = { showExportPassword = false },
            onReady = { password ->
                showExportPassword = false
                pendingExportPassword = password
                saveBackupFile("noxvault-backup.nox")
            }
        )
    }

    importUri?.let { uri ->
        ImportBackupDialog(
            viewModel = viewModel,
            uri = uri,
            hasExistingVault = true,
            onDismiss = { importUri = null },
            onSuccess = { importUri = null }
        )
    }

    if (showAutoLockPicker) {
        OptionPickerDialog(
            title = "Автоматическая блокировка в фоне",
            options = autoLockOptions.map { it.first },
            selectedIndex = autoLockOptions.indexOfFirst { it.second == autoLockMinutes }.coerceAtLeast(0),
            onPick = { index ->
                autoLockMinutes = autoLockOptions[index].second
                viewModel.settings.autoLockMinutes = autoLockMinutes
                showAutoLockPicker = false
            },
            onDismiss = { showAutoLockPicker = false }
        )
    }

    if (showClipboardPicker) {
        OptionPickerDialog(
            title = "Очищать буфер после копирования",
            options = clipboardOptions.map { it.first },
            selectedIndex = clipboardOptions.indexOfFirst { it.second == clipboardSeconds }.coerceAtLeast(0),
            onPick = { index ->
                clipboardSeconds = clipboardOptions[index].second
                viewModel.settings.clipboardClearSeconds = clipboardSeconds
                showClipboardPicker = false
            },
            onDismiss = { showClipboardPicker = false }
        )
    }

    if (showThemePicker) {
        OptionPickerDialog(
            title = "Тема оформления",
            options = themeOptions.map { it.first },
            selectedIndex = themeOptions.indexOfFirst { it.second == themeMode }.coerceAtLeast(0),
            onPick = { index ->
                viewModel.setThemeMode(themeOptions[index].second)
                showThemePicker = false
            },
            onDismiss = { showThemePicker = false }
        )
    }

    if (showWipeConfirm) {
        ConfirmDialog(
            title = "Удалить хранилище полностью?",
            text = "Это действие нельзя отменить. Все пароли, ключи, заметки и вложения на этом устройстве исчезнут. Если у вас нет резервной копии .nox — данные пропадут навсегда.",
            confirmText = "Продолжить удаление",
            onConfirm = {
                showWipeConfirm = false
                showWipePassword = true
            },
            onDismiss = { showWipeConfirm = false }
        )
    }

    if (showWipePassword) {
        PasswordConfirmDialog(
            title = "Подтверждение удаления",
            text = "Введите текущий мастер-пароль, чтобы окончательно удалить хранилище.",
            confirmText = "Удалить навсегда",
            onDismiss = { showWipePassword = false },
            onSubmit = { password, done ->
                viewModel.wipeVault(password) { ok, msg ->
                    if (ok) {
                        showWipePassword = false
                        done(null)
                    } else {
                        done(msg)
                    }
                }
            }
        )
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    showValue: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (showValue) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: (current: String, new: String, done: (Boolean, String) -> Unit) -> Unit
) {
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Сменить мастер-пароль") },
        text = {
            Column {
                PasswordField(current, { current = it; error = null }, "Текущий пароль")
                Spacer(Modifier.height(12.dp))
                PasswordField(newPassword, { newPassword = it; error = null }, "Новый пароль")
                Spacer(Modifier.height(8.dp))
                StrengthBar(newPassword)
                Spacer(Modifier.height(10.dp))
                PasswordField(confirm, { confirm = it; error = null }, "Повторите новый пароль")
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (working) BusyOverlay()
            }
        },
        confirmButton = {
            Button(
                enabled = !working && current.isNotEmpty() && newPassword.length >= 8 && newPassword == confirm,
                onClick = {
                    working = true
                    onSubmit(current, newPassword) { ok, msg ->
                        working = false
                        if (!ok) error = msg
                    }
                }
            ) { Text("Сменить пароль") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Отмена") } }
    )
}

@Composable
private fun PasswordConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onSubmit: (password: String, done: (String?) -> Unit) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                PasswordField(password, { password = it; error = null }, "Мастер-пароль")
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (working) BusyOverlay()
            }
        },
        confirmButton = {
            Button(
                enabled = !working && password.isNotEmpty(),
                onClick = {
                    working = true
                    onSubmit(password) { errorText ->
                        working = false
                        error = errorText
                    }
                }
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Отмена") } }
    )
}
