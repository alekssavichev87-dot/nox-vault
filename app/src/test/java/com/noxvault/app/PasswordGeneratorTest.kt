package com.noxvault.app

import com.noxvault.app.crypto.PasswordGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {

    @Test
    fun generate_respectsLengthAndClasses() {
        val password = PasswordGenerator.generate(PasswordGenerator.Options(length = 32))
        assertEquals(32, password.length)
        assertTrue(password.any { it.isLowerCase() })
        assertTrue(password.any { it.isUpperCase() })
        assertTrue(password.any { it.isDigit() })
        assertTrue(password.any { !it.isLetterOrDigit() })
    }

    @Test
    fun generate_digitsOnly() {
        val pin = PasswordGenerator.generate(
            PasswordGenerator.Options(length = 12, uppercase = false, lowercase = false, digits = true, symbols = false)
        )
        assertTrue(pin.all { it.isDigit() })
    }

    @Test
    fun generate_excludesAmbiguous() {
        repeat(20) {
            val password = PasswordGenerator.generate(PasswordGenerator.Options(length = 48, excludeAmbiguous = true))
            assertFalse(password.any { it in "Il1O0" })
        }
    }

    @Test
    fun generate_isRandom() {
        val values = (1..50).map { PasswordGenerator.generate(PasswordGenerator.Options()) }.toSet()
        assertEquals(50, values.size)  // коллизии на SecureRandom при длине 20 невозможны
    }

    @Test
    fun strength_scoresMakeSense() {
        assertTrue(PasswordGenerator.strengthScore("") == 0)
        assertTrue(PasswordGenerator.strengthScore("abc") <= 1)
        assertTrue(PasswordGenerator.strengthScore("Xk9#mQ2\$vL8&pR4!zW7") >= 3)
    }
}
