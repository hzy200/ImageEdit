package com.example.imageedit.core.intent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.imageedit.BuildConfig
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API key storage backed by Android Keystore (AES/GCM).
 *
 * Precedence: an explicit local key, then the build-time key from .env. The
 * legacy plaintext preference is migrated and removed on first read. Keystore
 * operations are synchronous; callers must use Dispatchers.IO.
 */
object ApiKeyStore {
    private const val PREFS = "imageedit_settings"
    private const val KEY_LEGACY = "deepseek_api_key"
    private const val KEY_CIPHER = "deepseek_api_key_enc"
    private const val KEY_DISABLE_REMOTE = "disable_remote"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "imageedit_deepseek_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val TAG = "ApiKeyStore"

    @Synchronized
    fun get(context: Context): String? {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_DISABLE_REMOTE, false)) return null

        val encrypted = prefs.getString(KEY_CIPHER, null)
        if (!encrypted.isNullOrBlank()) {
            val decrypted = runCatching { decrypt(encrypted) }
                .onFailure { Log.w(TAG, "failed to decrypt stored key", it) }
                .getOrNull()
            if (decrypted != null) return decrypted
            // Ciphertext can become unrecoverable when the Keystore key is
            // invalidated (e.g. lock-screen change). Drop it and fall through.
            prefs.edit().remove(KEY_CIPHER).apply()
        }

        val legacy = prefs.getString(KEY_LEGACY, null)?.takeIf { it.isNotBlank() }
        if (legacy != null) {
            runCatching { set(context, legacy) }
                .onFailure { Log.w(TAG, "failed to migrate plaintext key", it) }
            return legacy
        }
        return BuildConfig.DEEPSEEK_API_KEY.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun set(context: Context, value: String) {
        val encrypted = encrypt(value.trim())
        prefs(context).edit()
            .putString(KEY_CIPHER, encrypted)
            .remove(KEY_LEGACY)
            .putBoolean(KEY_DISABLE_REMOTE, false)
            .apply()
    }

    fun isRemoteDisabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISABLE_REMOTE, false)

    /** Clear the local key and explicitly disable the build-time key. */
    @Synchronized
    fun disableRemote(context: Context) {
        prefs(context).edit()
            .remove(KEY_CIPHER)
            .remove(KEY_LEGACY)
            .putBoolean(KEY_DISABLE_REMOTE, true)
            .apply()
    }

    fun clear(context: Context) = disableRemote(context)

    /** Re-enable the build-time .env key (used by settings/tests). */
    @Synchronized
    fun enableBuildKey(context: Context) {
        prefs(context).edit()
            .remove(KEY_CIPHER)
            .remove(KEY_LEGACY)
            .putBoolean(KEY_DISABLE_REMOTE, false)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val packed = ByteArray(1 + iv.size + cipherText.size)
        packed[0] = iv.size.toByte()
        iv.copyInto(packed, destinationOffset = 1)
        cipherText.copyInto(packed, destinationOffset = 1 + iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(packedValue: String): String {
        val packed = Base64.decode(packedValue, Base64.NO_WRAP)
        require(packed.size > 1) { "invalid encrypted key" }
        val ivSize = packed[0].toInt() and 0xFF
        require(ivSize in 12..32 && packed.size > ivSize + 1) { "invalid encrypted key" }
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val cipherText = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
