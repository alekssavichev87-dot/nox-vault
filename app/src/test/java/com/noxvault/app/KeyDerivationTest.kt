package com.noxvault.app

import com.noxvault.app.crypto.CryptoManager
import com.noxvault.app.crypto.KeyDerivation
import com.noxvault.app.crypto.RecoveryKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyDerivationTest {

    // Быстрые параметры для тестов (в приложении: 64 МиБ, t=3).
    private val fastParams = KeyDerivation.KdfParams(memoryKb = 4_096, iterations = 1, parallelism = 1)

    @Test
    fun argon2id_isDeterministicForSameInputs() {
        val salt = CryptoManager.generateSalt()
        val a = KeyDerivation.deriveKey("password-123".toCharArray(), salt, fastParams)
        val b = KeyDerivation.deriveKey("password-123".toCharArray(), salt, fastParams)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun argon2id_differsForDifferentPasswordOrSalt() {
        val salt = CryptoManager.generateSalt()
        val otherSalt = CryptoManager.generateSalt()
        val base = KeyDerivation.deriveKey("password-123".toCharArray(), salt, fastParams)
        val otherPassword = KeyDerivation.deriveKey("password-124".toCharArray(), salt, fastParams)
        val otherSaltKey = KeyDerivation.deriveKey("password-123".toCharArray(), otherSalt, fastParams)
        assertFalse(base.contentEquals(otherPassword))
        assertFalse(base.contentEquals(otherSaltKey))
    }

    @Test
    fun pbkdf2_fallback_works() {
        val salt = CryptoManager.generateSalt()
        val params = KeyDerivation.KdfParams(
            type = KeyDerivation.KDF_PBKDF2_SHA256, iterations = 1_000
        )
        val a = KeyDerivation.deriveKey("pw".toCharArray(), salt, params)
        val b = KeyDerivation.deriveKey("pw".toCharArray(), salt, params)
        assertArrayEquals(a, b)
    }

    @Test
    fun recoveryKey_formatParseRoundtrip() {
        val key = RecoveryKey.generate()
        val formatted = RecoveryKey.formatForDisplay(key)
        assertEquals(16, formatted.split("-").size)
        assertArrayEquals(key, RecoveryKey.parse(formatted))
        // Пользователь может вводить без дефисов, с пробелами и в нижнем регистре.
        assertArrayEquals(key, RecoveryKey.parse(formatted.lowercase().replace("-", " ")))
        assertNotNull(RecoveryKey.parse(formatted.replace("-", "")))
    }

    @Test
    fun recoveryKey_parseRejectsInvalid() {
        assertNull(RecoveryKey.parse(""))
        assertNull(RecoveryKey.parse("ABCD"))
        assertNull(RecoveryKey.parse("Z".repeat(64)))  // не HEX
        assertNull(RecoveryKey.parse("A".repeat(63)))  // на символ меньше
    }
}
