package com.panda.tauth.ui.edit

import com.panda.tauth.Outcome
import com.panda.tauth.vault.ImageReadError
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

private const val GITHUB = "otpauth://totp/GitHub:alice?secret=$SECRET&issuer=GitHub"
private const val ZENDESK = "otpauth://hotp/bob?secret=$SECRET&counter=41"

// Unconfined runs each resumption on the thread that causes it, so every assertion below sees a
// settled holder without joining anything.
class ScanStateTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val scan = ScanState()

    private var taken: String? = null

    @AfterTest
    fun stopPendingWork() {
        scope.cancel()
    }

    @Test
    fun `one account in an image is taken without asking`() {
        read(listOf(GITHUB))

        assertEquals(GITHUB, taken)
    }

    @Test
    fun `one account in an image offers no choice`() {
        read(listOf(GITHUB))

        assertEquals(emptyList(), scan.choices)
    }

    @Test
    fun `several accounts in an image are offered as a choice`() {
        read(listOf(GITHUB, ZENDESK))

        assertEquals(2, scan.choices.size)
    }

    @Test
    fun `several accounts in an image take none of them yet`() {
        read(listOf(GITHUB, ZENDESK))

        assertNull(taken)
    }

    @Test
    fun `the account chosen is the one taken`() {
        read(listOf(GITHUB, ZENDESK))

        scan.choose(scan.choices.last()) { taken = it }

        assertTrue(taken!!.startsWith("otpauth://hotp/bob"))
    }

    @Test
    fun `choosing ends the choice`() {
        read(listOf(GITHUB, ZENDESK))

        scan.choose(scan.choices.first()) { taken = it }

        assertEquals(emptyList(), scan.choices)
    }

    @Test
    fun `abandoning the choice takes nothing`() {
        read(listOf(GITHUB, ZENDESK))

        scan.cancelChoice()

        assertNull(taken)
    }

    // A code that is not an account and no code at all are different things to the person holding
    // the image.
    @Test
    fun `a code that is not an account says so`() {
        read(listOf("https://example.com/pay/12345"))

        assertEquals(SCAN_NOT_AN_ACCOUNT, scan.notice)
    }

    @Test
    fun `an image holding no code says so`() {
        read(emptyList())

        assertEquals(SCAN_NO_CODE, scan.notice)
    }

    @Test
    fun `an image the user declined says nothing`() {
        scan.read(scope, { Outcome.Success(null) }) { taken = it }

        assertNull(scan.notice)
    }

    @Test
    fun `an image the user declined takes nothing`() {
        scan.read(scope, { Outcome.Success(null) }) { taken = it }

        assertNull(taken)
    }

    @Test
    fun `an image that could not be read reports what the shell said`() {
        scan.read(scope, { Outcome.Failure<ImageReadError>(VaultError.Io(IOException("no such file"))) }) {
            taken = it
        }

        assertTrue(scan.error is VaultError.Io)
    }

    @Test
    fun `a second read opens on nothing the first reported`() {
        read(emptyList())

        read(listOf(GITHUB))

        assertNull(scan.notice)
    }

    private fun read(payloads: List<String>) {
        scan.read(scope, { Outcome.Success(payloads) }) { taken = it }
    }
}
