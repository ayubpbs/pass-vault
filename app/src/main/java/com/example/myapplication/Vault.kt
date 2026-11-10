package com.example.myapplication

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class Entry(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var userId: String,
    var password: String
)

/** Keeps the "is the app unlocked" flag in memory only. */
object Session {
    @Volatile
    var unlocked = false
}

class Vault(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun hasAppPassword() = sp.contains("hash")

    fun setAppPassword(pw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Crypto.hash(pw, salt))
            .apply()
    }

    fun verify(pw: String): Boolean {
        val salt = Base64.decode(sp.getString("salt", "") ?: return false, Base64.NO_WRAP)
        val expected = sp.getString("hash", null) ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(),
            Crypto.hash(pw, salt).toByteArray()
        )
    }

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
        val s = sp.getString("data", null) ?: return mutableListOf()
        return fromJson(Crypto.decrypt(s))
    }

    fun save(list: List<Entry>) {
        sp.edit().putString("data", Crypto.encrypt(toJson(list))).apply()
    }
}
