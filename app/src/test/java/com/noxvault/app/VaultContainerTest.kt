package com.noxvault.app

import com.noxvault.app.crypto.CorruptedVaultException
import com.noxvault.app.crypto.RecoveryKey
import com.noxvault.app.crypto.VaultContainerCodec
import com.noxvault.app.crypto.VaultException
import com.noxvault.app.crypto.WrongPasswordException
import com.noxvault.app.domain.model.Folder
import com.noxvault.app.domain.model.ItemCategory
import com.noxvault.app.domain.model.Templates
import com.noxvault.app.domain.model.VaultData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Сквозные сценарии контейнера (раздел 13 ТЗ, п. 1–4, 7–10, 12):
 * создание, правильная/неправильная разблокировка, восстановление,
 * повреждение данных, резервная копия, отсутствие открытых секретов в файле.
 */
class VaultContainerTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun sampleData(): VaultData {
        val item = Templates.newItem(ItemCategory.API_KEY).copy(
            title = "Продакшн API",
            tags = listOf("работа"),
            fields = Templates.fieldsFor(ItemCategory.API_KEY)
                .mapIndexed { i, f -> if (i == 0) f.copy(value = "sk-live-SECRET-98765") else f }
        )
        return VaultData(items = listOf(item), folders = listOf(Folder(name = "Работа")))
    }

    private fun samplePlaintext(): ByteArray =
        json.encodeToString(sampleData()).toByteArray(Charsets.UTF_8)

    @Test
    fun createAndUnlock_withCorrectPassword() {
        val plaintext = samplePlaintext()
        val created = VaultContainerCodec.createNew("Master-Password-123".toCharArray(), plaintext)
        val unlocked = VaultContainerCodec.unlockWithPassword(created.bytes, "Master-Password-123".toCharArray())
        assertArrayEquals(plaintext, unlocked.plaintext)
        val restored = json.decodeFromString<VaultData>(String(unlocked.plaintext, Charsets.UTF_8))
        assertEquals("Продакшн API", restored.items.single().title)
        assertEquals("sk-live-SECRET-98765", restored.items.single().fields[0].value)
    }

    @Test
    fun container_containsNoPlaintextSecretsOrTitles() {
        val created = VaultContainerCodec.createNew("Pw-12345678".toCharArray(), samplePlaintext())
        val asText = String(created.bytes, Charsets.ISO_8859_1)
        assertFalse(asText.contains("SECRET-98765"))
        assertFalse(asText.contains("Продакшн"))
        assertFalse(asText.contains("работа"))
        assertTrue(VaultContainerCodec.isProbablyContainer(created.bytes))
    }

    @Test(expected = WrongPasswordException::class)
    fun unlock_withWrongPassword_fails() {
        val created = VaultContainerCodec.createNew("Right-Password-1".toCharArray(), samplePlaintext())
        VaultContainerCodec.unlockWithPassword(created.bytes, "Wrong-Password-1".toCharArray())
    }

    @Test
    fun unlock_withRecoveryKey_works() {
        val plaintext = samplePlaintext()
        val created = VaultContainerCodec.createNew("Forgotten-Password".toCharArray(), plaintext)
        val parsed = RecoveryKey.parse(RecoveryKey.formatForDisplay(created.recoveryKey))
        val unlocked = VaultContainerCodec.unlockWithRecoveryKey(created.bytes, parsed!!)
        assertArrayEquals(plaintext, unlocked.plaintext)
        assertArrayEquals(created.dataKey, unlocked.dataKey)
    }

    @Test
    fun unlock_withWrongRecoveryKey_fails() {
        val created = VaultContainerCodec.createNew("Pw-12345678".toCharArray(), samplePlaintext())
        try {
            VaultContainerCodec.unlockWithRecoveryKey(created.bytes, RecoveryKey.generate())
            fail("Чужой ключ восстановления должен отклоняться")
        } catch (expected: WrongPasswordException) {
            // ok
        }
    }

    @Test
    fun tamperedPayload_isDetected() {
        val created = VaultContainerCodec.createNew("Pw-12345678".toCharArray(), samplePlaintext())
        val tampered = created.bytes.copyOf()
        tampered[tampered.lastIndex - 3] = (tampered[tampered.lastIndex - 3] + 1).toByte()
        try {
            VaultContainerCodec.unlockWithPassword(tampered, "Pw-12345678".toCharArray())
            fail("Изменённый шифртекст должен отклоняться тегом GCM")
        } catch (expected: VaultException) {
            assertTrue(expected is CorruptedVaultException || expected is WrongPasswordException)
        }
    }

    @Test
    fun junkFile_isRejectedCleanly() {
        try {
            VaultContainerCodec.unlockWithPassword("not a vault at all".toByteArray(), "x".toCharArray())
            fail("Мусорный файл должен отклоняться")
        } catch (expected: CorruptedVaultException) {
            // ok
        }
        try {
            VaultContainerCodec.unlockWithPassword(ByteArray(0), "x".toCharArray())
            fail("Пустой файл должен отклоняться")
        } catch (expected: CorruptedVaultException) {
            // ok
        }
    }

    @Test
    fun resave_usesFreshPayloadNonce_andStillUnlocks() {
        val plaintext = samplePlaintext()
        val created = VaultContainerCodec.createNew("Pw-12345678".toCharArray(), plaintext)
        val header1 = VaultContainerCodec.parseHeader(created.bytes)
        val (bytes2, header2) = VaultContainerCodec.assemble(header1, created.dataKey, plaintext)
        assertFalse(bytes2.contentEquals(created.bytes))
        assertFalse(header1.payloadNonceB64 == header2.payloadNonceB64)
        val unlocked = VaultContainerCodec.unlockWithPassword(bytes2, "Pw-12345678".toCharArray())
        assertArrayEquals(plaintext, unlocked.plaintext)
    }

    @Test
    fun backup_unlocksWithBackupPassword_andKeepsRecoverySlot() {
        val plaintext = samplePlaintext()
        val created = VaultContainerCodec.createNew("Master-1111".toCharArray(), plaintext)
        val backup = VaultContainerCodec.createBackup(
            VaultContainerCodec.parseHeader(created.bytes),
            created.dataKey, plaintext, "Backup-2222".toCharArray()
        )
        assertTrue(VaultContainerCodec.parseHeader(backup).isBackup)

        // Восстановление на «чистом устройстве»: только файл + пароль копии.
        val restored = VaultContainerCodec.unlockWithPassword(backup, "Backup-2222".toCharArray())
        assertArrayEquals(plaintext, restored.plaintext)
        assertArrayEquals(created.dataKey, restored.dataKey)

        // Ключ восстановления исходного хранилища продолжает работать.
        val byRecovery = VaultContainerCodec.unlockWithRecoveryKey(backup, created.recoveryKey)
        assertArrayEquals(plaintext, byRecovery.plaintext)

        // Мастер-пароль исходного устройства копию НЕ открывает (у неё свой пароль).
        try {
            VaultContainerCodec.unlockWithPassword(backup, "Master-1111".toCharArray())
            fail("Пароль устройства не должен открывать резервную копию")
        } catch (expected: WrongPasswordException) {
            // ok
        }
    }

    @Test
    fun changedPasswordSlot_oldPasswordStopsWorking() {
        val plaintext = samplePlaintext()
        val created = VaultContainerCodec.createNew("Old-Password-1".toCharArray(), plaintext)
        val newSlot = VaultContainerCodec.createPasswordSlot("New-Password-2".toCharArray(), created.dataKey)
        val newHeader = VaultContainerCodec.parseHeader(created.bytes).copy(passwordSlot = newSlot)
        val (newBytes, _) = VaultContainerCodec.assemble(newHeader, created.dataKey, plaintext)

        assertArrayEquals(
            plaintext,
            VaultContainerCodec.unlockWithPassword(newBytes, "New-Password-2".toCharArray()).plaintext
        )
        try {
            VaultContainerCodec.unlockWithPassword(newBytes, "Old-Password-1".toCharArray())
            fail("Старый пароль должен перестать работать")
        } catch (expected: WrongPasswordException) {
            // ok
        }
    }

    @Test
    fun itemCrudInsideContainer_editAndDeletePersist() {
        // Имитация цикла приложения: создать -> изменить данные -> пересохранить -> открыть заново.
        val created = VaultContainerCodec.createNew("Pw-12345678".toCharArray(), samplePlaintext())
        val opened = VaultContainerCodec.unlockWithPassword(created.bytes, "Pw-12345678".toCharArray())
        var data = json.decodeFromString<VaultData>(String(opened.plaintext, Charsets.UTF_8))
        data = data.copy(items = data.items.map { it.copy(title = "Переименовано", favorite = true) })
        val (savedBytes, _) = VaultContainerCodec.assemble(
            opened.header, opened.dataKey,
            json.encodeToString(data).toByteArray(Charsets.UTF_8)
        )
        val reopened = VaultContainerCodec.unlockWithPassword(savedBytes, "Pw-12345678".toCharArray())
        val reread = json.decodeFromString<VaultData>(String(reopened.plaintext, Charsets.UTF_8))
        assertEquals("Переименовано", reread.items.single().title)
        assertTrue(reread.items.single().favorite)

        data = reread.copy(items = emptyList())
        val (savedBytes2, _) = VaultContainerCodec.assemble(
            reopened.header, reopened.dataKey,
            json.encodeToString(data).toByteArray(Charsets.UTF_8)
        )
        val reread2 = json.decodeFromString<VaultData>(
            String(VaultContainerCodec.unlockWithPassword(savedBytes2, "Pw-12345678".toCharArray()).plaintext, Charsets.UTF_8)
        )
        assertTrue(reread2.items.isEmpty())
    }
}
