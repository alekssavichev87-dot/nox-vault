package com.noxvault.app

import com.noxvault.app.data.importer.BrowserFormat
import com.noxvault.app.data.importer.CsvPasswordImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Импорт CSV из браузеров: форматы, кавычки, кодировки, разделители, дубликаты. */
class CsvPasswordImporterTest {

    private fun import(csv: String) =
        CsvPasswordImporter.parse(csv).let { parsed ->
            parsed to CsvPasswordImporter.mapRows(parsed, CsvPasswordImporter.detectMapping(parsed))
        }

    @Test
    fun chromeFormat_parsesAndSplitsIntoItems() {
        val csv = """
            name,url,username,password,note
            GitHub,https://github.com/login,octocat,Secret#1,work account
            Mail,https://mail.example.com,user@example.com,MailPw42,
        """.trimIndent()
        val (parsed, candidates) = import(csv)
        assertEquals(BrowserFormat.CHROME, CsvPasswordImporter.detectFormat(parsed))
        assertEquals(2, candidates.size)
        assertEquals("GitHub", candidates[0].title)
        assertEquals("octocat", candidates[0].username)
        assertEquals("Secret#1", candidates[0].password)

        val item = CsvPasswordImporter.candidateToItem(candidates[0], folderId = "f1", tags = listOf("импорт"))
        assertEquals("GitHub", item.title)
        assertEquals("f1", item.folderId)
        assertEquals(listOf("импорт"), item.tags)
        assertEquals("Secret#1", item.fields.first { it.label == "Пароль" }.value)
        assertTrue(item.fields.first { it.label == "Пароль" }.isSecretEffective())
        assertEquals("https://github.com/login", item.fields.first { it.label == "Сайт" }.value)
    }

    @Test
    fun quotedCommasQuotesAndNewlines_survive() {
        val csv = "name,url,username,password,note\n" +
            "\"Site, Inc.\",https://example.com,bob,\"p@ss,with\"\"quote\",\"line1\nline2\""
        val (_, candidates) = import(csv)
        assertEquals(1, candidates.size)
        assertEquals("Site, Inc.", candidates[0].title)
        assertEquals("p@ss,with\"quote", candidates[0].password)
        assertTrue(candidates[0].note.contains("line2"))
    }

    @Test
    fun passwordSpaces_areNotTrimmed() {
        val csv = "name,url,username,password\nA,https://a.com,u,\"  spaced  \""
        val (_, candidates) = import(csv)
        assertEquals("  spaced  ", candidates[0].password)
    }

    @Test
    fun firefoxFormat_titleFromHost() {
        val csv = "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\n" +
            "\"https://accounts.example.org/login\",\"bob\",\"pw1\",\"\",\"https://example.org\",\"{abc}\",\"1\",\"2\",\"3\""
        val (parsed, candidates) = import(csv)
        assertEquals(BrowserFormat.FIREFOX, CsvPasswordImporter.detectFormat(parsed))
        assertEquals(1, candidates.size)
        assertEquals("accounts.example.org", candidates[0].title)
        assertEquals("bob", candidates[0].username)
    }

    @Test
    fun safariFormat_otpBecomesSecretField() {
        val csv = "Title,URL,Username,Password,Notes,OTPAuth\n" +
            "Apple,https://appleid.apple.com,me@icloud.com,pw123,,otpauth://totp/Apple?secret=ABCDEF"
        val (parsed, candidates) = import(csv)
        assertEquals(BrowserFormat.SAFARI, CsvPasswordImporter.detectFormat(parsed))
        val item = CsvPasswordImporter.candidateToItem(candidates[0], null, emptyList())
        val otpField = item.fields.first { it.label.contains("TOTP") }
        assertEquals("otpauth://totp/Apple?secret=ABCDEF", otpField.value)
        assertTrue(otpField.isSecretEffective())
    }

    @Test
    fun semicolonAndBom_andRussianHeaders() {
        val csv = "\uFEFFназвание;сайт;логин;пароль;заметка\n" +
            "Банк;https://bank.example.ru;ivan;Parol123;основной"
        val (parsed, candidates) = import(csv)
        assertEquals(';', parsed.delimiter)
        assertEquals(1, candidates.size)
        assertEquals("Банк", candidates[0].title)
        assertEquals("Parol123", candidates[0].password)
    }

    @Test
    fun windows1251Bytes_decode() {
        val text = "название;логин;пароль\nПочта;petr;secret"
        val bytes = text.toByteArray(charset("windows-1251"))
        val decoded = CsvPasswordImporter.decodeBytes(bytes)
        val (_, candidates) = import(decoded)
        assertEquals(1, candidates.size)
        assertEquals("Почта", candidates[0].title)
    }

    @Test
    fun noHeader_positionalGuess() {
        val csv = "MySite,https://mysite.example,alice,alicePw\nOther,https://other.example,bob,bobPw"
        val (parsed, candidates) = import(csv)
        assertFalse(parsed.hasHeaderRow)
        assertEquals(2, candidates.size)
        assertEquals("alicePw", candidates[0].password)
    }

    @Test
    fun emptyAndJunk_doNotCrash() {
        assertTrue(CsvPasswordImporter.parse("").rows.isEmpty())
        assertTrue(CsvPasswordImporter.parse("   \n\n").rows.isEmpty())
        val (parsed, candidates) = import("just some text without delimiters")
        assertTrue(parsed.rows.size <= 1)
        assertTrue(candidates.isEmpty() || candidates.all { it.password.isBlank() })
    }

    @Test
    fun blankRows_skipped_andRowNumbersAssigned() {
        val csv = "name,url,username,password\n\nA,https://a.com,u1,p1\n,  , ,\nB,https://b.com,u2,p2"
        val (_, candidates) = import(csv)
        assertEquals(2, candidates.size)
        // Пустые записи отбрасываются ещё парсером, поэтому номера идут по данным, а не по сырым строкам файла.
        assertEquals(2, candidates[0].rowNumber)
        assertEquals(3, candidates[1].rowNumber)
    }

    @Test
    fun dedupKeys_matchBetweenCandidateAndExistingItem() {
        val csv = "name,url,username,password\nGitHub,https://github.com,octocat,pw"
        val (_, candidates) = import(csv)
        val item = CsvPasswordImporter.candidateToItem(candidates[0], null, emptyList())
        assertEquals(candidates[0].dedupKey(), CsvPasswordImporter.existingDedupKey(item))
    }

    @Test
    fun hostFromUrl_cases() {
        assertEquals("example.com", CsvPasswordImporter.hostFromUrl("https://www.example.com/path?q=1"))
        assertEquals("sub.example.com", CsvPasswordImporter.hostFromUrl("http://sub.example.com:8080/x"))
        assertEquals("example.com", CsvPasswordImporter.hostFromUrl("example.com/login"))
        assertEquals("", CsvPasswordImporter.hostFromUrl(""))
    }
}
