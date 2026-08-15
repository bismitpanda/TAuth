package com.panda.tauth.ui.list

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.ui.hotpRow
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.ui.totpRow
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

// The row's own wording, repeated here as literals so a changed label fails the test that names it
// rather than following it.
private const val GENERATE = "Generate code"
private const val HIDE = "Hide code"
private const val MENU = "More"
private const val EDIT = "Edit"
private const val COPY_CODE = "Copy code"
private const val COPY_URI = "Copy otpauth:// URI"
private const val DELETE = "Delete"
private const val RING_RUNNING = "Countdown"
private const val RING_EXPIRING = "Countdown, expiring"

// RFC 4226 Appendix D counter 0 over the seed the fixtures carry. The hotp row must not show this
// until it is asked for, and the pair of tests below makes that absence falsifiable.
private const val COUNTER_ZERO_CODE = "755224"
private const val COUNTER_ZERO_GROUPED = "755 224"

private const val TOTP_CODE = "287082"
private const val TOTP_GROUPED = "287 082"

class AccountRowTest {
    @get:Rule
    val compose = createComposeRule()

    private var copies = 0
    private var generations = 0
    private var hides = 0
    private var edits = 0
    private var uriRequests = 0
    private var deletions = 0

    @Test
    fun `a totp row shows its issuer`() {
        show(totpRow(), code = TotpCode(TOTP_CODE, 30, 30))

        compose.onNodeWithText("GitHub").assertIsDisplayed()
    }

    @Test
    fun `a totp row shows its account name`() {
        show(totpRow(), code = TotpCode(TOTP_CODE, 30, 30))

        compose.onNodeWithText("alice").assertIsDisplayed()
    }

    @Test
    fun `a totp row shows the code in two groups`() {
        show(totpRow(), code = TotpCode(TOTP_CODE, 30, 30))

        compose.onNodeWithText(TOTP_GROUPED).assertIsDisplayed()
    }

    @Test
    fun `an eight-digit totp row groups its code in fours`() {
        show(totpRow(digits = 8), code = TotpCode("94287082", 30, 30))

        compose.onNodeWithText("9428 7082").assertIsDisplayed()
    }

    @Test
    fun `a countdown above the boundary reports a running code`() {
        val entry = totpRow()
        show(entry, code = TotpCode(TOTP_CODE, 6, 30))

        compose.onNodeWithTag(countdownTag(entry.id)).assertContentDescriptionEquals(RING_RUNNING)
    }

    @Test
    fun `a countdown on the boundary reports an expiring code`() {
        val entry = totpRow()
        show(entry, code = TotpCode(TOTP_CODE, 5, 30))

        compose.onNodeWithTag(countdownTag(entry.id)).assertContentDescriptionEquals(RING_EXPIRING)
    }

