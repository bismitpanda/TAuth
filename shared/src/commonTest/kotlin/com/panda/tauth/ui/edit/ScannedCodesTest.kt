package com.panda.tauth.ui.edit

import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.OtpType
import kotlin.test.Test
import kotlin.test.assertEquals

// Base32 of the RFC 4226 seed "12345678901234567890".
private const val SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

private const val GITHUB = "otpauth://totp/GitHub:alice?secret=$SECRET&issuer=GitHub"
private const val ZENDESK = "otpauth://hotp/bob?secret=$SECRET&counter=41"

class ScannedCodesTest {
    @Test
    fun `an account among the codes is offered`() {
        assertEquals(1, accountsIn(listOf(GITHUB)).size)
    }

    // One image can hold a page of codes, and most of what a camera sees is not an account.
    @Test
    fun `a code that is not an account is passed over`() {
        assertEquals(emptyList(), accountsIn(listOf("https://example.com/pay/12345")))
    }

    @Test
    fun `the accounts among a mixture are the ones offered`() {
        val payloads = listOf("WIFI:S:home;T:WPA;P:hunter2;;", GITHUB, "plain text")

        assertEquals(listOf("alice"), accountsIn(payloads).map { it.accountName })
    }

    @Test
    fun `every account in an image is offered`() {
        assertEquals(2, accountsIn(listOf(GITHUB, ZENDESK)).size)
    }

    @Test
    fun `an image holding no codes offers nothing`() {
        assertEquals(emptyList(), accountsIn(emptyList()))
    }

    // The list stands on screen while it is read, so what names a choice is the account and not the
    // credential behind it.
    @Test
    fun `a choice is named by its issuer and account name`() {
        assertEquals("GitHub — alice", scannedLabel(OtpAuthUri(OtpType.TOTP, "alice", SECRET, issuer = "GitHub")))
    }

    @Test
    fun `a choice with no issuer is named by its account alone`() {
        assertEquals("bob", scannedLabel(OtpAuthUri(OtpType.TOTP, "bob", SECRET)))
    }

    @Test
    fun `no rendering of a choice carries the secret`() {
        val label = scannedLabel(OtpAuthUri(OtpType.TOTP, "alice", SECRET, issuer = "GitHub"))

        assertEquals(false, SECRET in label)
    }
}
