package com.noxvault.app.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.noxvault.app.R
import com.noxvault.app.data.VaultRepository
import com.noxvault.app.security.SettingsStore

/**
 * Служба Android Autofill Framework.
 *
 * Принципы безопасности:
 * - работает только если пользователь включил переключатель в настройках NOX Vault
 *   И выбрал NOX Vault службой автозаполнения Android;
 * - никогда не расшифровывает хранилище в фоне: при заблокированной сессии возвращает
 *   только dataset «Разблокировать NOX Vault», ведущий на обычный экран мастер-пароля;
 * - при уже открытой сессии соблюдает тот же таймаут автоблокировки, что и UI;
 * - значения полей чужой формы не читает, секреты не логирует, своё же приложение игнорирует.
 *
 * Сохранение новых паролей из чужих форм (onSaveRequest) в этой версии не делается:
 * FillResponse не содержит SaveInfo, поэтому Android и не будет предлагать сохранение.
 */
class NoxAutofillService : AutofillService() {

    private val settings by lazy { SettingsStore(this) }
    private val repository by lazy { VaultRepository.getInstance(this) }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        try {
            if (!settings.autofillEnabled || cancellationSignal.isCanceled) {
                callback.onSuccess(null)
                return
            }

            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) {
                callback.onSuccess(null)
                return
            }

            val parsed = AutofillStructureParser.parse(structure)
            if (!parsed.hasFillableFields || parsed.packageName == packageName ||
                !repository.vaultExists()
            ) {
                callback.onSuccess(null)
                return
            }

            expireSessionIfNeeded()
            val appLabel = appLabelFor(parsed)
            val response = FillResponse.Builder()

            if (repository.isUnlocked) {
                val items = repository.data?.items.orEmpty()
                val matches = AutofillMatcher.match(
                    items = items,
                    webDomain = parsed.webDomain,
                    packageName = parsed.packageName,
                    appLabel = appLabel
                ).take(MAX_DIRECT_SUGGESTIONS)

                matches.forEach { credential ->
                    credentialDataset(parsed, credential)?.let { response.addDataset(it) }
                }
                // Даже при совпадении домена даём ручной выбор: эвристика может ошибиться.
                response.addDataset(
                    authenticationDataset(
                        parsed = parsed,
                        appLabel = appLabel,
                        title = "Выбрать в NOX Vault…",
                        subtitle = parsed.targetLabel
                    )
                )
            } else {
                response.addDataset(
                    authenticationDataset(
                        parsed = parsed,
                        appLabel = appLabel,
                        title = "Разблокировать NOX Vault",
                        subtitle = "Автозаполнение для ${parsed.targetLabel}"
                    )
                )
            }

            callback.onSuccess(response.build())
        } catch (e: Exception) {
            // Служба не имеет права ронять чужую форму. Диагностику без секретов
            // Android покажет только как отсутствие подсказок.
            runCatching { callback.onSuccess(null) }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // SaveInfo мы не выставляем, поэтому штатно сюда не попадают.
        // Если какая-то прошивка всё же вызовет метод — просто ничего не сохраняем.
        callback.onSuccess()
    }

    /**
     * Открытая из приложения сессия не должна жить вечно только потому, что пользователь
     * ушёл в браузер: здесь применяется та же настройка автоблокировки, что и в UI.
     */
    private fun expireSessionIfNeeded() {
        if (!repository.isUnlocked) return
        val minutes = settings.autoLockMinutes
        if (minutes < 0) return  // пользователь осознанно выбрал «никогда»
        val expired = if (minutes == 0) {
            true
        } else {
            System.currentTimeMillis() - repository.lastSessionActivityMs >= minutes * 60_000L
        }
        if (expired) repository.lock()
    }

    private fun credentialDataset(
        parsed: ParsedAutofillForm,
        credential: AutofillCredential
    ): Dataset? {
        // Форма только из поля логина + запись без логина = пустой Dataset,
        // который Android отклоняет. Такую подсказку пропускаем молча.
        if (parsed.passwordIds.isEmpty() && credential.username.isBlank()) return null
        val subtitle = when {
            credential.username.isNotBlank() -> credential.username
            credential.url.isNotBlank() -> credential.url
            else -> "NOX Vault"
        }
        val builder = Dataset.Builder(
            presentation(credential.item.title.ifBlank { "Без названия" }, subtitle)
        )
        if (credential.username.isNotBlank()) {
            parsed.usernameIds.forEach { id ->
                builder.setValue(id, AutofillValue.forText(credential.username))
            }
        }
        parsed.passwordIds.forEach { id ->
            builder.setValue(id, AutofillValue.forText(credential.password))
        }
        return builder.build()
    }

    private fun authenticationDataset(
        parsed: ParsedAutofillForm,
        appLabel: String?,
        title: String,
        subtitle: String
    ): Dataset {
        val intent = Intent(this, AutofillAuthActivity::class.java).apply {
            putExtra(AutofillAuthActivity.EXTRA_TARGET_LABEL, parsed.targetLabel)
            putExtra(AutofillAuthActivity.EXTRA_WEB_DOMAIN, parsed.webDomain)
            putExtra(AutofillAuthActivity.EXTRA_PACKAGE_NAME, parsed.packageName)
            putExtra(AutofillAuthActivity.EXTRA_APP_LABEL, appLabel)
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android добавляет AssistStructure в этот Intent перед показом экрана,
            // поэтому PendingIntent обязан быть mutable — иначе структура не долетит.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val requestCode = (parsed.packageName.orEmpty() + "|" + parsed.webDomain.orEmpty()).hashCode()
        val sender = PendingIntent.getActivity(this, requestCode, intent, flags).intentSender

        val builder = Dataset.Builder(presentation(title, subtitle))
        parsed.allIds.forEach { id -> builder.setValue(id, null) }
        builder.setAuthentication(sender)
        return builder.build()
    }

    private fun presentation(title: String, subtitle: String): RemoteViews =
        RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(R.id.autofill_dataset_title, title)
            setTextViewText(R.id.autofill_dataset_subtitle, subtitle)
            setViewVisibility(
                R.id.autofill_dataset_subtitle,
                if (subtitle.isBlank()) View.GONE else View.VISIBLE
            )
            setImageViewResource(R.id.autofill_dataset_icon, R.mipmap.ic_launcher)
        }

    private fun appLabelFor(parsed: ParsedAutofillForm): String? {
        if (!parsed.webDomain.isNullOrBlank()) return null
        val targetPackage = parsed.packageName?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val info = packageManager.getApplicationInfo(targetPackage, 0)
            packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private companion object {
        const val MAX_DIRECT_SUGGESTIONS = 5
    }
}