    // The period reaching the ring is the one the code was generated under. At the same reading a
    // sixty-second account sweeps half what a thirty-second one does, and a fixed period would not.
    @Test
    fun `a thirty-second account halfway through its period sweeps half the ring`() {
        val entry = totpRow(period = 30)
        show(entry, code = TotpCode(TOTP_CODE, 15, 30))

        compose.onNodeWithTag(countdownTag(entry.id))
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.5f, 0f..1f))
    }

    @Test
    fun `a sixty-second account at the same reading sweeps a quarter of the ring`() {
        val entry = totpRow(period = 60)
        show(entry, code = TotpCode(TOTP_CODE, 15, 60))

        compose.onNodeWithTag(countdownTag(entry.id))
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.25f, 0f..1f))
    }

    @Test
    fun `tapping the code reports a copy`() {
        show(totpRow(), code = TotpCode(TOTP_CODE, 30, 30))

        compose.onNodeWithText(TOTP_GROUPED).performClick()

        compose.runOnIdle { assertEquals(1, copies) }
    }

    @Test
    fun `an hotp row shows its counter`() {
        show(hotpRow(counter = 41uL))

        compose.onNodeWithText("Counter 41").assertIsDisplayed()
    }

    // Displaying a code spends a counter value, so the row shows none until it is asked. The test
    // below puts the same code on screen the moment the row is given one.
    @Test
    fun `an hotp row shows no code before one is asked for`() {
        show(hotpRow())

        compose.onNodeWithText(COUNTER_ZERO_GROUPED).assertDoesNotExist()
    }

    @Test
    fun `an hotp row shows the code it is given`() {
        show(hotpRow(), generatedCode = COUNTER_ZERO_CODE)

        compose.onNodeWithText(COUNTER_ZERO_GROUPED).assertIsDisplayed()
    }

    @Test
    fun `an hotp row carries no countdown`() {
        val entry = hotpRow()
        show(entry, generatedCode = COUNTER_ZERO_CODE)

        compose.onNodeWithTag(countdownTag(entry.id)).assertDoesNotExist()
    }

    @Test
    fun `the generate control reports a generation`() {
        show(hotpRow())

        compose.onNodeWithText(GENERATE).performClick()

        compose.runOnIdle { assertEquals(1, generations) }
    }

    @Test
    fun `the generate control is disabled while it is cooling down`() {
        show(hotpRow(), isGenerateEnabled = false)

        compose.onNodeWithText(GENERATE).assertIsNotEnabled()
    }

    @Test
    fun `a disabled generate control reports nothing when pressed`() {
        show(hotpRow(), isGenerateEnabled = false)

        compose.onNodeWithText(GENERATE).performClick()

        compose.runOnIdle { assertEquals(0, generations) }
    }

    @Test
    fun `hiding a generated code reports the collapse`() {
        show(hotpRow(), generatedCode = COUNTER_ZERO_CODE)

        compose.onNodeWithText(HIDE).performClick()

        compose.runOnIdle { assertEquals(1, hides) }
    }

    @Test
    fun `a row with no code offers nothing to hide`() {
        show(hotpRow())

        compose.onNodeWithText(HIDE).assertDoesNotExist()
    }

    @Test
    fun `the overflow menu reports an edit`() {
        show(totpRow(), code = TotpCode(TOTP_CODE, 30, 30))

        openMenu()
        compose.onNodeWithText(EDIT).performClick()

        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test
    fun `the overflow menu reports a URI request`() {
        show(totpRow(), code = TotpCode(TOTP_CODE, 30, 30))

        openMenu()
        compose.onNodeWithText(COPY_URI).performClick()

        compose.runOnIdle { assertEquals(1, uriRequests) }
    }

    @Test
    fun `the overflow menu reports a delete`() {
        show(totpRow(), code = TotpCode(TOTP_CODE, 30, 30))

        openMenu()
        compose.onNodeWithText(DELETE).performClick()

        compose.runOnIdle { assertEquals(1, deletions) }
    }

    @Test
    fun `the overflow menu copies the code on screen`() {
        show(totpRow(), code = TotpCode(TOTP_CODE, 30, 30))

        openMenu()
        compose.onNodeWithText(COPY_CODE).performClick()

        compose.runOnIdle { assertEquals(1, copies) }
    }

    // There is nothing to copy until a code has been asked for, and an entry the menu could copy from
    // a blank row would be copying whatever the last row held.
    @Test
    fun `the overflow menu cannot copy a code that is not on screen`() {
        show(hotpRow())

        openMenu()

        compose.onNodeWithText(COPY_CODE).assertIsNotEnabled()
    }

    @Test
    fun `a notice the caller supplied is on screen`() {
        show(totpRow(), code = TotpCode(TOTP_CODE, 30, 30), notice = "Code copied")

        compose.onNodeWithText("Code copied").assertIsDisplayed()
    }

    private fun openMenu() = compose.onNodeWithText(MENU).performClick()

    private fun show(
        entry: UnlockedEntry,
        code: TotpCode? = null,
        generatedCode: String? = null,
        isGenerateEnabled: Boolean = true,
        notice: String? = null,
    ) {
        compose.setContent {
            TauthTheme {
                AccountRow(
                    entry = entry,
                    code = code,
                    generatedCode = generatedCode,
                    isGenerateEnabled = isGenerateEnabled,
                    notice = notice,
                    onCopyCode = { copies++ },
                    onGenerate = { generations++ },
                    onHideCode = { hides++ },
                    onEdit = { edits++ },
                    onCopyUri = { uriRequests++ },
                    onDelete = { deletions++ },
                )
            }
        }
    }
}
