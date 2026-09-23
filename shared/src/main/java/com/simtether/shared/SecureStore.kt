package com.simtether.shared

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed encrypted string store. Values are AES/GCM encrypted
 * with a key that lives in the AndroidKeyStore (hardware-backed on
 * capable devices) — the plaintext never touches the prefs file, and
 * even root can't export the key. This guards the pairing token and
 * the bridge's static private key, both of which used to sit in plain
 * SharedPreferences.
 *
 * Reads transparently migrate legacy plaintext entries: a value that
 * fails decryption is treated as plaintext, re-encrypted, and the
 * plain copy removed.
 */
object SecureStore {
    private const val KEY_ALIAS = "simtether_store_v1"
    private const val ENC_PREFIX = "enc:"
    private const val TAG = "SimTether.SecureStore"

    private val key: javax.crypto.SecretKey? by lazy {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                ).run {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                    generateKey()
                }
        }.onFailure { Log.e(TAG, "keystore unavailable", it) }.getOrNull()
    }

    private fun encrypt(plain: String): String? {
        val k = key ?: return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            // iv(12) || ciphertext+tag
            Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun decrypt(blob: String): String? {
        val k = key ?: return null
        return runCatching {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw.copyOf(12)))
            c.doFinal(raw.copyOfRange(12, raw.size)).decodeToString()
        }.getOrNull()
    }

    /**
     * False when the AndroidKeyStore key couldn't be created/loaded —
     * writes fall back to plaintext prefs. The bridge's identity key
     * and pairing token then sit unprotected; the UI surfaces this as
     * a warning banner rather than failing silently.
     */
    fun encryptionReady(): Boolean = key != null

    /** Reads [key] from [store]; migrates a legacy plaintext value. */
    fun getString(context: Context, store: String, key: String): String? {
        val sp = context.getSharedPreferences(store, Context.MODE_PRIVATE)
        // Read untyped — legacy entries may be Int/Long/Boolean, and
        // getString() throws ClassCastException on a type mismatch.
        val raw = runCatching { sp.all[key] }.getOrNull() ?: return null
        val v = raw.toString()
        if (v.startsWith(ENC_PREFIX)) {
            return v.removePrefix(ENC_PREFIX).let { enc ->
                decrypt(enc) ?: run {
                    Log.w(TAG, "undecryptable $key in $store — dropping")
                    null
                }
            }
        }
        // Legacy plaintext — re-encrypt in place.
        putString(context, store, key, v)
        return v
    }

    /**
     * Writes [key]; pass [failClosed] for crown-jewel values (identity
     * key, pairing token) where an unencrypted write is worse than no
     * write — a keystore failure then leaves nothing persisted rather
     * than a plaintext private key. Returns false when the write was
     * refused.
     */
    fun putString(context: Context, store: String, key: String,
                  value: String?, failClosed: Boolean = false): Boolean {
        val sp = context.getSharedPreferences(store, Context.MODE_PRIVATE)
        if (value == null) {
            sp.edit().remove(key).apply()
            return true
        }
        val enc = encrypt(value)
        if (enc != null) {
            sp.edit().putString(key, ENC_PREFIX + enc).apply()
            return true
        }
        if (failClosed) {
            Log.e(TAG, "refusing plaintext write of $key (keystore unavailable)")
            return false
        }
        // Keystore broken — plaintext fallback beats losing pairing.
        Log.w(TAG, "storing $key unencrypted (keystore unavailable)")
        sp.edit().putString(key, value).apply()
        return true
    }

    fun getInt(context: Context, store: String, key: String, def: Int): Int =
        getString(context, store, key)?.toIntOrNull() ?: def

    fun putInt(context: Context, store: String, key: String, value: Int) =
        putString(context, store, key, value.toString())
}
