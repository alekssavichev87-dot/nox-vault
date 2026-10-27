package com.noxvault.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.noxvault.app.security.ThemeMode
import com.noxvault.app.ui.components.BusyOverlay
import com.noxvault.app.ui.components.PasswordField
import com.noxvault.app.ui.components.StrengthBar
import com.noxvault.app.ui.navigation.NoxBottomBar
import com.noxvault.app.ui.navigation.Routes
import com.noxvault.app.ui.screens.CsvImportScreen
import com.noxvault.app.ui.screens.GeneratorScreen
import com.noxvault.app.ui.screens.HomeScreen
import com.noxvault.app.ui.screens.ItemDetailScreen
import com.noxvault.app.ui.screens.ItemEditScreen
import com.noxvault.app.ui.screens.SettingsScreen
import com.noxvault.app.ui.screens.SetupScreen
import com.noxvault.app.ui.screens.UnlockScreen
import com.noxvault.app.ui.screens.VaultScreen
import com.noxvault.app.ui.theme.NOXVaultTheme
import com.noxvault.app.viewmodel.AuthState
import com.noxvault.app.viewmodel.VaultViewModel

/**
 * Корень интерфейса. Граф навигации зависит от состояния аутентификации:
 * пока хранилище заблокировано, экранов с данными в композиции вообще нет —
 * содержимое невозможно «подсмотреть» через навигацию назад.
 */
@Composable
fun NoxVaultApp(viewModel: VaultViewModel) {
    val themeMode by viewModel.themeMode.collectAsState()
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val authState by viewModel.authState.collectAsState()
    val pendingRecovery by viewModel.pendingRecoveryKey.collectAsState()
    val needsReset by viewModel.needsPasswordReset.collectAsState()
    val busy by viewModel.busy.collectAsState()

    NOXVaultTheme(darkTheme = dark) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            when (authState) {
                AuthState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                AuthState.NO_VAULT -> SetupScreen(viewModel)
                AuthState.LOCKED -> UnlockScreen(viewModel)
                AuthState.UNLOCKED -> UnlockedGraph(viewModel)
            }

            // Ключ восстановления показывается ровно один раз поверх интерфейса.
            pendingRecovery?.let { key ->
                RecoveryKeyDialog(
                    recoveryKey = key,
                    onCopy = { viewModel.copyText("NOX Vault recovery key", key) },
                    onDismiss = { viewModel.dismissRecoveryKey() }
                )
            }

            // Вход по ключу восстановления => обязательная установка нового мастер-пароля.
            if (authState == AuthState.UNLOCKED && needsReset && pendingRecovery == null) {
                PasswordResetDialog(viewModel)
            }

            if (busy && authState != AuthState.UNLOCKED && authState != AuthState.LOADING) {
                // Индикатор длительной криптооперации на экранах входа/создания рисуется ими самими.
            }
        }
    }
}

@Composable
private fun UnlockedGraph(viewModel: VaultViewModel) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (currentRoute?.substringBefore('?') in Routes.mainRoutes) {
                NoxBottomBar(navController, currentRoute)
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) { HomeScreen(viewModel, navController) }
            composable(
                route = "${Routes.VAULT}?category={category}",
                arguments = listOf(
                    navArgument("category") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                VaultScreen(
                    viewModel = viewModel,
                    navController = navController,
                    initialCategoryName = entry.arguments?.getString("category")
                )
            }
            composable(Routes.GENERATOR) { GeneratorScreen(viewModel, navController) }
            composable(Routes.SETTINGS) { SettingsScreen(viewModel, navController) }
            composable(Routes.CSV_IMPORT) { CsvImportScreen(viewModel, navController) }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("itemId") { type = NavType.StringType })
            ) { entry ->
                ItemDetailScreen(
                    viewModel = viewModel,
                    navController = navController,
                    itemId = entry.arguments?.getString("itemId").orEmpty()
                )
            }
            composable(
                route = "${Routes.EDIT}?itemId={itemId}&category={category}",
                arguments = listOf(
                    navArgument("itemId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("category") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                ItemEditScreen(
                    viewModel = viewModel,
                    navController = navController,
                    itemId = entry.arguments?.getString("itemId"),
                    initialCategoryName = entry.arguments?.getString("category")
                )
            }
        }
    }
}

@Composable
private fun RecoveryKeyDialog(recoveryKey: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { /* закрытие только после подтверждения сохранения */ },
        title = { Text("Ключ восстановления") },
        text = {
            Column {
                Text(
                    "Сохраните этот ключ в надёжном месте (лучше на бумаге). Он — единственный способ вернуть доступ, если забудете мастер-пароль. Ключ показывается ОДИН раз и нигде не хранится в открытом виде. Скриншот сделать нельзя — это защита от утечки.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                SelectionContainer {
                    Text(
                        text = recoveryKey,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.shapes.medium
                            )
                            .padding(14.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Text("Скопировать ключ")
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saved, onCheckedChange = { saved = it })
                    Text("Я сохранил ключ восстановления", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, enabled = saved) { Text("Готово") }
        }
    )
}

@Composable
private fun PasswordResetDialog(viewModel: VaultViewModel) {
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { /* обязательный шаг после восстановления */ },
        title = { Text("Задайте новый мастер-пароль") },
        text = {
            Column {
                Text(
                    "Вы вошли по ключу восстановления. Установите новый мастер-пароль — старый больше не действует.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                PasswordField(newPassword, { newPassword = it; error = null }, "Новый мастер-пароль")
                Spacer(Modifier.height(8.dp))
                StrengthBar(newPassword)
                Spacer(Modifier.height(10.dp))
                PasswordField(confirm, { confirm = it; error = null }, "Повторите пароль")
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (working) BusyOverlay()
            }
        },
        confirmButton = {
            Button(
                enabled = !working && newPassword.length >= 8 && newPassword == confirm,
                onClick = {
                    working = true
                    viewModel.setNewMasterPasswordAfterRecovery(newPassword) { ok, msg ->
                        working = false
                        if (!ok) error = msg else viewModel.message(msg)
                    }
                }
            ) { Text("Установить пароль") }
        }
    )
}
