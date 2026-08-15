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

private const val ROW_HEIGHT = 80
private const val GAPPED_PITCH = 88

// A scrolled list reports the first visible row's offset as negative, so a pitch read off that offset
// alone rather than off the distance between two of them comes out wrong here.
private val SCROLLED_OFFSETS = listOf(-24, 64, 152)

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

    @Test
    fun `a drop one row down lands one position further on`() {
        assertEquals(1, dropIndex(from = 0, draggedPixels = 80f, rowPitchPixels = 80f, count = 4))
    }

    @Test
    fun `a drop that travelled under half a row stays where it was`() {
        assertEquals(2, dropIndex(from = 2, draggedPixels = 30f, rowPitchPixels = 80f, count = 4))
    }

    @Test
    fun `a drop upwards lands earlier in the list`() {
        assertEquals(1, dropIndex(from = 3, draggedPixels = -160f, rowPitchPixels = 80f, count = 4))
    }

    @Test
    fun `a drop past the end of the list lands on the end`() {
        assertEquals(3, dropIndex(from = 0, draggedPixels = 8000f, rowPitchPixels = 80f, count = 4))
    }

    @Test
    fun `a drop above the top of the list lands on the top`() {
        assertEquals(0, dropIndex(from = 3, draggedPixels = -8000f, rowPitchPixels = 80f, count = 4))
    }

    // A list that has not been measured yet reports no pitch, and dividing by it would give a
    // position from nothing.
    @Test
    fun `a drop against an unmeasured row stays where it was`() {
        assertEquals(2, dropIndex(from = 2, draggedPixels = 400f, rowPitchPixels = 0f, count = 4))
    }

    // The list arranges its rows with a gap between them, so the distance from one row to the next is
    // the row plus the gap. Measuring the row alone counts every gap a drag crossed as extra travel.
    @Test
    fun `the pitch is the distance between two adjacent rows`() {
        assertEquals(GAPPED_PITCH, rowPitch(offsets = SCROLLED_OFFSETS, firstSize = ROW_HEIGHT))
    }

    @Test
    fun `the pitch of a single row on screen is its own height`() {
        assertEquals(ROW_HEIGHT, rowPitch(offsets = listOf(-24), firstSize = ROW_HEIGHT))
    }

    @Test
    fun `a list with nothing measured has no pitch`() {
        assertEquals(0, rowPitch(offsets = emptyList(), firstSize = 0))
    }

    // The distance across six gaps, written out rather than derived from the divisor: 528 over a pitch
    // of 88 is six, over the 80-pixel row height it is 6.6 and rounds a place further on.
    @Test
    fun `a drag across six gaps lands on the row it was dropped over`() {
        val pitch = rowPitch(offsets = SCROLLED_OFFSETS, firstSize = ROW_HEIGHT)

        assertEquals(6, dropIndex(from = 0, draggedPixels = 528f, rowPitchPixels = pitch.toFloat(), count = 10))
    }
}
