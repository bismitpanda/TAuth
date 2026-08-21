package com.panda.tauth.ui.list

import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.settings.SortOrder
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

// Every field the code under test reads varies across these four: the issuer, the account name, the
// order index and the creation time. Two share an issuer bar its case, and one carries none at all.
private val GITHUB_ALICE = entry(
    id = "a",
    issuer = "GitHub",
    accountName = "alice",
    orderIndex = 0,
    createdAt = "2026-01-01T00:00:00Z",
)

private val ZENDESK_BOB = entry(
    id = "b",
    issuer = "Zendesk",
    accountName = "bob",
    orderIndex = 1,
    createdAt = "2026-04-01T00:00:00Z",
    type = OtpType.HOTP,
)

private val GITHUB_AARON = entry(
    id = "c",
    issuer = "github",
    accountName = "aaron",
    orderIndex = 2,
    createdAt = "2026-03-01T00:00:00Z",
    algorithm = HashAlgorithm.SHA512,
)

private val NAMELESS_MALLORY = entry(
    id = "d",
    issuer = null,
    accountName = "mallory",
    orderIndex = 3,
    createdAt = "2026-02-01T00:00:00Z",
)

private val ALL = listOf(ZENDESK_BOB, NAMELESS_MALLORY, GITHUB_AARON, GITHUB_ALICE)

private fun entry(
    id: String,
    issuer: String?,
    accountName: String,
    orderIndex: Int,
    createdAt: String,
    type: OtpType = OtpType.TOTP,
    algorithm: HashAlgorithm = HashAlgorithm.SHA1,
) = UnlockedEntry(
    id = id,
    type = type,
    accountName = accountName,
    createdAt = Instant.parse(createdAt),
    issuer = issuer,
    algorithm = algorithm,
    digits = 6,
    period = if (type == OtpType.TOTP) 30 else null,
    counter = if (type == OtpType.HOTP) 7uL else null,
    orderIndex = orderIndex,
)

private fun idsOf(order: SortOrder): List<String> = sorted(ALL, order).map { it.id }

class AccountOrderTest {
    @Test
    fun `an empty query matches every account`() {
        assertTrue(matchesQuery(NAMELESS_MALLORY, ""))
    }

    @Test
    fun `a query of spaces alone matches every account`() {
        assertTrue(matchesQuery(NAMELESS_MALLORY, "   "))
    }

    @Test
    fun `a substring of the account name matches`() {
        assertTrue(matchesQuery(GITHUB_ALICE, "lic"))
    }

    @Test
    fun `a substring of the issuer matches`() {
        assertTrue(matchesQuery(GITHUB_ALICE, "tHu"))
    }

    @Test
    fun `a query in the other case matches`() {
        assertTrue(matchesQuery(GITHUB_ALICE, "ALICE"))
    }

    @Test
    fun `an account with no issuer is still matched on its name`() {
        assertTrue(matchesQuery(NAMELESS_MALLORY, "mall"))
    }

    @Test
    fun `an account with no issuer does not match a query the issuer would have`() {
        assertFalse(matchesQuery(NAMELESS_MALLORY, "GitHub"))
    }

    @Test
    fun `a query matching neither field is refused`() {
        assertFalse(matchesQuery(GITHUB_ALICE, "zendesk"))
    }

    @Test
    fun `manual order follows the stored order index`() {
        assertEquals(listOf("a", "b", "c", "d"), idsOf(SortOrder.MANUAL))
    }

    // The issuers differ only in case, so an ordering that compared them byte for byte would put
    // GitHub after github rather than sorting the two together.
    @Test
    fun `issuer order ignores the case of the issuer`() {
        assertEquals(listOf("c", "a", "d", "b"), idsOf(SortOrder.ISSUER))
    }

    @Test
    fun `recently added order runs from the newest account backwards`() {
        assertEquals(listOf("b", "c", "d", "a"), idsOf(SortOrder.RECENTLY_ADDED))
    }
}
