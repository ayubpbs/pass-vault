package com.example.myapplication

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key design:
 *  - A random 256-bit data key (DEK) encrypts the vault.
 *  - The DEK is stored wrapped by a key derived from the app password (PBKDF2, 600k rounds).
 *    Without the app password the data cannot be opened, even on a rooted phone.
 *  - Optionally the DEK is also wrapped by an Android Keystore key that needs a fingerprint
 *    for every use (so fingerprint unlock is cryptographically real, not just a screen lock).
 */
object Crypto {
    private const val LEGACY_ALIAS = "passvault_key"
    private const val BIO_ALIAS = "passvault_bio_key"

    const val KDF_ITER = 600_000
    const val LEGACY_HASH_ITER = 120_000
    private const val LEGACY_BACKUP_ITER = 200_000

    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    // Backup file v2: magic(4) + salt(16) + iv(12) + ciphertext
    private val BACKUP_MAGIC = byteArrayOf(0x50, 0x56, 0x42, 0x02) // "PVB" + 2

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // ---------------- Password based keys ----------------

    fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun newDataKey(): SecretKey = SecretKeySpec(randomBytes(32), "AES")

    // ---------------- AES-GCM with a software key. Output: iv(12) + ciphertext ----------------

    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = randomBytes(IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + c.doFinal(plain)
    }

    fun open(key: SecretKey, data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_LEN)
        val ct = data.copyOfRange(IV_LEN, data.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return c.doFinal(ct)
    }

    fun encryptString(key: SecretKey, s: String): String =
        b64(seal(key, s.toByteArray(Charsets.UTF_8)))

    fun decryptString(key: SecretKey, s: String): String =
        String(open(key, unb64(s)), Charsets.UTF_8)

    // ---------------- Fingerprint-bound Keystore key ----------------

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun bioKey(create: Boolean): SecretKey {
        val ks = keyStore()
        (ks.getKey(BIO_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "no biometric key" }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                BIO_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return kg.generateKey()
    }

    /** Cipher to wrap the data key. Must be passed to BiometricPrompt as a CryptoObject. */
    fun bioEncryptCipher(): Cipher {
        deleteBioKey() // always start with a fresh key
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, bioKey(true))
        return c
    }

    /** Cipher to unwrap the data key. Throws if the key was invalidated (fingerprints changed). */
    fun bioDecryptCipher(iv: ByteArray): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, bioKey(false), GCMParameterSpec(GCM_TAG_BITS, iv))
        return c
    }

    fun deleteBioKey() {
        try {
            val ks = keyStore()
            if (ks.containsAlias(BIO_ALIAS)) ks.deleteEntry(BIO_ALIAS)
        } catch (_: Exception) {
        }
    }

    // ---------------- Legacy (old versions) - only used once to migrate ----------------

    fun legacyDecrypt(data: String): String {
        val key = keyStore().getKey(LEGACY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("old key missing")
        val all = unb64(data)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(GCM_TAG_BITS, all.copyOfRange(0, IV_LEN))
        )
        return String(c.doFinal(all.copyOfRange(IV_LEN, all.size)), Charsets.UTF_8)
    }

    fun legacyHash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, LEGACY_HASH_ITER, 256)
        try {
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return b64(f.generateSecret(spec).encoded)
        } finally {
            spec.clearPassword()
        }
    }

    fun deleteLegacyKey() {
        try {
            val ks = keyStore()
            if (ks.containsAlias(LEGACY_ALIAS)) ks.deleteEntry(LEGACY_ALIAS)
        } catch (_: Exception) {
        }
    }

    // ---------------- Backup files (password based, works on any phone) ----------------

    fun encryptWithPassword(plain: String, password: String): ByteArray {
        val salt = randomBytes(16)
        val body = seal(deriveKey(password, salt, KDF_ITER), plain.toByteArray(Charsets.UTF_8))
        return BACKUP_MAGIC + salt + body
    }

    fun decryptWithPassword(data: ByteArray, password: String): String {
        // New format (600k rounds)
        if (data.size > 4 + 16 + IV_LEN &&
            data.copyOfRange(0, 4).contentEquals(BACKUP_MAGIC)
        ) {
            try {
                val key = deriveKey(password, data.copyOfRange(4, 20), KDF_ITER)
                return String(open(key, data.copyOfRange(20, data.size)), Charsets.UTF_8)
            } catch (_: Exception) {
                // fall through: could (very rarely) be an old file that starts with the same bytes
            }
        }
        // Old format (salt(16) + iv(12) + ciphertext, 200k rounds) - still restorable
        val key = deriveKey(password, data.copyOfRange(0, 16), LEGACY_BACKUP_ITER)
        return String(open(key, data.copyOfRange(16, data.size)), Charsets.UTF_8)
    }
}
