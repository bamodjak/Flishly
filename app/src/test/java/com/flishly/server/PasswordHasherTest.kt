package com.flishly.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {

    @Test
    fun `hash is deterministic for same password and salt`() {
        val salt = ByteArray(16) { it.toByte() }
        val a = PasswordHasher.hash("hunter2", salt)
        val b = PasswordHasher.hash("hunter2", salt)
        assertArrayEquals(a, b)
    }

    @Test
    fun `different salts produce different hashes`() {
        val a = PasswordHasher.hash("hunter2", ByteArray(16) { 1 })
        val b = PasswordHasher.hash("hunter2", ByteArray(16) { 2 })
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `verify accepts correct password`() {
        val salt = PasswordHasher.randomSalt()
        val hash = PasswordHasher.hash("correct horse battery staple", salt)
        assertTrue(PasswordHasher.verify("correct horse battery staple", salt, hash))
    }

    @Test
    fun `verify rejects wrong password`() {
        val salt = PasswordHasher.randomSalt()
        val hash = PasswordHasher.hash("correct horse battery staple", salt)
        assertFalse(PasswordHasher.verify("correct horse battery staple ", salt, hash))
        assertFalse(PasswordHasher.verify("", salt, hash))
    }

    @Test
    fun `constant time equals handles mismatched lengths`() {
        assertFalse(PasswordHasher.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1)))
        assertTrue(PasswordHasher.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
    }

    @Test
    fun `random salt is unique`() {
        val a = PasswordHasher.randomSalt()
        val b = PasswordHasher.randomSalt()
        assertFalse(a.contentEquals(b))
    }
}
