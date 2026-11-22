package com.attendance.nfc.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** تجزئة كلمات المرور بـ PBKDF2-SHA256 مع ملح عشوائي (لا تُخزَّن كلمة المرور نفسها). */
object Passwords {
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun fromHex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun newSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return toHex(b)
    }

    fun hash(password: String, saltHex: String): String {
        val spec = PBEKeySpec(password.toCharArray(), fromHex(saltHex), 100_000, 256)
        return toHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    fun verify(password: String, saltHex: String, hashHex: String): Boolean =
        MessageDigest.isEqual(hash(password, saltHex).toByteArray(), hashHex.toByteArray())
}
