package com.noxvault.app.autofill

import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.noxvault.app.R
import com.noxvault.app.domain.model.VaultItem
import com.noxvault.app.security.ThemeMode
import com.noxvault.app.ui.components.EmptyState
import com.noxvault.app.ui.components.ItemCard
import com.noxvault.app.ui.components.SectionTitle
import com.noxvault.app.ui.screens.UnlockScreen
import com.noxvault.app.ui.theme.NOXVaultTheme
import com.noxvault.app.viewmodel.AuthState
import com.noxvault.app.viewmodel.VaultViewModel

/**
 * Экран аутентификации Android Autofill.
 *
 * Android открывает его поверх чужой формы и кладёт AssistStructure в Intent.
 * Мы показываем существующий экран мастер-пароля/биометрии, затем выбор записи и
 * возвращаем системе готовый Dataset через EXTRA_AUTHENTICATION_RESULT.
 *
 * Важная гарантия: если хранилище было ЗАБЛОКИРОВАНО до этого экрана, после выбора
 * записи (или отмены) оно снова блокируется. Одна вставка пароля в браузер не должна
 * оставлять всё хранилище открытым в фоновом процессе.
 */
class AutofillAuthActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()
    private var initiallyUnlocked: Boolean = false

    private val parsedForm: ParsedAutofillForm? by lazy {
        assistStructureFromIntent()?.let { AutofillStructureParser.parse(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()

        initiallyUnlocked = viewModel.repositoryUnlocked()
        viewModel.syncWithRepository()

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val dark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val authState by viewModel.authState.collectAsState()
            val needsReset by viewModel.needsPasswordReset.collectAsState()
            val parsed = remember { parsedForm }
            val appLabel = remember { intent.getStringExtra(EXTRA_APP_LABEL) }

            NOXVaultTheme(darkTheme = dark) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    when {
                        parsed == null || !parsed.hasFillableFields -> AutofillMessage(
                            title = "Не удалось прочитать форму",
                            text = "Android не передал NOX Vault поля этой формы. " +
                                "Вернитесь в приложение, коснитесь поля пароля ещё раз и повторите.",
                            buttonText = "Закрыть",
                            onButton = { cancelAndFinish() }
                        )

                        needsReset -> AutofillMessage(
                            title = "Сначала задайте новый мастер-пароль",
                            text = "Вы вошли по ключу восстановления. Откройте NOX Vault, " +
                                "установите новый мастер-пароль, и автозаполнение заработает.",
                            buttonText = "Понятно",
                            onButton = { cancelAndFinish() }
                        )

                        authState == AuthState.LOADING -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }

                        authState == AuthState.NO_VAULT -> AutofillMessage(
                            title = "Хранилища пока нет",
                            text = "Откройте NOX Vault и создайте хранилище — после этого " +
                                "автозаполнение сможет предлагать ваши записи.",
                            buttonText = "Закрыть",
                            onButton = { cancelAndFinish() }
                        )

                        authState == AuthState.LOCKED -> Column(Modifier.fillMaxSize()) {
                            AutofillHeader(
                                target = appLabel ?: parsed.targetLabel,
                                onClose = { cancelAndFinish() }
                            )
                            Box(Modifier.weight(1f)) {
                                UnlockScreen(viewModel)
                            }
                        }

                        authState == AuthState.UNLOCKED -> {
                            val vaultItems by viewModel.items.collectAsState()
                            AutofillPicker(
                                items = vaultItems,
                                parsed = parsed,
                                appLabel = appLabel,
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onPick = { finishWithCredential(it) },
                                onCancel = { cancelAndFinish() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (!initiallyUnlocked && isFinishing && viewModel.repositoryUnlocked()) {
            viewModel.lockNow()
        }
        super.onDestroy()
    }

    private fun finishWithCredential(credential: AutofillCredential) {
        val parsed = parsedForm
        if (parsed == null) {
            cancelAndFinish()
            return
        }
        val dataset = runCatching { buildResultDataset(parsed, credential) }.getOrNull()
        if (dataset == null) {
            cancelAndFinish()
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
        )
        if (!initiallyUnlocked) viewModel.lockNow()
        finish()
    }

    private fun cancelAndFinish() {
        setResult(Activity.RESULT_CANCELED)
        if (!initiallyUnlocked && viewModel.repositoryUnlocked()) viewModel.lockNow()
        finish()
    }

    private fun buildResultDataset(
        parsed: ParsedAutofillForm,
        credential: AutofillCredential
    ): Dataset {
        val presentation = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(
                R.id.autofill_dataset_title,
                credential.item.title.ifBlank { "Без названия" }
            )
            setTextViewText(R.id.autofill_dataset_subtitle, credential.username)
            setViewVisibility(
                R.id.autofill_dataset_subtitle,
                if (credential.username.isBlank()) View.GONE else View.VISIBLE
            )
            setImageViewResource(R.id.autofill_dataset_icon, R.mipmap.ic_launcher)
        }
        val builder = Dataset.Builder(presentation)
        var hasValue = false
        if (credential.username.isNotBlank()) {
            parsed.usernameIds.forEach {
                builder.setValue(it, AutofillValue.forText(credential.username))
                hasValue = true
            }
        }
        parsed.passwordIds.forEach {
            builder.setValue(it, AutofillValue.forText(credential.password))
            hasValue = true
        }
        check(hasValue) { "Нет подходящих полей для выбранной записи" }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun assistStructureFromIntent(): AssistStructure? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                AutofillManager.EXTRA_ASSIST_STRUCTURE,
                AssistStructure::class.java
            )
        } else {
            intent.getParcelableExtra<AssistStructure>(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        }
    }

    companion object {
        const val EXTRA_TARGET_LABEL = "com.noxvault.app.autofill.TARGET_LABEL"
        const val EXTRA_WEB_DOMAIN = "com.noxvault.app.autofill.WEB_DOMAIN"
        const val EXTRA_PACKAGE_NAME = "com.noxvault.app.autofill.PACKAGE_NAME"
        const val EXTRA_APP_LABEL = "com.noxvault.app.autofill.APP_LABEL"
    }
}

@Composable
private fun AutofillHeader(target: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Автозаполнение", style = MaterialTheme.typography.titleLarge)
            Text(
                target,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Отмена")
        }
    }
}

@Composable
private fun AutofillPicker(
    items: List<VaultItem>,
    parsed: ParsedAutofillForm,
    appLabel: String?,
    onToggleFavorite: (VaultItem) -> Unit,
    onPick: (AutofillCredential) -> Unit,
    onCancel: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val matched = remember(items, parsed.webDomain, parsed.packageName, appLabel) {
        AutofillMatcher.match(items, parsed.webDomain, parsed.packageName, appLabel)
    }
    val all = remember(items) { AutofillMatcher.candidates(items) }
    val matchedIds = remember(matched) { matched.map { it.item.id }.toSet() }
    val shownAll = remember(all, matchedIds, query) {
        val base = if (query.isBlank()) all.filterNot { it.item.id in matchedIds } else all
        if (query.isBlank()) base else base.filter { it.item.matchesQuery(query) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        AutofillHeader(
            target = appLabel ?: parsed.targetLabel,
            onClose = onCancel
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Поиск записи") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true
        )
        Spacer(Modifier.height(6.dp))

        LazyColumn(Modifier.weight(1f)) {
            if (query.isBlank() && matched.isNotEmpty()) {
                item { SectionTitle("Подходящие записи") }
                items(matched, key = { "match-${it.item.id}" }) { credential ->
                    ItemCard(
                        item = credential.item,
                        folderName = null,
                        onClick = { onPick(credential) },
                        onToggleFavorite = { onToggleFavorite(credential.item) }
                    )
                }
                if (shownAll.isNotEmpty()) {
                    item { SectionTitle("Все записи с паролем") }
                }
            }

            items(shownAll, key = { "all-${it.item.id}" }) { credential ->
                ItemCard(
                    item = credential.item,
                    folderName = null,
                    onClick = { onPick(credential) },
                    onToggleFavorite = { onToggleFavorite(credential.item) }
                )
            }

            if (matched.isEmpty() && shownAll.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Lock,
                        title = "Нет записей с паролем",
                        text = "Добавьте запись с логином и паролем в NOX Vault — и она появится здесь."
                    )
                }
            }
        }

        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
        ) {
            Text("Отмена")
        }
    }
}

@Composable
private fun AutofillMessage(title: String, text: String, buttonText: String, onButton: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(72.dp))
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 18.dp)
        )
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onButton, modifier = Modifier.fillMaxWidth()) {
            Text(buttonText)
        }
    }
}
