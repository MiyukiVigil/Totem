package tech.miyukivigil.totem.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that lives in the Android Keystore (StrongBox secure chip when the phone
 * has one) and can never be exported.
 *
 * The key deliberately does **not** require the user to have unlocked recently: the phone must be
 * able to answer a paired PC while it sits locked in a pocket. The UI is protected by the app
 * lock instead. With [requireUnlockSecs] set, the key only works for that long after the user
 * unlocks (screen lock or Totem's prompt); [Vault] uses such a key for sealed phone-only accounts.
 */
/** What [Vault] needs from a key; [VaultCrypto] in the app, a fake in tests. */
interface SecretBox {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
    fun hasKey(): Boolean
    fun createKeyNow()
    fun deleteKey()
}

class VaultCrypto(private val alias: String = "totem_vault_v1", private val requireUnlockSecs: Int? = null) : SecretBox {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey =
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            // An unlock-bound key that vanished must not be silently replaced.
            ?: if (requireUnlockSecs == null) createKey() else throw IllegalStateException("the key for sealed accounts is missing")

    /** Creates the key up front (unlock-bound keys are never created implicitly). */
    override fun createKeyNow() {
        if (!hasKey()) createKey()
    }

    private fun createKey(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .apply {
                requireUnlockSecs?.let { secs ->
                    setUserAuthenticationRequired(true)
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        setUserAuthenticationParameters(secs, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(secs)
                    }
                    // Adding a fingerprint later must not destroy the key (and the accounts with it).
                    setInvalidatedByBiometricEnrollment(false)
                }
            }
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey().also { Log.i(TAG, "vault key created in StrongBox") }
        } catch (e: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey().also { Log.i(TAG, "vault key created in TEE (no StrongBox)") }
        }
    }

    /** Returns `iv (12) | ciphertext+tag`. */
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return cipher.iv + ct
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "vault file is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    override fun hasKey(): Boolean = keyStore.containsAlias(alias)

    override fun deleteKey() = keyStore.deleteEntry(alias)

    private companion object {
        const val TAG = "TotemVault"
        const val IV_LEN = 12
    }
}
