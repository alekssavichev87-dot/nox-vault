package com.noxvault.app.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import java.util.Locale

/** Тип поля формы, как его поняла эвристика NOX Vault. */
enum class AutofillFieldKind { USERNAME, PASSWORD, IGNORED }

/** Результат разбора AssistStructure: только id полей и несекретный контекст формы. */
data class ParsedAutofillForm(
    val usernameIds: List<AutofillId>,
    val passwordIds: List<AutofillId>,
    val webDomain: String?,
    val packageName: String?
) {
    val allIds: List<AutofillId> get() = (usernameIds + passwordIds).distinct()
    val hasFillableFields: Boolean get() = allIds.isNotEmpty()

    /** Безопасная подпись цели для UI: сайт или пакет, никогда — значения полей. */
    val targetLabel: String get() = webDomain?.takeIf { it.isNotBlank() }
        ?: packageName?.takeIf { it.isNotBlank() }
        ?: "текущее приложение"
}

/**
 * Классификация одного поля без AssistStructure — чтобы эвристики покрывались JVM-тестами.
 * Приоритет: autofillHints -> inputType -> id/hint.
 */
fun classifyAutofillField(
    hints: Array<out String>?,
    idEntry: String?,
    hintText: String?,
    isPasswordInput: Boolean,
    isEmailInput: Boolean
): AutofillFieldKind {
    val normalizedHints = hints.orEmpty().map { it.lowercase(Locale.ROOT) }
    if (normalizedHints.any { it.contains("password") }) return AutofillFieldKind.PASSWORD
    if (normalizedHints.any {
            it == View.AUTOFILL_HINT_USERNAME.lowercase(Locale.ROOT) ||
                it.contains("username") || it.contains("email")
        }
    ) return AutofillFieldKind.USERNAME

    if (isPasswordInput) return AutofillFieldKind.PASSWORD
    if (isEmailInput) return AutofillFieldKind.USERNAME

    val labels = listOfNotNull(idEntry, hintText).joinToString(" ").lowercase(Locale.ROOT)
    if (labels.isBlank()) return AutofillFieldKind.IGNORED
    if (PASSWORD_WORDS.any { labels.contains(it) }) return AutofillFieldKind.PASSWORD
    if (USERNAME_WORDS.any { labels.contains(it) }) return AutofillFieldKind.USERNAME
    return AutofillFieldKind.IGNORED
}

private val PASSWORD_WORDS = listOf("парол", "password", "passwd", "passwort", "pwd")
private val USERNAME_WORDS = listOf(
    "логин", "username", "user_name", "login", "email", "e-mail", "почт",
    "account", "аккаунт", "identifier", "phone", "телефон"
)

/** Обход дерева чужого экрана. Значения полей намеренно не читаем и никуда не сохраняем. */
object AutofillStructureParser {

    fun parse(structure: AssistStructure): ParsedAutofillForm {
        val usernameIds = linkedSetOf<AutofillId>()
        val passwordIds = linkedSetOf<AutofillId>()
        var webDomain: String? = null

        fun visit(node: AssistStructure.ViewNode) {
            if (webDomain.isNullOrBlank() && !node.webDomain.isNullOrBlank()) {
                webDomain = node.webDomain
            }

            val autofillId = node.autofillId
            if (autofillId != null && node.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_NO &&
                node.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            ) {
                val kind = classifyAutofillField(
                    hints = node.autofillHints,
                    idEntry = node.idEntry,
                    hintText = node.hint,
                    isPasswordInput = isPasswordInput(node.inputType),
                    isEmailInput = isEmailInput(node.inputType)
                )
                when (kind) {
                    AutofillFieldKind.USERNAME -> usernameIds += autofillId
                    AutofillFieldKind.PASSWORD -> passwordIds += autofillId
                    AutofillFieldKind.IGNORED -> Unit
                }
            }

            for (index in 0 until node.childCount) {
                node.getChildAt(index)?.let(::visit)
            }
        }

        for (index in 0 until structure.windowNodeCount) {
            structure.getWindowNodeAt(index)?.rootViewNode?.let(::visit)
        }

        return ParsedAutofillForm(
            usernameIds = usernameIds.toList(),
            passwordIds = passwordIds.toList(),
            webDomain = webDomain,
            packageName = structure.activityComponent?.packageName
        )
    }

    private fun isPasswordInput(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            (inputClass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun isEmailInput(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }
}
