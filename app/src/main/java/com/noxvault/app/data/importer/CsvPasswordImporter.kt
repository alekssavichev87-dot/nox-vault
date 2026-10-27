package com.noxvault.app.data.importer

import com.noxvault.app.domain.model.FieldType
import com.noxvault.app.domain.model.ItemCategory
import com.noxvault.app.domain.model.Templates
import com.noxvault.app.domain.model.VaultField
import com.noxvault.app.domain.model.VaultItem
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Импорт паролей из CSV, экспортированных браузерами.
 *
 * Поддерживаются форматы:
 *  - Chrome / Edge / Opera / Яндекс и другие Chromium: name,url,username,password,note
 *  - Firefox: url,username,password,httpRealm,formActionOrigin,guid,...
 *  - Safari: Title,URL,Username,Password,Notes,OTPAuth
 *  - произвольный CSV: автоопределение столбцов по заголовкам (EN/RU) + ручной выбор на экране.
 *
 * Парсер — RFC 4180: кавычки, запятые и переносы строк внутри полей, экранирование "",
 * разделители "," / ";" / Tab, BOM, кодировки UTF-8 / UTF-16 / Windows-1251.
 *
 * ВАЖНО ПО БЕЗОПАСНОСТИ: CSV из браузера — НЕЗАШИФРОВАННЫЙ файл со всеми паролями.
 * Приложение читает его только в память, ничего не логирует и после импорта очищает
 * сессию. Пользователь должен удалить исходный CSV с телефона/компьютера.
 *
 * Пароли НЕ обрезаются trim()-ом: пробелы по краям могут быть частью пароля.
 */

data class ParsedCsv(
    val headers: List<String>,
    val rows: List<List<String>>,
    val delimiter: Char,
    val hasHeaderRow: Boolean
)

data class ColumnMapping(
    val title: Int? = null,
    val url: Int? = null,
    val username: Int? = null,
    val password: Int? = null,
    val note: Int? = null,
    val otp: Int? = null
) {
    /** Без столбца пароля или логина импортировать нечего. */
    fun isUsable(): Boolean = password != null || username != null
}

enum class BrowserFormat(val labelRu: String) {
    CHROME("Chrome / Edge / Chromium"),
    FIREFOX("Firefox"),
    SAFARI("Safari"),
    GENERIC("Другой CSV")
}

data class ImportCandidate(
    val rowNumber: Int,
    val title: String,
    val url: String,
    val username: String,
    val password: String,
    val note: String,
    val otp: String
) {
    fun dedupKey(): String =
        listOf(title, username, url).joinToString("|") { it.trim().lowercase() }
}

object CsvPasswordImporter {

    const val MAX_ROWS = 10_000
    const val MAX_BYTES = 10 * 1024 * 1024

    private enum class Role { TITLE, URL, USERNAME, PASSWORD, NOTE, OTP }

    // ---------- Декодирование и парсинг ----------

