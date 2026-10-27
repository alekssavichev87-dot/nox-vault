package com.noxvault.app.autofill

import com.noxvault.app.domain.model.FieldType
import com.noxvault.app.domain.model.VaultItem
import java.util.Locale

/** Пара логин/пароль, извлечённая из записи хранилища для автозаполнения. */
data class AutofillCredential(
    val item: VaultItem,
    val username: String,
    val password: String,
    val url: String
)

/**
 * Подбор записей под форму входа. Логика намеренно чистая (without Android types):
 * служба и экран выбора передают сюда уже найденные domain/package/label,
 * поэтому матчинг полностью покрывается JVM-тестами.
 *
 * Никогда не логируем поля: результат содержит значения, но вызывающий код обязан
 * обращаться с ними как с секретами (без LogCat, без Intent extras между экранами).
 */
object AutofillMatcher {

    fun credentialFor(item: VaultItem): AutofillCredential? {
        val password = item.fields.firstOrNull {
            it.fieldType() == FieldType.PASSWORD && it.value.isNotBlank()
        }?.value ?: item.fields.firstOrNull {
            it.value.isNotBlank() && it.isSecretEffective() && isPasswordLabel(it.label)
        }?.value ?: return null

        val username = item.fields.firstOrNull {
            it.fieldType() == FieldType.LOGIN && it.value.isNotBlank()
        }?.value ?: item.fields.firstOrNull {
            it.value.isNotBlank() && !it.isSecretEffective() && isUsernameLabel(it.label)
        }?.value ?: item.fields.firstOrNull {
            // Многие импортированные/ручные записи хранят email как обычный текст.
            it.value.isNotBlank() && !it.isSecretEffective() &&
                it.label.contains("mail", ignoreCase = true)
        }?.value.orEmpty()

        val url = item.fields.firstOrNull {
            it.fieldType() == FieldType.URL && it.value.isNotBlank()
        }?.value.orEmpty()

        return AutofillCredential(item, username, password, url)
    }

    /** Все записи, у которых вообще есть пароль и которые можно вставить в форму. */
    fun candidates(items: List<VaultItem>): List<AutofillCredential> =
        items.mapNotNull(::credentialFor)
            .sortedWith(
                compareByDescending<AutofillCredential> { it.item.favorite }
                    .thenByDescending { it.item.lastOpenedAt }
                    .thenBy { it.item.title.lowercase(Locale.ROOT) }
            )

    /**
     * Подходящие записи для текущего сайта/приложения, лучшие первыми.
     * Если совпадений нет, возвращается пустой список: UI тогда показывает ручной выбор.
     */
    fun match(
        items: List<VaultItem>,
        webDomain: String?,
        packageName: String?,
        appLabel: String? = null
    ): List<AutofillCredential> {
        val targetHost = normalizeHost(webDomain.orEmpty())
        val terms = targetTerms(targetHost, packageName, appLabel)
        if (terms.isEmpty() && targetHost.isBlank()) return emptyList()

        return candidates(items)
            .map { it to score(it, targetHost, terms, appLabel) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<AutofillCredential, Int>> { it.second }
                    .thenByDescending { it.first.item.favorite }
                    .thenByDescending { it.first.item.lastOpenedAt }
                    .thenByDescending { it.first.item.updatedAt }
            )
            .map { it.first }
    }

    internal fun normalizeHost(raw: String): String {
        var value = raw.trim().lowercase(Locale.ROOT)
        if (value.isBlank()) return ""
        value = value.substringAfter("://", value)
        value = value.substringAfter('@')
        value = value.substringBefore('/')
        value = value.substringBefore('?')
        value = value.substringBefore('#')
        value = value.substringBefore(':')
        if (value.startsWith("www.")) value = value.removePrefix("www.")
        return value.trim().trim('.')
    }

    private fun score(
        credential: AutofillCredential,
        targetHost: String,
        terms: Set<String>,
        appLabel: String?
    ): Int {
        var result = 0
        val itemHost = normalizeHost(credential.url)
        val title = credential.item.title.lowercase(Locale.ROOT)
        val allText = buildString {
            append(title)
            append(' ')
            append(credential.item.tags.joinToString(" ").lowercase(Locale.ROOT))
            append(' ')
            append(credential.url.lowercase(Locale.ROOT))
        }

        if (targetHost.isNotBlank() && itemHost.isNotBlank()) {
            when {
                itemHost == targetHost -> result += 120
                itemHost.endsWith(".$targetHost") || targetHost.endsWith(".$itemHost") -> result += 100
                domainKey(itemHost) == domainKey(targetHost) -> result += 70
            }
            if (title.contains(targetHost)) result += 45
        }

        val label = appLabel?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (label.length >= 3) {
            if (title == label) result += 90
            else if (title.contains(label) || label.contains(title) && title.length >= 3) result += 60
        }

        // Пакет приложения (org.telegram.messenger) и домен дают токены вроде
        // telegram/github/vk. Совпадение с заголовком — хороший fallback-сигнал.
        for (term in terms) {
            if (term.length < 3) continue
            if (title.contains(term)) result += 35
            else if (allText.contains(term)) result += 15
        }

        if (credential.item.favorite) result += 3
        return result
    }

    private fun targetTerms(host: String, packageName: String?, appLabel: String?): Set<String> {
        val terms = linkedSetOf<String>()
        if (host.isNotBlank()) {
            terms += host.split('.').filter { it.length >= 3 && it !in HOST_STOP_TOKENS }
            domainKey(host).takeIf { it.length >= 3 }?.let { terms += it }
        }
        packageName.orEmpty().split('.').forEach { part ->
            val token = part.lowercase(Locale.ROOT)
            if (token.length >= 3 && token !in PACKAGE_STOP_TOKENS) terms += token
        }
        appLabel.orEmpty().split(Regex("[^\\p{L}\\p{N}]+")).forEach { part ->
            val token = part.lowercase(Locale.ROOT)
            if (token.length >= 3) terms += token
        }
        return terms
    }

    /**
     * Очень грубая "корневая" часть домена: github из api.github.com.
     * Для ранжирования подсказок этого достаточно; финальный выбор всегда делает пользователь.
     */
    private fun domainKey(host: String): String {
        val labels = host.split('.').filter { it.isNotBlank() }
        if (labels.isEmpty()) return ""
        if (labels.size == 1) return labels[0]
        val maybeSecondLevel = labels.getOrNull(labels.lastIndex - 1).orEmpty()
        val tld = labels.last()
        return if (maybeSecondLevel in setOf("co", "com", "net", "org") && tld.length == 2 && labels.size >= 3) {
            labels[labels.lastIndex - 2]
        } else {
            maybeSecondLevel
        }
    }

    private fun isPasswordLabel(label: String): Boolean {
        val value = label.lowercase(Locale.ROOT)
        return PASSWORD_LABELS.any { value.contains(it) }
    }

    private fun isUsernameLabel(label: String): Boolean {
        val value = label.lowercase(Locale.ROOT)
        return USERNAME_LABELS.any { value.contains(it) }
    }

    private val PASSWORD_LABELS = listOf("парол", "password", "passwd", "passwort", "pwd", "pass")
    private val USERNAME_LABELS = listOf(
        "логин", "login", "user", "username", "email", "e-mail", "почт",
        "account", "аккаунт", "телефон", "phone"
    )
    private val PACKAGE_STOP_TOKENS = setOf(
        "com", "org", "net", "android", "app", "apps", "mobile", "client"
    )
    private val HOST_STOP_TOKENS = setOf("com", "org", "net", "www")
}
