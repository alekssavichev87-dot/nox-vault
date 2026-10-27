package com.noxvault.app

import com.noxvault.app.domain.model.FieldType
import com.noxvault.app.domain.model.ItemCategory
import com.noxvault.app.domain.model.Templates
import com.noxvault.app.domain.model.VaultField
import com.noxvault.app.domain.model.VaultItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun templates_markPasswordFieldsSecret() {
        val fields = Templates.fieldsFor(ItemCategory.WIFI)
        val passwordField = fields.first { it.fieldType() == FieldType.PASSWORD }
        assertTrue(passwordField.isSecretEffective())
    }

    @Test
    fun search_matchesTitleTagsAndFields() {
        val item = VaultItem(
            title = "GitHub работа",
            tags = listOf("код", "важное"),
            fields = listOf(VaultField(label = "Логин", type = FieldType.LOGIN.name, value = "octocat@example.com"))
        )
        assertTrue(item.matchesQuery("github"))
        assertTrue(item.matchesQuery("КОД"))
        assertTrue(item.matchesQuery("octocat"))
        assertTrue(item.matchesQuery(""))
        assertFalse(item.matchesQuery("netflix"))
    }

    @Test
    fun preview_neverShowsSecretValue() {
        val item = VaultItem(
            title = "Банк",
            fields = listOf(
                VaultField(label = "Пароль", type = FieldType.PASSWORD.name, value = "top-secret-123", secret = true)
            )
        )
        assertFalse(item.previewText().contains("top-secret"))
    }
}
