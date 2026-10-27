package com.noxvault.app

import com.noxvault.app.autofill.AutofillMatcher
import com.noxvault.app.domain.model.FieldType
import com.noxvault.app.domain.model.VaultField
import com.noxvault.app.domain.model.VaultItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillMatcherTest {

    private fun item(
        title: String,
        login: String = "user@example.com",
        password: String = "Secret!123",
        url: String = "",
        favorite: Boolean = false,
        lastOpenedAt: Long = 0L
    ) = VaultItem(
        title = title,
        favorite = favorite,
        lastOpenedAt = lastOpenedAt,
        fields = listOf(
            VaultField(label = "Логин", type = FieldType.LOGIN.name, value = login),
            VaultField(label = "Пароль", type = FieldType.PASSWORD.name, value = password, secret = true),
            VaultField(label = "Сайт", type = FieldType.URL.name, value = url)
        )
    )

    @Test
    fun extractsCredentialFromTypedFields() {
        val credential = AutofillMatcher.credentialFor(
            item("GitHub", login = "dev", password = "pw", url = "https://github.com")
        )
        assertEquals("dev", credential?.username)
        assertEquals("pw", credential?.password)
        assertEquals("https://github.com", credential?.url)
    }

    @Test
    fun itemWithoutPasswordIsNotCandidate() {
        val noPassword = VaultItem(
            title = "Только логин",
            fields = listOf(VaultField(label = "Логин", type = FieldType.LOGIN.name, value = "u"))
        )
        assertNull(AutofillMatcher.credentialFor(noPassword))
        assertTrue(AutofillMatcher.candidates(listOf(noPassword)).isEmpty())
    }

    @Test
    fun exactDomainBeatsLooseTitleMatch() {
        val exact = item("Мой аккаунт", url = "https://www.github.com/login")
        val loose = item("GitHub notes", url = "https://example.org")
        val result = AutofillMatcher.match(listOf(loose, exact), "github.com", "com.android.chrome")
        assertEquals("Мой аккаунт", result.first().item.title)
    }

    @Test
    fun subdomainMatchesRegisteredDomain() {
        val corporate = item("Corp", url = "https://accounts.example.com")
        val result = AutofillMatcher.match(listOf(corporate), "mail.example.com", null)
        assertEquals(1, result.size)
    }

    @Test
    fun packageTokensAndAppLabelMatchTitle() {
        val telegram = item("Telegram")
        val byPackage = AutofillMatcher.match(
            listOf(telegram), null, "org.telegram.messenger", null
        )
        assertEquals(1, byPackage.size)

        val bank = item("Мой банк")
        val byLabel = AutofillMatcher.match(
            listOf(bank), null, "ru.example.financial", "Мой банк"
        )
        assertEquals(1, byLabel.size)
    }

    @Test
    fun unrelatedDomainProducesNoSuggestions() {
        val result = AutofillMatcher.match(
            listOf(item("Почта", url = "https://mail.example.com")),
            "unrelated-service.net",
            "com.android.chrome"
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun candidatesPutFavoritesAndRecentFirst() {
        val oldFavorite = item("B", favorite = true, lastOpenedAt = 1)
        val recent = item("A", favorite = false, lastOpenedAt = 100)
        val result = AutofillMatcher.candidates(listOf(recent, oldFavorite))
        assertEquals("B", result[0].item.title)
        assertEquals("A", result[1].item.title)
    }

    @Test
    fun hostNormalizationStripsSchemeWwwPathAndPort() {
        assertEquals(
            "login.example.com",
            AutofillMatcher.normalizeHost("https://www.login.example.com:8443/path?next=/home")
        )
    }
}
