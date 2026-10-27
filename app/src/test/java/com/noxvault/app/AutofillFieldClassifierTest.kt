package com.noxvault.app

import com.noxvault.app.autofill.AutofillFieldKind
import com.noxvault.app.autofill.classifyAutofillField
import org.junit.Assert.assertEquals
import org.junit.Test

class AutofillFieldClassifierTest {

    @Test
    fun passwordHintWinsOverEverything() {
        assertEquals(
            AutofillFieldKind.PASSWORD,
            classifyAutofillField(arrayOf("newPassword"), "user", "Логин", false, false)
        )
    }

    @Test
    fun emailHintIsUsername() {
        assertEquals(
            AutofillFieldKind.USERNAME,
            classifyAutofillField(arrayOf("emailAddress"), null, null, false, false)
        )
    }

    @Test
    fun passwordInputTypeIsPasswordEvenWithoutHint() {
        assertEquals(
            AutofillFieldKind.PASSWORD,
            classifyAutofillField(null, "field_2", null, isPasswordInput = true, isEmailInput = false)
        )
    }

    @Test
    fun russianAndEnglishLabelsAreUnderstood() {
        assertEquals(
            AutofillFieldKind.USERNAME,
            classifyAutofillField(null, "input_login", "Логин", false, false)
        )
        assertEquals(
            AutofillFieldKind.PASSWORD,
            classifyAutofillField(null, "passwordInput", "Пароль", false, false)
        )
    }

    @Test
    fun unrelatedSearchFieldIsIgnored() {
        assertEquals(
            AutofillFieldKind.IGNORED,
            classifyAutofillField(null, "search_query", "Поиск по сайту", false, false)
        )
    }
}
