package com.flishly.server

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    const val ITERATIONS = 120_000
    const val KEY_LENGTH_BITS = 256

    fun hash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun verify(password: String, salt: ByteArray, expected: ByteArray): Boolean {
        val actual = hash(password, salt)
        return constantTimeEquals(actual, expected)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun randomSalt(size: Int = 16): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
}

class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences(ServerConfig.PREFS, Context.MODE_PRIVATE)

    fun hasPassword(): Boolean = prefs.contains(KEY_SALT) && prefs.contains(KEY_HASH)

    fun setPassword(password: String) {
        val salt = PasswordHasher.randomSalt()
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(PasswordHasher.hash(password, salt), Base64.NO_WRAP))
            .apply()
    }

    fun clear() = prefs.edit().remove(KEY_SALT).remove(KEY_HASH).apply()

    fun verify(password: String): Boolean {
        val saltB64 = prefs.getString(KEY_SALT, null) ?: return false
        val hashB64 = prefs.getString(KEY_HASH, null) ?: return false
        return try {
            PasswordHasher.verify(
                password,
                Base64.decode(saltB64, Base64.NO_WRAP),
                Base64.decode(hashB64, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val KEY_SALT = "auth_salt_b64"
        private const val KEY_HASH = "auth_hash_b64"
    }
}
