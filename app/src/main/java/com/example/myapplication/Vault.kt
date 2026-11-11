package com.example.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

data class Entry(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var userId: String,
    var password: String
)

/** Holds the unlocked data key in memory only. Locking wipes the reference. */
object Session {
    @Volatile
    var dataKey: SecretKey? = null

    val unlocked: Boolean get() = dataKey != null

    fun lock() {
        dataKey = null
    }
}

class Vault(context: Context) {
    enum class State { NONE, LEGACY, READY }
    enum class Result { OK, WRONG, ERROR }

    companion object {
        const val MIN_PASSWORD = 12
        private const val FREE_ATTEMPTS = 5
        private const val BASE_DELAY_MS = 30_000L
        private const val MAX_DELAY_MS = 3_600_000L
    }

    private val sp = context.applicationContext
        .getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun state(): State = when {
        sp.contains("wrap_pw") -> State.READY
        sp.contains("hash") -> State.LEGACY
        else -> State.NONE
    }

    // ---------------- Create / unlock ----------------

    private fun putPasswordWrap(ed: android.content.SharedPreferences.Editor, pw: String, dek: SecretKey) {
        val salt = Crypto.randomBytes(16)
        val kek = Crypto.deriveKey(pw, salt, Crypto.KDF_ITER)
        ed.putString("kdf_salt", Crypto.b64(salt))
        ed.putInt("kdf_iter", Crypto.KDF_ITER)
        ed.putString("wrap_pw", Crypto.b64(Crypto.seal(kek, dek.encoded)))
    }

    /** First-time setup. */
    fun create(pw: String): Result {
        val dek = Crypto.newDataKey()
        val ed = sp.edit()
        putPasswordWrap(ed, pw, dek)
        ed.putString("data", Crypto.encryptString(dek, "[]"))
        if (!ed.commit()) return Result.ERROR
        Session.dataKey = dek
        return Result.OK
    }

    fun unlock(pw: String): Result = try {
        when (state()) {
            State.READY -> unlockReady(pw)
            State.LEGACY -> migrateLegacy(pw)
            State.NONE -> Result.ERROR
        }
    } catch (e: Exception) {
        Result.ERROR
    }

    private fun unlockReady(pw: String): Result {
        val salt = Crypto.unb64(sp.getString("kdf_salt", null) ?: return Result.ERROR)
        val iter = sp.getInt("kdf_iter", Crypto.KDF_ITER)
        val wrapped = Crypto.unb64(sp.getString("wrap_pw", null) ?: return Result.ERROR)
        val dekBytes = try {
            Crypto.open(Crypto.deriveKey(pw, salt, iter), wrapped)
        } catch (e: java.security.GeneralSecurityException) {
            return Result.WRONG
        }
        val dek = SecretKeySpec(dekBytes, "AES")
        // Upgrade older, weaker settings on a correct login
        if (iter < Crypto.KDF_ITER) {
            val ed = sp.edit()
            putPasswordWrap(ed, pw, dek)
            ed.commit()
        }
        Session.dataKey = dek
        return Result.OK
    }

    /** Old version (Keystore-key encryption + stored hash) -> new password-derived design. */
    private fun migrateLegacy(pw: String): Result {
        val salt = Crypto.unb64(sp.getString("salt", null) ?: return Result.ERROR)
        val hash = sp.getString("hash", null) ?: return Result.ERROR
        if (!MessageDigest.isEqual(hash.toByteArray(), Crypto.legacyHash(pw, salt).toByteArray())) {
            return Result.WRONG
        }
        val json = sp.getString("data", null)?.let { Crypto.legacyDecrypt(it) } ?: "[]"
        fromJson(json) // make sure it parses before touching anything

        val dek = Crypto.newDataKey()
        val newData = Crypto.encryptString(dek, json)
        if (Crypto.decryptString(dek, newData) != json) return Result.ERROR

        val ed = sp.edit()
        putPasswordWrap(ed, pw, dek)
        ed.putString("data", newData)
        ed.remove("hash").remove("salt")
        if (!ed.commit()) return Result.ERROR
        Crypto.deleteLegacyKey()
        Session.dataKey = dek
        return Result.OK
    }

    // ---------------- Fingerprint ----------------

    fun bioEnrolled(): Boolean = sp.contains("wrap_bio") && sp.contains("bio_iv")

    fun bioIv(): ByteArray = Crypto.unb64(sp.getString("bio_iv", "") ?: "")

    /** [cipher] is the authenticated encrypt cipher returned by BiometricPrompt. */
    fun storeBioWrap(cipher: Cipher) {
        val dek = Session.dataKey ?: return
        val ct = cipher.doFinal(dek.encoded)
        sp.edit()
            .putString("wrap_bio", Crypto.b64(ct))
            .putString("bio_iv", Crypto.b64(cipher.iv))
            .commit()
    }

    /** [cipher] is the authenticated decrypt cipher returned by BiometricPrompt. */
    fun unlockWithBio(cipher: Cipher): Boolean = try {
        val ct = Crypto.unb64(sp.getString("wrap_bio", null) ?: "")
        Session.dataKey = SecretKeySpec(cipher.doFinal(ct), "AES")
        true
    } catch (e: Exception) {
        false
    }

    fun clearBio() {
        sp.edit().remove("wrap_bio").remove("bio_iv").commit()
        Crypto.deleteBioKey()
    }

    // ---------------- Wrong-password lockout ----------------

    fun lockRemainingMs(): Long {
        val left = sp.getLong("lock_until", 0L) - System.currentTimeMillis()
        // If the clock was turned back, never wait longer than the maximum delay
        return left.coerceIn(0L, MAX_DELAY_MS)
    }

    fun recordFailure() {
        val n = sp.getInt("fails", 0) + 1
        val ed = sp.edit().putInt("fails", n)
        if (n >= FREE_ATTEMPTS) {
            val shift = (n - FREE_ATTEMPTS).coerceAtMost(10)
            val delay = (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
            ed.putLong("lock_until", System.currentTimeMillis() + delay)
        }
        ed.commit()
    }

    fun resetFailures() {
        sp.edit().remove("fails").remove("lock_until").commit()
    }

    // ---------------- Data ----------------

    fun toJson(list: List<Entry>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("userId", it.userId)
                    .put("password", it.password)
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): MutableList<Entry> {
        val arr = JSONArray(json)
        val list = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Entry(
                    o.getString("id"),
                    o.getString("name"),
                    o.getString("userId"),
                    o.getString("password")
                )
            )
        }
        return list
    }

    fun load(): MutableList<Entry> {
        val key = Session.dataKey ?: throw IllegalStateException("locked")
        val s = sp.getString("data", null) ?: return mutableListOf()
        return fromJson(Crypto.decryptString(key, s))
    }

    fun save(list: List<Entry>) {
        val key = Session.dataKey ?: throw IllegalStateException("locked")
        sp.edit().putString("data", Crypto.encryptString(key, toJson(list))).commit()
    }
}
