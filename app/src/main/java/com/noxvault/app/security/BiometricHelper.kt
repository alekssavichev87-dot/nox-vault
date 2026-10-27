package com.noxvault.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.noxvault.app.crypto.CryptoAad
import com.noxvault.app.crypto.CryptoManager
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Биометрическая разблокировка как ДОПОЛНИТЕЛЬНЫЙ слот доступа к DataKey.
 *
 * Схема: в Android Keystore создаётся AES-256 ключ, требующий биометрической
 * аутентификации при КАЖДОМ использовании (CryptoObject). Этим ключом
 * шифруется копия DataKey; blob (IV + шифртекст) лежит в biometric.key.
 * Сам Keystore-ключ неэкспортируем и привязан к устройству — поэтому
 * биометрия никогда не является единственным способом доступа:
 * мастер-пароль и резервная копия .nox работают независимо от Keystore.
 *
 * Смена отпечатков/лица инвалидирует ключ (setInvalidatedByBiometricEnrollment) —
 * приложение в этом случае отключает биометрию и просит мастер-пароль.
 */
class BiometricHelper(private val appContext: Context) {

    sealed class Availability {
        object Available : Availability()
        object NoHardware : Availability()
        object NotEnrolled : Availability()
        object Unavailable : Availability()
    }

    fun availability(): Availability {
        val manager = BiometricManager.from(appContext)
        return when (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> Availability.NoHardware
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NotEnrolled
            else -> Availability.Unavailable
        }
    }

    fun isAvailable(): Boolean = availability() == Availability.Available

    // ---------- Keystore ----------
    // ВНИМАНИЕ: java.security.KeyStore нуждается в инициализации,
    // поэтому экземпляр получаем через фабрику каждый раз

    private fun secretKey(createIfMissing: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null) // KeyStore обязательно загружается перед использованием
        }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!createIfMissing) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(CryptoManager.KEY_SIZE_BYTES * 8)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /**
     * Cipher для ШИФРОВАНИЯ DataKey при включении биометрии.
     * @throws KeyPermanentlyInvalidatedException если биометрия изменилась
     */
    fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(createIfMissing = true))
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Биометрия на устройстве изменилась: старый ключ мёртв.
            // Удаляем его и создаём свежий под текущие биометрические данные.
            runCatching {
                KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
                    load(null) // KeyStore обязательно загружается перед использованием
                    deleteEntry(KEY_ALIAS)
                }
            }
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(createIfMissing = true))
        }
        return cipher
    }

    /**
     * Cipher для РАСШИФРОВКИ DataKey при разблокировке. Null, если blob отсутствует.
     * @throws KeyPermanentlyInvalidatedException если ключ инвалидирован
     */
    fun decryptCipher(biometricFile: File): Cipher? {
        val blob = readBlob(biometricFile) ?: return null
        val key = secretKey(createIfMissing = false) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob.iv))
        return cipher
    }

    /** Зашифровать DataKey АВТОРИЗОВАННЫМ cipher (после успешной биометрии) и сохранить blob. */
    fun storeDataKey(authorizedCipher: Cipher, dataKey: ByteArray, biometricFile: File) {
        authorizedCipher.updateAAD(CryptoAad.BIOMETRIC_SLOT)
        val ciphertext = authorizedCipher.doFinal(dataKey)
        val iv = authorizedCipher.iv
        biometricFile.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(ciphertext)
        }
    }

    /** Расшифровать DataKey АВТОРИЗОВАННЫМ cipher (после успешной биометрии). */
    fun extractDataKey(authorizedCipher: Cipher, biometricFile: File): ByteArray? {
        val blob = readBlob(biometricFile) ?: return null
        authorizedCipher.updateAAD(CryptoAad.BIOMETRIC_SLOT)
        val key = authorizedCipher.doFinal(blob.ciphertext)
        return if (key.size == CryptoManager.KEY_SIZE_BYTES) key else null
    }

    fun deleteBlob(biometricFile: File) {
        runCatching { biometricFile.delete() }
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
                load(null) // KeyStore обязательно загружается перед использованием
                deleteEntry(KEY_ALIAS)
            }
        }
    }

    fun hasBlob(biometricFile: File): Boolean = biometricFile.exists() && biometricFile.length() > 20

    private data class Blob(val iv: ByteArray, val ciphertext: ByteArray)

    private fun readBlob(file: File): Blob? {
        if (!file.exists()) return null
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (raw.size < 2) return null
        val ivLen = raw[0].toInt()
        if (ivLen <= 0 || ivLen > 32 || raw.size <= 1 + ivLen) return null
        return Blob(
            iv = raw.copyOfRange(1, 1 + ivLen),
            ciphertext = raw.copyOfRange(1 + ivLen, raw.size)
        )
    }

    // ---------- BiometricPrompt ----------

    /**
     * Показать системный диалог биометрии с CryptoObject.
     * onSuccess получает АВТОРИЗОВАННЫЙ Cipher — им нужно сразу зашифровать/расшифровать.
     * onUsePassword — пользователь нажал «Использовать пароль».
     */
    fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        onSuccess: (Cipher) -> Unit,
        onUsePassword: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authorized = result.cryptoObject?.cipher
                if (authorized != null) onSuccess(authorized)
                else onError("Не удалось получить криптографический объект")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> onUsePassword()
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> onUsePassword()
                    else -> onError(errString.toString())
                }
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Использовать пароль")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "nox_vault_biometric_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        fun isKeyInvalidated(e: Throwable): Boolean =
            e is KeyPermanentlyInvalidatedException || e.cause is KeyPermanentlyInvalidatedException
    }
}
