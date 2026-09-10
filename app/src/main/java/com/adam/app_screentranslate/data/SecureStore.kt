package com.adam.app_screentranslate.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The xAI token at rest. AES/GCM with the key held by AndroidKeystore, ciphertext in ordinary
 * preferences: a copied settings file is undecryptable anywhere but this device, and the key
 * material never enters the process. The app is already excluded from backup, so nothing leaves.
 *
 * The token is decrypted on demand and lives only as long as the call that asked for it. It is
 * never logged — see invariant 1.
 */
class SecureStore(private val prefs: SharedPreferences) {
    private val keyStore: KeyStore? = runCatching { KeyStore.getInstance(STORE).apply { load(null) } }.getOrNull()

    /** [name] separates one provider's key from another's; both share the one Keystore key. */
    fun has(name: String): Boolean = prefs.contains(key(name))

    fun token(name: String): String {
        val stored = prefs.getString(key(name), null) ?: return ""
        return runCatching { decrypt(stored) }.getOrElse {
            // A key invalidated by a lock-screen change leaves ciphertext that will never open again.
            prefs.edit().remove(key(name)).apply()
            ""
        }
    }

    fun save(name: String, token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) { clear(name); return true }
        return runCatching { prefs.edit().putString(key(name), encrypt(trimmed)).apply() }.isSuccess
    }

    fun clear(name: String) = prefs.edit().remove(key(name)).apply()

    private fun key(name: String) = "$VALUE.$name"

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(),
            GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = keyStore ?: error("Keystore unavailable")
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "lenslate_xai_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VALUE = "token"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
