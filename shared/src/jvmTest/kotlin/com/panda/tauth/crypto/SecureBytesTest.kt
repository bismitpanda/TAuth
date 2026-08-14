package com.panda.tauth.crypto

import com.panda.tauth.awaitBlockedOrFinished
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureBytesTest {
    @Test
    fun `destroy zeroes the backing array`() {
        val backing = byteArrayOf(1, 2, 3, 4)
        SecureBytes.adopt(backing).destroy()
        assertContentEquals(ByteArray(4), backing)
    }

    @Test
    fun `a lend after destroy never reaches the bytes`() {
        // The refusal is the block not running at all: a block that ran and was handed the zeroed
        // array would seal a vault under zeros and report success.
        val secret = SecureBytes.adopt(byteArrayOf(1, 2, 3))
        secret.destroy()
        var ran = false
        secret.lendOrNull { ran = true }
        assertFalse(ran)
    }

    @Test
    fun `use zeroes the backing array on the way out`() {
        val backing = byteArrayOf(9, 9, 9)
        SecureBytes.adopt(backing).use { it.lendOrNull { bytes -> bytes.size } }
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
    fun `a destroy landing inside a lend does not zero the bytes it lent`() {
        // The destroy is provably inside the lend: it has been called, and it has either parked on
        // the lend or already run, before the block below reads the bytes it was given.
        val secret = SecureBytes.adopt(byteArrayOf(1, 2, 3, 4))
        val lent = CountDownLatch(1)
        val release = CountDownLatch(1)
        var observed = ByteArray(0)
        val lender = thread {
            secret.lendOrNull { bytes ->
                lent.countDown()
                release.await()
                observed = bytes.copyOf()
            }
        }
        lent.await()
        val finished = AtomicBoolean(false)
        val destroyer = thread {
            secret.destroy()
            finished.set(true)
        }
        awaitBlockedOrFinished(destroyer, finished)
        release.countDown()
        lender.join()
        destroyer.join()
        assertContentEquals(byteArrayOf(1, 2, 3, 4), observed)
    }

    @Test
    fun `there is nothing to lend once the bytes are destroyed`() {
        val secret = SecureBytes.adopt(byteArrayOf(1, 2, 3))
        secret.destroy()
        assertNull(secret.lendOrNull { it })
    }

    @Test
    fun `no member hands the byte array back to a caller`() {
        // A member returning the array hands it over with no exclusion held, so a destroy() landing
        // after its check zeroes bytes the caller is still using. A copy would be one nothing zeroes.
        val handers = SecureBytes::class.java.declaredMethods
            .filter { !it.isSynthetic && it.returnType == ByteArray::class.java }
            .map { it.name }
        assertEquals(emptyList(), handers)
    }

    @Test
    fun `toString renders neither the bytes nor their hex`() {
        val rendered = SecureBytes.adopt(byteArrayOf(0x41, 0x42, 0x43)).toString()
        assertTrue("ABC" !in rendered && "414243" !in rendered, rendered)
    }
}
