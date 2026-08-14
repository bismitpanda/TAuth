package com.panda.tauth.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecureBytesTest {
    @Test
    fun `destroy zeroes the backing array`() {
        val backing = byteArrayOf(1, 2, 3, 4)
        SecureBytes.adopt(backing).destroy()
        assertContentEquals(ByteArray(4), backing)
    }

    @Test
    fun `reveal after destroy is rejected`() {
        val secret = SecureBytes.adopt(byteArrayOf(1, 2, 3))
        secret.destroy()
        assertFailsWith<IllegalStateException> { secret.reveal() }
    }

    @Test
    fun `use zeroes the backing array on the way out`() {
        val backing = byteArrayOf(9, 9, 9)
        SecureBytes.adopt(backing).use { it.reveal() }
        assertContentEquals(ByteArray(3), backing)
    }

    @Test
    fun `use zeroes the backing array when the block throws`() {
        val backing = byteArrayOf(9, 9, 9)
        runCatching { SecureBytes.adopt(backing).use { error("failed midway") } }
        assertContentEquals(ByteArray(3), backing)
    }

    @Test
    fun `a second destroy leaves the array zeroed`() {
        val backing = byteArrayOf(1, 2, 3)
        val secret = SecureBytes.adopt(backing)
        secret.destroy()
        secret.destroy()
        assertContentEquals(ByteArray(3), backing)
    }

    @Test
    fun `toString renders neither the bytes nor their hex`() {
        val rendered = SecureBytes.adopt(byteArrayOf(0x41, 0x42, 0x43)).toString()
        assertTrue("ABC" !in rendered && "414243" !in rendered, rendered)
    }
}
