package org.schabi.newpipe.aether

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest protection for the Aether identity (Phase 7, task 45 — optional
 * hardening).
 *
 * Aether's engine writes its identity TOML to the path given to
 * `aether_identity_open` (app-private `filesDir/aether/aether.toml`, 0600).
 * This helper additionally encrypts that file with an AES-GCM key held in the
 * Android Keystore: the engine writes/reads the plaintext path while running,
 * and [encryptAtRest] / [decryptForEngine] move it between plaintext and an
 * encrypted `aether.toml.enc` blob. The key never leaves the Keystore.
 *
 * If the Keystore is unavailable (rare), the store degrades gracefully: it
 * reports [isSecure] = false and leaves the file unencrypted rather than
 * breaking the engine.
 */
class AetherIdentityStore(private val context: Context) {

    private val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    private val keyAlias = "${context.packageName}.aether.identity"

    private val plainFile: File get() = File(context.filesDir, "aether/aether.toml")
    private val encFile: File get() = File(context.filesDir, "aether/aether.toml.enc")

    /** True when the Android Keystore is usable on this device. */
    val isSecure: Boolean
        get() = runCatching {
            getOrCreateKey()
            true
        }.getOrDefault(false)

    /**
     * Move the plaintext identity written by the engine to the encrypted
     * blob. Call on app shutdown / background when the engine is stopped.
     */
    fun encryptAtRest() {
        if (!plainFile.exists() || !isSecure) return
        val plain = plainFile.readBytes()
        if (plain.isEmpty()) return
        val cipherText = encrypt(plain)
        encFile.writeBytes(cipherText)
        plainFile.delete()
    }

    /**
     * Restore the plaintext identity for the engine from the encrypted blob.
     * Call before `aether_identity_open`. Returns the plaintext path.
     */
    fun decryptForEngine(): File {
        if (encFile.exists() && isSecure) {
            val plain = decrypt(encFile.readBytes())
            plainFile.writeBytes(plain)
            encFile.delete()
        }
        return plainFile
    }

    private fun getOrCreateKey(): SecretKey {
        keystore.getKey(keyAlias, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        val blob = iv + cipherText
        return Base64.encodeToString(blob, Base64.NO_WRAP).toByteArray(Charsets.US_ASCII)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val decoded = Base64.decode(String(blob, Charsets.US_ASCII), Base64.NO_WRAP)
        val iv = decoded.copyOfRange(0, IV_LENGTH)
        val cipherText = decoded.copyOfRange(IV_LENGTH, decoded.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }
}
