package com.panda.tauth.ui.list

import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun account(issuer: String?, accountName: String) = UnlockedEntry(
    id = "0192f4c1-0000-7000-8000-000000000001",
    type = OtpType.TOTP,
    accountName = accountName,
    createdAt = Instant.fromEpochSeconds(0),
    issuer = issuer,
    algorithm = HashAlgorithm.SHA1,
    digits = 6,
    period = 30,
    counter = null,
    orderIndex = 0,
)

class AccountMarkTest {
    @Test
    fun `an account takes its initial from the issuer`() {
        assertEquals("G", markInitial(account(issuer = "GitHub", accountName = "alice")))
    }

    @Test
    fun `an account with no issuer takes its initial from the account name`() {
        assertEquals("A", markInitial(account(issuer = null, accountName = "alice")))
    }

    @Test
    fun `an issuer of spaces alone falls back to the account name`() {
        assertEquals("A", markInitial(account(issuer = "   ", accountName = "alice")))
    }

    @Test
    fun `an initial is the first letter or digit rather than the first character`() {
        assertEquals("M", markInitial(account(issuer = "@monzo", accountName = "alice")))
    }

    @Test
    fun `a name carrying no letter or digit falls back rather than drawing nothing`() {
        assertEquals("?", markInitial(account(issuer = "!!!", accountName = "!!!")))
    }

    @Test
    fun `an initial is upper case whatever the name is`() {
        assertEquals("G", markInitial(account(issuer = "github", accountName = "alice")))
    }

    @Test
    fun `two accounts under one issuer take different hues`() {
        val first = markHue(account(issuer = "GitHub", accountName = "alice"))
        val second = markHue(account(issuer = "GitHub", accountName = "bob"))

        assertNotEquals(first, second)
    }

    @Test
    fun `one account takes the same hue every time it is read`() {
        val entry = account(issuer = "GitHub", accountName = "alice")

        assertEquals(markHue(entry), markHue(entry))
    }

    @Test
    fun `a hue is a fraction of one turn`() {
        val hues = listOf("GitHub", "Monzo", "!!!", "", "Ω").map {
            markHue(account(issuer = it, accountName = "alice"))
        }

        assertTrue(hues.all { it in 0f..1f }, "$hues")
    }
}
