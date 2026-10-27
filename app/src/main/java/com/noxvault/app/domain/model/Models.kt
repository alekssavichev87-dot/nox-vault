package com.noxvault.app.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Модель данных хранилища. Всё это существует в открытом виде ТОЛЬКО в оперативной
 * памяти после разблокировки. На диск попадает исключительно внутри зашифрованного
 * контейнера (см. crypto/VaultContainer.kt). Типы хранятся строками (name enum),
 * чтобы будущие версии формата не ломали чтение старых данных.
 */

fun newId(): String = UUID.randomUUID().toString()

enum class FieldType(val titleRu: String) {
    TEXT("Текст"),
    SECRET("Секретный текст"),
    PASSWORD("Пароль"),
    LOGIN("Логин"),
    URL("Ссылка (URL)"),
    MULTILINE("Многострочная заметка"),
    DATE("Дата"),
    NUMBER("Число"),
    TOGGLE("Переключатель");

    fun secretByDefault(): Boolean = this == SECRET || this == PASSWORD

    companion object {
        fun fromName(name: String): FieldType = entries.firstOrNull { it.name == name } ?: TEXT
    }
}

enum class ItemCategory(val titleRu: String) {
    PASSWORD("Пароли"),
    ACCOUNT("Аккаунты"),
    API_KEY("API-ключи и токены"),
    APP_SETTINGS("Настройки программ"),
    NOTE("Защищённые заметки"),
    FILE("Файлы и документы"),
    WIFI("Wi-Fi и сети"),
    SERVER("Серверы и подключения"),
    LICENSE("Лицензии"),
    RECOVERY("Резервные коды"),
    CUSTOM("Своё");

    companion object {
        fun fromName(name: String): ItemCategory = entries.firstOrNull { it.name == name } ?: CUSTOM
    }
}

@Serializable
data class VaultField(
    val id: String = newId(),
    val label: String = "",
    val type: String = FieldType.TEXT.name,
    val value: String = "",
    val secret: Boolean = false
) {
    fun fieldType(): FieldType = FieldType.fromName(type)
    fun isSecretEffective(): Boolean = secret || fieldType().secretByDefault()
}

/**
 * Вложение v1: содержимое хранится Base64 внутри зашифрованного payload.
 * Ограничение размера при добавлении — 2 МБ (см. VaultViewModel), чтобы
 * контейнер не раздувался. Отдельные зашифрованные файлы вложений — план v2.
 */
@Serializable
data class AttachmentMeta(
    val id: String = newId(),
    val fileName: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
    val contentB64: String = ""
)

@Serializable
data class VaultItem(
    val id: String = newId(),
    val type: String = ItemCategory.CUSTOM.name,   // категория записи
    val title: String = "",
    val folderId: String? = null,
    val tags: List<String> = emptyList(),
    val fields: List<VaultField> = emptyList(),
    val attachments: List<AttachmentMeta> = emptyList(),
    val linkedIds: List<String> = emptyList(),
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0L,
    val version: Int = 1
) {
    fun category(): ItemCategory = ItemCategory.fromName(type)

    /** Короткое превью для списка: первый непустой НЕсекретный логин/текст. */
    fun previewText(): String {
        val login = fields.firstOrNull { it.fieldType() == FieldType.LOGIN && it.value.isNotBlank() }
        if (login != null) return login.value
        val visible = fields.firstOrNull { !it.isSecretEffective() && it.value.isNotBlank() }
        return visible?.value?.lineSequence()?.firstOrNull().orEmpty()
    }

    /** Поиск в памяти: название, теги, подписи и значения полей. */
    fun matchesQuery(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        if (title.lowercase().contains(q)) return true
        if (tags.any { it.lowercase().contains(q) }) return true
        if (category().titleRu.lowercase().contains(q)) return true
        return fields.any {
            it.label.lowercase().contains(q) || it.value.lowercase().contains(q)
        }
    }
}

@Serializable
data class Folder(
    val id: String = newId(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** Всё содержимое хранилища — именно это сериализуется и шифруется как payload. */
@Serializable
data class VaultData(
    val schemaVersion: Int = 1,
    val items: List<VaultItem> = emptyList(),
    val folders: List<Folder> = emptyList()
)

/** Шаблоны полей при создании записи выбранной категории. */
object Templates {

    private fun f(label: String, type: FieldType) = VaultField(
        label = label,
        type = type.name,
        secret = type.secretByDefault()
    )

    fun fieldsFor(category: ItemCategory): List<VaultField> = when (category) {
        ItemCategory.PASSWORD -> listOf(
            f("Логин", FieldType.LOGIN),
            f("Пароль", FieldType.PASSWORD),
            f("Сайт", FieldType.URL),
            f("Заметка", FieldType.MULTILINE)
        )
        ItemCategory.ACCOUNT -> listOf(
            f("Логин", FieldType.LOGIN),
            f("Пароль", FieldType.PASSWORD),
            f("Email", FieldType.TEXT),
            f("Сайт", FieldType.URL),
            f("Заметка", FieldType.MULTILINE)
        )
        ItemCategory.API_KEY -> listOf(
            f("API-ключ", FieldType.SECRET),
            f("Секрет", FieldType.SECRET),
            f("Сервис / URL", FieldType.URL),
            f("Заметка", FieldType.MULTILINE)
        )
        ItemCategory.APP_SETTINGS -> listOf(
            f("Программа", FieldType.TEXT),
            f("Настройки", FieldType.MULTILINE),
            f("Путь к конфигу", FieldType.TEXT)
        )
        ItemCategory.NOTE -> listOf(
            f("Текст заметки", FieldType.MULTILINE)
        )
        ItemCategory.FILE -> listOf(
            f("Описание", FieldType.MULTILINE)
        )
        ItemCategory.WIFI -> listOf(
            f("Имя сети (SSID)", FieldType.TEXT),
            f("Пароль", FieldType.PASSWORD),
            f("Тип защиты", FieldType.TEXT)
        )
        ItemCategory.SERVER -> listOf(
            f("Хост", FieldType.TEXT),
            f("Порт", FieldType.NUMBER),
            f("Логин", FieldType.LOGIN),
            f("Пароль", FieldType.PASSWORD),
            f("SSH-ключ", FieldType.SECRET),
            f("Заметка", FieldType.MULTILINE)
        )
        ItemCategory.LICENSE -> listOf(
            f("Продукт", FieldType.TEXT),
            f("Ключ лицензии", FieldType.SECRET),
            f("Действует до", FieldType.DATE)
        )
        ItemCategory.RECOVERY -> listOf(
            f("Сервис", FieldType.TEXT),
            f("Резервные коды", FieldType.SECRET)
        )
        ItemCategory.CUSTOM -> listOf(
            f("Значение", FieldType.TEXT)
        )
    }

    fun newItem(category: ItemCategory): VaultItem = VaultItem(
        type = category.name,
        fields = fieldsFor(category)
    )
}
