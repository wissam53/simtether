package com.simtether.shared

import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Keystore-backed file encryption for the at-rest stores
 * (pending events, conversation history, call log). AES/GCM with a
 * key that lives in the AndroidKeyStore — the plaintext never hits
 * disk and the key is non-exportable.
 *
 * Wire format: "STENC1\n" || iv(12) || ciphertext+tag.
 * Files without the magic header are read as legacy plaintext —
 * they get rewritten encrypted on the next write, so migration is
 * automatic. Keystore failure falls back to plaintext rather than
 * dropping user data (same policy as SecureStore).
 */
object SecureFile {
    private const val KEY_ALIAS = "simtether_files_v1"
    private val MAGIC = "STENC1\n".toByteArray()
    private const val TAG = "SimTether.SecureFile"

    private val key: SecretKey? by lazy {
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

    /** Encrypt + overwrite [file] with [text]. Plaintext fallback if
     *  the Keystore is unavailable. */
    fun write(file: File, text: String) {
        val k = key
        if (k == null) {
            Log.w(TAG, "writing ${file.name} unencrypted (keystore unavailable)")
            file.writeText(text)
            return
        }
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            file.writeBytes(MAGIC + c.iv + c.doFinal(text.toByteArray()))
        }.onFailure { Log.e(TAG, "encrypt ${file.name} failed", it) }
    }

    /** Read [file], decrypting if it carries the magic header. Returns
     *  null when missing or undecryptable. */
    fun read(file: File): String? {
        if (!file.exists()) return null
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (!raw.copyOfRange(0, minOf(MAGIC.size, raw.size)).contentEquals(MAGIC)) {
            return raw.decodeToString()   // legacy plaintext
        }
        val k = key ?: run {
            Log.w(TAG, "${file.name} encrypted but keystore unavailable")
            return null
        }
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE, k,
                GCMParameterSpec(128, raw.copyOfRange(MAGIC.size, MAGIC.size + 12)),
            )
            c.doFinal(raw.copyOfRange(MAGIC.size + 12, raw.size)).decodeToString()
        }.onFailure { Log.e(TAG, "decrypt ${file.name} failed", it) }.getOrNull()
    }
}