    /** Байты файла -> строка. Браузеры пишут UTF-8, но страхуемся UTF-16 и Windows-1251. */
    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            runCatching { String(bytes, charset("windows-1251")) }
                .getOrElse { String(bytes, Charsets.UTF_8) }
        }
    }

    fun parse(text: String): ParsedCsv {
        val clean = text.removePrefix("\uFEFF")
        if (clean.isBlank()) return ParsedCsv(emptyList(), emptyList(), ',', hasHeaderRow = false)
        val delimiter = detectDelimiter(clean)
        val records = parseRecords(clean, delimiter)
            .filter { record -> record.any { it.isNotBlank() } }
            .take(MAX_ROWS + 1)
        if (records.isEmpty()) return ParsedCsv(emptyList(), emptyList(), delimiter, hasHeaderRow = false)

        val firstRoles = records.first().map { roleOf(it) }
        val knownRoles = firstRoles.filterNotNull().toSet()
        val hasHeader = knownRoles.size >= 2 &&
            (Role.PASSWORD in knownRoles || (Role.USERNAME in knownRoles && Role.URL in knownRoles))

        return if (hasHeader) {
            val headers = records.first().mapIndexed { index, name ->
                name.trim().ifBlank { "Столбец ${index + 1}" }
            }
            ParsedCsv(headers, records.drop(1).take(MAX_ROWS), delimiter, hasHeaderRow = true)
        } else {
            val width = records.maxOf { it.size }
            val headers = (1..width).map { "Столбец $it" }
            ParsedCsv(headers, records.take(MAX_ROWS), delimiter, hasHeaderRow = false)
        }
    }

    private fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().take(5).joinToString("\n")
        val candidates = listOf(',', ';', '\t')
        return candidates.maxByOrNull { sample.count { ch -> ch == it } }
            ?.takeIf { sample.contains(it) } ?: ','
    }

    /** RFC 4180 с мягким отношением к кавычкам посреди незакавыченного поля. */
    private fun parseRecords(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() {
            record.add(field.toString())
            field.setLength(0)
        }

        fun endRecord() {
            endField()
            records.add(record)
            record = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"'); i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else {
                when {
                    c == '"' && field.isEmpty() -> inQuotes = true
                    c == delimiter -> endField()
                    c == '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        endRecord()
                    }
                    c == '\n' -> endRecord()
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty() || inQuotes) endRecord()
        return records
    }

    // ---------- Определение формата и столбцов ----------

    private fun normalizeHeader(raw: String): String =
        raw.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), " ")

    private fun roleOf(rawHeader: String): Role? {
        val h = normalizeHeader(rawHeader)
        return when (h) {
            "password", "pass", "pwd", "пароль", "current password", "saved password" -> Role.PASSWORD
            "username", "user name", "user", "login", "username or email", "email", "e-mail",
            "логин", "имя пользователя", "пользователь", "почта", "электронная почта" -> Role.USERNAME
            "url", "website", "web site", "site", "login uri", "uri", "address", "login url",
            "адрес", "адрес сайта", "сайт", "ссылка" -> Role.URL
            "name", "title", "site name", "account name", "название", "имя", "сервис", "service" -> Role.TITLE
            "note", "notes", "comment", "comments", "заметка", "заметки", "комментарий", "описание" -> Role.NOTE
            "otpauth", "otp", "totp", "one-time password", "одноразовый пароль" -> Role.OTP
            else -> null
        }
    }

    fun detectFormat(parsed: ParsedCsv): BrowserFormat {
        if (!parsed.hasHeaderRow) return BrowserFormat.GENERIC
        val normalized = parsed.headers.map { it.lowercase().filter { ch -> ch.isLetterOrDigit() } }.toSet()
        return when {
            "httprealm" in normalized || "formactionorigin" in normalized -> BrowserFormat.FIREFOX
            "otpauth" in normalized -> BrowserFormat.SAFARI
            setOf("name", "url", "username", "password").all { it in normalized } -> BrowserFormat.CHROME
            else -> BrowserFormat.GENERIC
        }
    }

    fun detectMapping(parsed: ParsedCsv): ColumnMapping {
        if (parsed.headers.isEmpty()) return ColumnMapping()
        if (!parsed.hasHeaderRow) {
            // Позиционная догадка для CSV без заголовков; пользователь поправит на экране.
            return when {
                parsed.headers.size >= 4 -> ColumnMapping(title = 0, url = 1, username = 2, password = 3, note = 4.takeIf { parsed.headers.size > 4 })
                parsed.headers.size == 3 -> ColumnMapping(url = 0, username = 1, password = 2)
                parsed.headers.size == 2 -> ColumnMapping(username = 0, password = 1)
                else -> ColumnMapping()
            }
        }
        var mapping = ColumnMapping()
        parsed.headers.forEachIndexed { index, header ->
            when (roleOf(header)) {
                Role.TITLE -> if (mapping.title == null) mapping = mapping.copy(title = index)
                Role.URL -> if (mapping.url == null) mapping = mapping.copy(url = index)
                Role.USERNAME -> if (mapping.username == null) mapping = mapping.copy(username = index)
                Role.PASSWORD -> if (mapping.password == null) mapping = mapping.copy(password = index)
                Role.NOTE -> if (mapping.note == null) mapping = mapping.copy(note = index)
                Role.OTP -> if (mapping.otp == null) mapping = mapping.copy(otp = index)
                null -> Unit
            }
        }
        return mapping
    }

    // ---------- Строки -> кандидаты -> записи ----------

    fun mapRows(parsed: ParsedCsv, mapping: ColumnMapping): List<ImportCandidate> {
        fun cell(row: List<String>, index: Int?): String =
            if (index == null) "" else row.getOrElse(index) { "" }

        val firstRowNumber = if (parsed.hasHeaderRow) 2 else 1
        return parsed.rows.mapIndexedNotNull { i, row ->
            val url = cell(row, mapping.url).trim()
            val username = cell(row, mapping.username).trim()
            val password = cell(row, mapping.password)  // НЕ trim: пробелы могут быть частью пароля
            if (url.isBlank() && username.isBlank() && password.isBlank()) return@mapIndexedNotNull null
            val rawTitle = cell(row, mapping.title).trim()
            ImportCandidate(
                rowNumber = firstRowNumber + i,
                title = deriveTitle(rawTitle, url, username, firstRowNumber + i),
                url = url,
                username = username,
                password = password,
                note = cell(row, mapping.note).trim(),
                otp = cell(row, mapping.otp).trim()
            )
        }
    }

    private fun deriveTitle(rawTitle: String, url: String, username: String, rowNumber: Int): String {
        if (rawTitle.isNotBlank()) return rawTitle
        val host = hostFromUrl(url)
        if (host.isNotBlank()) return host
        if (username.isNotBlank()) return username
        return "Импорт, строка $rowNumber"
    }

    fun hostFromUrl(url: String): String {
        if (url.isBlank()) return ""
        var s = url.trim()
        val schemeEnd = s.indexOf("://")
        if (schemeEnd >= 0) s = s.substring(schemeEnd + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in s) s = s.substringAfter('@')
        s = s.substringBefore(':')  // порт
        if (s.startsWith("www.")) s = s.substring(4)
        return s
    }

    /** Кандидат -> полноценная запись категории «Пароли» с привычным набором полей. */
    fun candidateToItem(candidate: ImportCandidate, folderId: String?, tags: List<String>): VaultItem {
        val fields = Templates.fieldsFor(ItemCategory.PASSWORD).map { template ->
            when (template.fieldType()) {
                FieldType.LOGIN -> template.copy(value = candidate.username)
                FieldType.PASSWORD -> template.copy(value = candidate.password, secret = true)
                FieldType.URL -> template.copy(value = candidate.url)
                FieldType.MULTILINE -> template.copy(value = candidate.note)
                else -> template
            }
        }.toMutableList()
        if (candidate.otp.isNotBlank()) {
            fields.add(
                fields.lastIndex.coerceAtLeast(0),
                VaultField(
                    label = "Одноразовый код (TOTP)",
                    type = FieldType.SECRET.name,
                    value = candidate.otp,
                    secret = true
                )
            )
        }
        val now = System.currentTimeMillis()
        return VaultItem(
            type = ItemCategory.PASSWORD.name,
            title = candidate.title,
            folderId = folderId,
            tags = tags,
            fields = fields,
            createdAt = now,
            updatedAt = now
        )
    }

    /** Ключ дубликата для существующей записи хранилища (название+логин+сайт). */
    fun existingDedupKey(item: VaultItem): String {
        val login = item.fields.firstOrNull { it.fieldType() == FieldType.LOGIN }?.value
            ?: item.fields.firstOrNull { it.label.contains("логин", ignoreCase = true) }?.value
            ?: ""
        val url = item.fields.firstOrNull { it.fieldType() == FieldType.URL }?.value
            ?: item.fields.firstOrNull { it.label.contains("сайт", ignoreCase = true) }?.value
            ?: ""
        return listOf(item.title, login, url).joinToString("|") { it.trim().lowercase() }
    }
}
