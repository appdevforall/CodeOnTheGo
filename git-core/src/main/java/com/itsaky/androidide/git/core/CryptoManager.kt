package com.itsaky.androidide.git.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Helper class for encrypting and decrypting data using the Android KeyStore.
 * Uses AES-GCM for strong encryption with authentication.
 */
object CryptoManager {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "git_credentials_key"

    private val keyStore: KeyStore? by lazy {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getSecretKey(): SecretKey {
        val ks = keyStore ?: throw IllegalStateException("KeyStore could not be initialized")
        return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateSecretKey()
    }

    private fun generateSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts the [data] and returns a [Pair] containing the IV and the ciphertext.
     */
    fun encrypt(data: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val ciphertext = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return cipher.iv to ciphertext
    }

    /**
     * Decrypts the [ciphertext] using the provided [iv] and returns the original string.
     */
    fun decrypt(iv: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
