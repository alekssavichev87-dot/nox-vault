package com.noxvault.app

import com.noxvault.app.crypto.CryptoAad
import com.noxvault.app.crypto.CryptoManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

/** Сценарии раздела 13 ТЗ: шифрование/расшифровка, целостность, уникальность nonce. */
class CryptoManagerTest {

    @Test
    fun aesGcm_roundtrip_restoresPlaintext() {
        val key = CryptoManager.generateDataKey()
        val nonce = CryptoManager.generateNonce()
        val plaintext = "Секретные данные NOX Vault — 12345".toByteArray(Charsets.UTF_8)

        val ciphertext = CryptoManager.aesGcmEncrypt(key, nonce, plaintext, CryptoAad.PAYLOAD)
        val restored = CryptoManager.aesGcmDecrypt(key, nonce, ciphertext, CryptoAad.PAYLOAD)

        assertArrayEquals(plaintext, restored)
        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains("Секретные"))
    }

    @Test(expected = AEADBadTagException::class)
    fun aesGcm_wrongKey_fails() {
        val key = CryptoManager.generateDataKey()
        val otherKey = CryptoManager.generateDataKey()
        val nonce = CryptoManager.generateNonce()
        val ciphertext = CryptoManager.aesGcmEncrypt(key, nonce, byteArrayOf(1, 2, 3), CryptoAad.PAYLOAD)
        CryptoManager.aesGcmDecrypt(otherKey, nonce, ciphertext, CryptoAad.PAYLOAD)
    }

    @Test(expected = AEADBadTagException::class)
    fun aesGcm_tamperedCiphertext_fails() {
        val key = CryptoManager.generateDataKey()
        val nonce = CryptoManager.generateNonce()
        val ciphertext = CryptoManager.aesGcmEncrypt(key, nonce, ByteArray(64) { 7 }, CryptoAad.PAYLOAD)
        ciphertext[10] = (ciphertext[10] + 1).toByte()  // подмена одного байта
        CryptoManager.aesGcmDecrypt(key, nonce, ciphertext, CryptoAad.PAYLOAD)
    }

    @Test(expected = AEADBadTagException::class)
    fun aesGcm_wrongAad_fails() {
        val key = CryptoManager.generateDataKey()
        val nonce = CryptoManager.generateNonce()
        val ciphertext = CryptoManager.aesGcmEncrypt(key, nonce, byteArrayOf(9, 9), CryptoAad.PAYLOAD)
        // Шифртекст одного слота нельзя предъявить в другом контексте.
        CryptoManager.aesGcmDecrypt(key, nonce, ciphertext, CryptoAad.PASSWORD_SLOT)
    }

    @Test
    fun wrapKey_usesFreshNonceEachTime() {
        val kek = CryptoManager.generateDataKey()
        val dataKey = CryptoManager.generateDataKey()
        val first = CryptoManager.wrapKey(kek, dataKey, CryptoAad.PASSWORD_SLOT)
        val second = CryptoManager.wrapKey(kek, dataKey, CryptoAad.PASSWORD_SLOT)
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        assertArrayEquals(dataKey, CryptoManager.unwrapKey(kek, first, CryptoAad.PASSWORD_SLOT))
        assertArrayEquals(dataKey, CryptoManager.unwrapKey(kek, second, CryptoAad.PASSWORD_SLOT))
    }

    @Test
    fun randomBytes_areDistinct() {
        val a = CryptoManager.randomBytes(32)
        val b = CryptoManager.randomBytes(32)
        assertFalse(a.contentEquals(b))
        assertTrue(a.any { it != 0.toByte() })
        try {
            CryptoManager.aesGcmEncrypt(ByteArray(16), CryptoManager.generateNonce(), ByteArray(0), CryptoAad.PAYLOAD)
            fail("16-байтный ключ должен отклоняться")
        } catch (expected: IllegalArgumentException) {
            // ok: только 256-битные ключи
        }
    }
}
