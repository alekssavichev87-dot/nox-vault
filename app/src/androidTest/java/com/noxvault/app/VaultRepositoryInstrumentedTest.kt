package com.noxvault.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noxvault.app.crypto.WrongPasswordException
import com.noxvault.app.data.VaultRepository
import com.noxvault.app.data.VaultStorage
import com.noxvault.app.data.importer.CsvPasswordImporter
import com.noxvault.app.domain.model.ItemCategory
import com.noxvault.app.domain.model.Templates
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Инструментальные тесты (нужно устройство/эмулятор): полный цикл через файлы —
 * создание, сохранение после «перезапуска» (новый Repository), отказ при неверном
 * пароле, экспорт и импорт .nox на «чистую установку». Сценарии п. 1–11 раздела 13 ТЗ.
 */
@RunWith(AndroidJUnit4::class)
class VaultRepositoryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = VaultStorage(context)

    @Before
    fun cleanBefore() {
        storage.deleteAll()
    }

    @After
    fun cleanAfter() {
        storage.deleteAll()
    }

    @Test
    fun fullCycle_createPersistUnlock() = runBlocking {
        val repo = VaultRepository(storage)
        val recovery = repo.createVault("Device-Password-1".toCharArray())
        assertTrue(recovery.length >= 64)
        assertTrue(storage.exists())

        val item = Templates.newItem(ItemCategory.PASSWORD).copy(title = "Почта")
        repo.upsertItem(item)
        repo.addFolder("Личное")
        repo.lock()
        assertFalse(repo.isUnlocked)

        // «Перезапуск приложения»: новый репозиторий читает с диска.
        val repo2 = VaultRepository(storage)
        try {
            repo2.unlock("Device-Password-2".toCharArray())
            fail("Неверный пароль не должен открывать хранилище")
        } catch (expected: WrongPasswordException) {
            // ok
        }
        repo2.unlock("Device-Password-1".toCharArray())
        assertEquals("Почта", repo2.data?.items?.single()?.title)
        assertEquals("Личное", repo2.data?.folders?.single()?.name)

        // Файл на диске не содержит открытых данных.
        val raw = storage.readMain()!!
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("Почта"))
        Unit
    }

    @Test
    fun corruptedMainFile_fallsBackToPreviousGood() = runBlocking {
        val repo = VaultRepository(storage)
        repo.createVault("Backup-Test-Pw-1".toCharArray())
        repo.upsertItem(Templates.newItem(ItemCategory.NOTE).copy(title = "Важная заметка"))
        // Второе сохранение создаст .bak с предыдущей хорошей версией.
        repo.upsertItem(Templates.newItem(ItemCategory.NOTE).copy(title = "Вторая"))
        repo.lock()

        // Портим основной файл (имитация прерванной записи).
        val bytes = storage.readMain()!!
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex] + 1).toByte()
        storage.mainFile.writeBytes(bytes)

        val repo2 = VaultRepository(storage)
        repo2.unlock("Backup-Test-Pw-1".toCharArray())  // должен подхватить .bak
        assertTrue(repo2.data!!.items.any { it.title == "Важная заметка" })
        Unit
    }

    @Test
    fun exportImport_onCleanInstall() = runBlocking {
        val repo = VaultRepository(storage)
        repo.createVault("Master-A-12345".toCharArray())
        repo.upsertItem(Templates.newItem(ItemCategory.WIFI).copy(title = "Домашний Wi-Fi"))

        val out = ByteArrayOutputStream()
        repo.exportBackup(out, "Backup-B-67890".toCharArray())
        val backupBytes = out.toByteArray()
        assertTrue(backupBytes.isNotEmpty())
        assertFalse(String(backupBytes, Charsets.ISO_8859_1).contains("Wi-Fi"))

        repo.lock()

        // «Другое устройство»: удаляем всё локальное и импортируем копию.
        storage.deleteAll()
        val repoOther = VaultRepository(storage)
        repoOther.importBackup(
            ByteArrayInputStream(backupBytes),
            "Backup-B-67890".toCharArray(),
            "NewDevice-Pw-1".toCharArray()
        )
        assertEquals("Домашний Wi-Fi", repoOther.data?.items?.single()?.title)

        repoOther.lock()
        val repoCheck = VaultRepository(storage)
        repoCheck.unlock("NewDevice-Pw-1".toCharArray())
        assertEquals("Домашний Wi-Fi", repoCheck.data?.items?.single()?.title)
        Unit
    }

    @Test
    fun csvImport_createsSeparateItemsInOneSave() = runBlocking {
        val repo = VaultRepository(storage)
        repo.createVault("Csv-Import-Pw-1".toCharArray())

        val csv = "name,url,username,password,note\n" +
            "GitHub,https://github.com,octocat,GhSecret1,\n" +
            "Mail,https://mail.example.com,user@example.com,MailSecret2,личная\n" +
            "Bank,https://bank.example.com,ivan,BankSecret3,"
        val parsed = CsvPasswordImporter.parse(csv)
        val candidates = CsvPasswordImporter.mapRows(parsed, CsvPasswordImporter.detectMapping(parsed))
        assertEquals(3, candidates.size)
        repo.addItems(candidates.map {
            CsvPasswordImporter.candidateToItem(it, folderId = null, tags = listOf("импорт-браузер"))
        })
        repo.lock()

        val repo2 = VaultRepository(storage)
        repo2.unlock("Csv-Import-Pw-1".toCharArray())
        val titles = repo2.data!!.items.map { it.title }.sorted()
        assertEquals(listOf("Bank", "GitHub", "Mail"), titles)
        val mail = repo2.data!!.items.first { it.title == "Mail" }
        assertEquals("MailSecret2", mail.fields.first { it.label == "Пароль" }.value)
        assertEquals(listOf("импорт-браузер"), mail.tags)
        // Пароли из CSV не лежат в файле хранилища открыто.
        val raw = String(storage.readMain()!!, Charsets.ISO_8859_1)
        assertFalse(raw.contains("GhSecret1"))
        assertFalse(raw.contains("BankSecret3"))
        Unit
    }

    @Test
    fun wipe_removesAllFiles() = runBlocking {
        val repo = VaultRepository(storage)
        repo.createVault("Wipe-Me-12345".toCharArray())
        assertTrue(storage.exists())
        repo.wipeEverything()
        assertFalse(storage.exists())
        assertFalse(repo.isUnlocked)
        Unit
    }
}
