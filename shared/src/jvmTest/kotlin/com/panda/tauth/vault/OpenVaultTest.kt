package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.awaitBlockedOrFinished
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun password() = "correct horse battery staple".toCharArray()

private fun openVault(): OpenVault {
    val created = VaultCodec.create(password(), VaultBody())
    assertIs<Outcome.Success<ByteArray>>(created)
    val opened = VaultCodec.open(created.value, password())
    assertIs<Outcome.Success<OpenVault>>(opened)
    return opened.value
}

class OpenVaultTest {
    @Test
    fun `no member hands the key back to a caller`() {
        // A member returning the key lets a write hold it while a close from the tray zeroes it, so
        // the seal that follows goes out under zeros while the header still wraps the real key.
        val handers = OpenVault::class.java.declaredMethods
            .filter { !it.isSynthetic && it.returnType == ByteArray::class.java }
            .map { it.name }
        assertEquals(emptyList(), handers)
    }

    @Test
    fun `a close landing inside a write does not zero the key the write is using`() {
        // Hiding the window closes the vault from another thread. A key zeroed part-way through a
        // write seals the body under zeros while the header wraps the real key.
        val vault = openVault()
        val key = checkNotNull(vault.useDek { it.copyOf() })
        val lent = CountDownLatch(1)
        val release = CountDownLatch(1)
        var observed = ByteArray(0)
        val writer = thread {
            vault.useDek { dek ->
                lent.countDown()
                release.await()
                observed = dek.copyOf()
            }
        }
        lent.await()
        val finished = AtomicBoolean(false)
        val closer = thread {
            vault.close()
            finished.set(true)
        }
        awaitBlockedOrFinished(closer, finished)
        release.countDown()
        writer.join()
        closer.join()
        assertContentEquals(key, observed)
    }
}
