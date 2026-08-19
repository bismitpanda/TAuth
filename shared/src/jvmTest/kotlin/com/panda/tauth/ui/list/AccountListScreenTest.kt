package com.panda.tauth.ui.list

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.panda.tauth.Outcome
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.settings.SortOrder
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.ui.ClipboardCopy
import com.panda.tauth.ui.CopyResult
import com.panda.tauth.ui.components.DISCLOSURE_PASSWORD_TAG
import com.panda.tauth.ui.components.DISCLOSURE_STATEMENT_TAG
import com.panda.tauth.ui.hotpRow
import com.panda.tauth.ui.qr.QR_SAVE_PROBLEM_TAG
import com.panda.tauth.ui.qr.QrEncoding
import com.panda.tauth.ui.qr.QrSymbol
import com.panda.tauth.ui.settings.ExportError
import com.panda.tauth.ui.settings.FileWriteError
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.ui.totpRow
import com.panda.tauth.vault.EntryChangeError
import com.panda.tauth.vault.VaultError
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The screen's own wording, repeated here as literals so a changed label fails the test that names it
// rather than following it.
private const val ADD = "Add account"
private const val LOCK = "Lock"
private const val MENU = "More"
private const val GENERATE = "Generate code"
private const val COPY_URI = "Copy otpauth:// URI"
private const val SHOW_QR = "Show QR code"
private const val QR_SYMBOL = "QR code"
private const val QR_COPY_URI = "Copy URI"
private const val QR_SAVE = "Save as PNG"
private const val QR_CLOSE = "Close"
private const val DELETE = "Delete"
private const val DELETE_CONFIRM = "Delete account"
private const val DELETE_CANCEL = "Keep account"
private const val DISCLOSE = "Disclose"
private const val SORT_ISSUER = "Issuer A–Z"
private const val SORT_MANUAL = "Manual order"
private const val EMPTY_HEADING_TEXT = "No accounts yet"
private const val EMPTY_BODY_TEXT =
    "Add an account by pasting the otpauth:// URI its provider gave you, by reading an image of the " +
        "QR code it showed you, or by typing its details in by hand."

// The gate has to state what is about to leave the vault, so the sentence is written out here rather
// than read from the function the screen calls to produce it.
private const val DISCLOSURE_STATEMENT =
    "The complete secret for GitHub — alice is about to be placed on the clipboard as an otpauth:// URI."

// The screen is a different destination from the clipboard, so the gate in front of it says so.
private const val QR_DISCLOSURE_STATEMENT =
    "The complete secret for GitHub — alice is about to be drawn on screen as a QR code."

private const val TOTP_CODE = "287082"
private const val TOTP_GROUPED = "287 082"

// RFC 4226 Appendix D counter 0 over the seed the fixtures carry.
private const val HOTP_CODE = "755224"
private const val HOTP_GROUPED = "755 224"

private const val RIGHT_PASSWORD = "correct horse battery staple"
private const val WRONG_PASSWORD = "wrong"

private const val DISCLOSED_URI = "otpauth://totp/GitHub:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=GitHub"

private const val CLEAR_SECONDS = 20

private const val ONE_SECOND_MILLIS = 1_000L

// Past the interval the generate control is dead for, whatever that interval is set to below it.
private const val COOLDOWN_OVERSHOOT_MILLIS = 10_000L

private val TOTP = totpRow(orderIndex = 1)
private val HOTP = hotpRow(orderIndex = 0)

// A third account, so that the three orderings give three different layouts: two rows admit only two
// layouts between them, which would leave one ordering agreeing with another whatever the fixtures.
private val MONZO = totpRow(
    id = "0192f4c1-0000-7000-8000-0000000000c1",
    accountName = "carol",
    issuer = "Monzo",
    orderIndex = 2,
    createdAt = "2026-03-01T00:00:00Z",
)

private val ORDERED = listOf(TOTP, HOTP, MONZO)

// More rows than a short viewport holds, so the ones past the fold are the ones the list must leave
// out of what it publishes.
private val MANY = List(12) { index ->
    totpRow(
        id = "0192f4c1-0000-7000-8000-0000000001${index.toString().padStart(2, '0')}",
        accountName = "row $index",
        orderIndex = index,
    )
}

private val SHORT_VIEWPORT = 600.dp

// Three modules on a side, which is no real symbol and is all the dialog needs to draw something.
private val FAKE_SYMBOL = QrSymbol(3, BooleanArray(9) { it % 2 == 0 })

private class FakeClipboard : ClipboardCopy {
    val texts = mutableListOf<String>()
    val delays = mutableListOf<Int>()
    var answer = CopyResult.COPIED

    override fun copy(text: String, clearAfterSeconds: Int): CopyResult {
        texts += text
        delays += clearAfterSeconds
        return answer
    }
}

class AccountListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val clipboard = FakeClipboard()

    private var visible: Set<String> = emptySet()
    private var chosenSort: SortOrder? = null
    private var edited: String? = null
    private var deleted: String? = null
    private var moved: Pair<String, Int>? = null
    private var adds = 0
    private var locks = 0
    private var generations = 0
    private var generateAnswer: Outcome<String, EntryChangeError> = Outcome.Success(HOTP_CODE)
    private var encodedText: String? = null
    private val idleHolds = mutableListOf<Boolean>()
    private var savedSymbol: QrSymbol? = null
    private var saveAnswer: Outcome<Unit, FileWriteError> = Outcome.Success(Unit)

    @Test
    fun `a totp row draws the code the ticker supplied`() {
        show()

        compose.onNodeWithText(TOTP_GROUPED).assertIsDisplayed()
    }

    // The ticker computes only what it is told is on screen, so a row below the fold must not be in
    // the set. A viewport short enough to cut the list off is what makes that observable.
    @Test
    fun `a row on screen is published`() {
        show(entries = MANY, height = SHORT_VIEWPORT)

        compose.runOnIdle { assertTrue(MANY.first().id in visible, "the first row is published") }
    }

    @Test
    fun `a row below the fold is not published`() {
        show(entries = MANY, height = SHORT_VIEWPORT)

        compose.runOnIdle { assertFalse(MANY.last().id in visible, "the last row is not published") }
    }

    @Test
    fun `the ids of the rows on screen are published`() {
        show()

        compose.runOnIdle { assertEquals(setOf(TOTP.id, HOTP.id), visible) }
    }

    @Test
    fun `a search on the issuer keeps the row that matches`() {
        show()

        search("zend")

        compose.onNodeWithTag(accountRowTag(HOTP.id)).assertIsDisplayed()
    }

    @Test
    fun `a search on the issuer drops the row that does not match`() {
        show()

        search("zend")

        compose.onNodeWithTag(accountRowTag(TOTP.id)).assertDoesNotExist()
    }

    @Test
    fun `a search on the account name keeps the row that matches`() {
        show()

        search("ALIC")

        compose.onNodeWithTag(accountRowTag(TOTP.id)).assertIsDisplayed()
    }

    @Test
    fun `a search that matches nothing leaves no rows`() {
        show()

        search("nothing here")

        compose.onNodeWithTag(accountRowTag(TOTP.id)).assertDoesNotExist()
    }

    @Test
    fun `the sort control reports the order that was chosen`() {
        show()

        compose.onNodeWithText(SORT_ISSUER).performClick()

        compose.runOnIdle { assertEquals(SortOrder.ISSUER, chosenSort) }
    }

    // Which ordering is in force reads off the control itself. The selected flag is what a screen
    // reader and a test can see; the filled treatment beside it is not observable here.
    @Test
    fun `the ordering in force is the one marked selected`() {
        show(sortOrder = SortOrder.ISSUER)

        compose.onNodeWithText(SORT_ISSUER).assertIsSelected()
    }

    @Test
    fun `an ordering not in force is not marked selected`() {
        show(sortOrder = SortOrder.ISSUER)

        compose.onNodeWithText(SORT_MANUAL).assertIsNotSelected()
    }

    // A disabled control reads as one that cannot be had, which is the opposite of what the current
    // choice is.
    @Test
    fun `the ordering in force stays pressable`() {
        show(sortOrder = SortOrder.ISSUER)

        compose.onNodeWithText(SORT_ISSUER).assertIsEnabled()
    }

    // Each ordering lays the three rows out in an order neither of the others produces, so a screen
    // reading a fixed ordering rather than the one it is given fails the other two.
    @Test
    fun `the issuer ordering runs GitHub, Monzo, Zendesk`() {
        show(entries = ORDERED, sortOrder = SortOrder.ISSUER)

        assertTrue(topOf(TOTP) < topOf(MONZO), "GitHub is above Monzo")
        assertTrue(topOf(MONZO) < topOf(HOTP), "Monzo is above Zendesk")
    }

    @Test
    fun `the manual ordering follows the stored order index`() {
        show(entries = ORDERED, sortOrder = SortOrder.MANUAL)

        assertTrue(topOf(HOTP) < topOf(TOTP), "the row at order index 0 is above the row at index 1")
        assertTrue(topOf(TOTP) < topOf(MONZO), "the row at order index 1 is above the row at index 2")
    }

    @Test
    fun `the recently added ordering runs from the newest account backwards`() {
        show(entries = ORDERED, sortOrder = SortOrder.RECENTLY_ADDED)

        assertTrue(topOf(MONZO) < topOf(HOTP), "the newest account is above the next newest")
        assertTrue(topOf(HOTP) < topOf(TOTP), "the next newest is above the oldest")
    }

    @Test
    fun `the add button reports a request to add`() {
        show()

        compose.onNodeWithText(ADD).performClick()

        compose.runOnIdle { assertEquals(1, adds) }
    }

    @Test
    fun `the lock button reports a lock`() {
        show()

        compose.onNodeWithText(LOCK).performClick()

        compose.runOnIdle { assertEquals(1, locks) }
    }

    @Test
    fun `an empty vault shows the empty state`() {
        show(entries = emptyList())

        compose.onNodeWithText(EMPTY_HEADING_TEXT).assertIsDisplayed()
    }

    @Test
    fun `an empty vault names the ways an account arrives`() {
        show(entries = emptyList())

        compose.onNodeWithText(EMPTY_BODY_TEXT).assertIsDisplayed()
    }

    @Test
    fun `tapping a code puts it on the clipboard`() {
        show()

        compose.onNodeWithText(TOTP_GROUPED).performClick()

        compose.runOnIdle { assertEquals(listOf(TOTP_CODE), clipboard.texts) }
    }

    // The delay the row counts down is the one the clipboard was given, so what the screen says and
    // what the platform is about to do describe one timer.
    @Test
    fun `a copy hands the clipboard the clear delay in force`() {
        show()

        compose.onNodeWithText(TOTP_GROUPED).performClick()

        compose.runOnIdle { assertEquals(listOf(CLEAR_SECONDS), clipboard.delays) }
    }

    @Test
    fun `a copy shows the clipboard clear countdown`() {
        show()

        compose.onNodeWithText(TOTP_GROUPED).performClick()

        compose.onNodeWithText("Code copied — the clipboard clears in $CLEAR_SECONDS s").assertIsDisplayed()
    }

    @Test
    fun `a copy with no clear scheduled shows a bare confirmation`() {
        show(clearSeconds = 0)

        compose.onNodeWithText(TOTP_GROUPED).performClick()

        compose.onNodeWithText("Code copied").assertIsDisplayed()
    }

    @Test
    fun `a clipboard that refuses the copy says so`() {
        clipboard.answer = CopyResult.REFUSED
        show()

        compose.onNodeWithText(TOTP_GROUPED).performClick()

        compose.onNodeWithText("The clipboard would not take it").assertIsDisplayed()
    }

    @Test
    fun `an hotp row shows no code before the control is pressed`() {
        show()

        compose.onNodeWithText(HOTP_GROUPED).assertDoesNotExist()
    }

    @Test
    fun `pressing the generate control shows the code it returned`() {
        show()

        compose.onNodeWithText(GENERATE).performClick()

        compose.onNodeWithText(HOTP_GROUPED).assertIsDisplayed()
    }

    @Test
    fun `pressing the generate control disables it`() {
        show()

        compose.onNodeWithText(GENERATE).performClick()

        compose.onNodeWithText(GENERATE).assertIsNotEnabled()
    }

    // The ticker computes no hotp code, so a map carrying one for an hotp id is a map the screen must
    // not read: the row draws the code it generated and the copy has to take the same one.
    @Test
    fun `an hotp row copies the code it generated rather than one supplied for its id`() {
        show(codes = mapOf(TOTP.id to TotpCode(TOTP_CODE, 30, 30), HOTP.id to TotpCode(TOTP_CODE, 30, 30)))

        compose.onNodeWithText(GENERATE).performClick()
        compose.onNodeWithText(HOTP_GROUPED).performClick()

        compose.runOnIdle { assertEquals(listOf(HOTP_CODE), clipboard.texts) }
    }

    // The whole point of the disabled interval: a second press inside it must not reach the vault, or
    // two counter values are spent for one code the user asked for.
    @Test
    fun `a second press while the control is cooling down spends no counter value`() {
        show()

        compose.onNodeWithText(GENERATE).performClick()
        compose.onNodeWithText(GENERATE).performClick()

        compose.runOnIdle { assertEquals(1, generations) }
    }

    @Test
    fun `the generate control comes back once the interval has passed`() {
        show()

        compose.onNodeWithText(GENERATE).performClick()
        compose.mainClock.advanceTimeBy(COOLDOWN_OVERSHOOT_MILLIS)

        compose.onNodeWithText(GENERATE).assertIsEnabled()
    }

    @Test
    fun `the generated code stays on screen once the interval has passed`() {
        show()

        compose.onNodeWithText(GENERATE).performClick()
        compose.mainClock.advanceTimeBy(COOLDOWN_OVERSHOOT_MILLIS)

        compose.onNodeWithText(HOTP_GROUPED).assertIsDisplayed()
    }

    @Test
    fun `collapsing the row takes the generated code off the screen`() {
        show()

        compose.onNodeWithText(GENERATE).performClick()
        compose.onNodeWithText("Hide code").performClick()

        compose.onNodeWithText(HOTP_GROUPED).assertDoesNotExist()
    }

    @Test
    fun `the clipboard countdown falls as the delay runs`() {
        show()

        compose.onNodeWithText(TOTP_GROUPED).performClick()
        compose.mainClock.advanceTimeBy(ONE_SECOND_MILLIS)

        compose.onNodeWithText("Code copied — the clipboard clears in ${CLEAR_SECONDS - 1} s").assertIsDisplayed()
    }

    // A failed vault write leaves the counter where it was, so the row shows no code and the control
    // is live again rather than sitting dead over a generation that never happened.
    @Test
    fun `a refused generation shows no code`() {
        generateAnswer = Outcome.Failure(VaultError.LockedByAnotherProcess("vault.lock"))
        show()

        compose.onNodeWithText(GENERATE).performClick()

        compose.onNodeWithText(HOTP_GROUPED).assertDoesNotExist()
    }

    @Test
    fun `a refused generation leaves the control live`() {
        generateAnswer = Outcome.Failure(VaultError.LockedByAnotherProcess("vault.lock"))
        show()

        compose.onNodeWithText(GENERATE).performClick()

        compose.onNodeWithText(GENERATE).performClick()

        compose.runOnIdle { assertEquals(2, generations) }
    }

    @Test
    fun `the overflow menu asks for the password before disclosing a URI`() {
        show()

        openUriGate()

        compose.onNodeWithTag(DISCLOSURE_STATEMENT_TAG).assertTextEquals(DISCLOSURE_STATEMENT)
    }

    @Test
    fun `no URI reaches the clipboard while the gate is open`() {
        show()

        openUriGate()

        compose.runOnIdle { assertEquals(emptyList(), clipboard.texts) }
    }

    @Test
    fun `a refused password puts no URI on the clipboard`() {
        show()

        openUriGate()
        confirmGateWith(WRONG_PASSWORD)

        compose.runOnIdle { assertEquals(emptyList(), clipboard.texts) }
    }

    @Test
    fun `a refused password leaves the gate on screen`() {
        show()

        openUriGate()
        confirmGateWith(WRONG_PASSWORD)

        compose.onNodeWithTag(DISCLOSURE_STATEMENT_TAG).assertIsDisplayed()
    }

    @Test
    fun `a refused password says the password did not open the vault`() {
        show()

        openUriGate()
        confirmGateWith(WRONG_PASSWORD)

        compose.onNodeWithText("That password did not open the vault.").assertIsDisplayed()
    }

    @Test
    fun `the accepted password puts the URI on the clipboard`() {
        show()

        openUriGate()
        confirmGateWith(RIGHT_PASSWORD)

        compose.runOnIdle { assertEquals(listOf(DISCLOSED_URI), clipboard.texts) }
    }

    @Test
    fun `the accepted password closes the gate`() {
        show()

        openUriGate()
        confirmGateWith(RIGHT_PASSWORD)

        compose.onNodeWithTag(DISCLOSURE_STATEMENT_TAG).assertDoesNotExist()
    }

    // A copied URI is a complete credential and takes the clipboard clear the clipboard is holding
    // for a code.
    @Test
    fun `a disclosed URI counts the clipboard down like a code`() {
        show()

        openUriGate()
        confirmGateWith(RIGHT_PASSWORD)

        compose.onNodeWithText("URI copied — the clipboard clears in $CLEAR_SECONDS s").assertIsDisplayed()
    }

    @Test
    fun `deleting names the account in the confirmation`() {
        show()

        openMenuOn(TOTP.id)
        compose.onNodeWithText(DELETE).performClick()

        compose.onNodeWithText("Delete GitHub — alice?").assertIsDisplayed()
    }

    @Test
    fun `a confirmed delete reports the account it named`() {
        show()

        openMenuOn(TOTP.id)
        compose.onNodeWithText(DELETE).performClick()
        compose.onNodeWithText(DELETE_CONFIRM).performClick()

        compose.runOnIdle { assertEquals(TOTP.id, deleted) }
    }

    // The confirmation is the whole protection on an irreversible action, so dismissing it has to
    // leave the account where it was.
    @Test
    fun `a dismissed delete reports nothing`() {
        show()

        openMenuOn(TOTP.id)
        compose.onNodeWithText(DELETE).performClick()
        compose.onNodeWithText(DELETE_CANCEL).performClick()

        compose.runOnIdle { assertEquals(null, deleted) }
    }

    @Test
    fun `an edit reports the account it was asked for`() {
        show()

        openMenuOn(TOTP.id)
        compose.onNodeWithText("Edit").performClick()

        compose.runOnIdle { assertEquals(TOTP.id, edited) }
    }

    // A drag well past the end of the list drops on the end, which is the position the vault is asked
    // to renumber to.
    @Test
    fun `a drag to the bottom reports the last position`() {
        show()

        dragToBottom(HOTP.id)

        compose.runOnIdle { assertEquals(HOTP.id to 1, moved) }
    }

    // The drag handle rewrites the stored order, and the other two orderings are views over it: a
    // drop in one of them would renumber by a position the list is not showing.
    @Test
    fun `no drag handle acts while the list is sorted by issuer`() {
        show(sortOrder = SortOrder.ISSUER)

        dragToBottom(TOTP.id)

        compose.runOnIdle { assertEquals(null, moved) }
    }

    // A filtered list is showing some of the vault while the index a drop yields is a position in all
    // of it, so a drag here would move the row somewhere nobody pointed at.
    @Test
    fun `no drag handle acts while a search is filtering the list`() {
        show()
        search("zend")

        dragToBottom(HOTP.id)

        compose.runOnIdle { assertEquals(null, moved) }
    }

    @Test
    fun `the drag handle acts again once the search is cleared`() {
        show()
        search("zend")
        compose.onNodeWithTag(SEARCH_TAG).performTextClearance()

        dragToBottom(HOTP.id)

        compose.runOnIdle { assertEquals(HOTP.id to 1, moved) }
    }

    private fun dragToBottom(id: String) {
        compose.onNodeWithTag(dragHandleTag(id)).performTouchInput {
            down(center)
            moveBy(Offset(0f, 200f))
            moveBy(Offset(0f, 2000f))
            up()
        }
    }

    private fun topOf(entry: UnlockedEntry) = compose.onNodeWithTag(accountRowTag(entry.id)).getBoundsInRoot().top

    private fun search(text: String) = compose.onNodeWithTag(SEARCH_TAG).performTextInput(text)

    // Every row carries the same overflow label, so the row is picked by its position in the stored
    // order, which puts Zendesk first.
    @Test
    fun `the overflow menu offers the qr code`() {
        show()

        openMenuOn(TOTP.id)

        compose.onNodeWithText(SHOW_QR).assertIsDisplayed()
    }

    @Test
    fun `showing the qr code asks for the password first`() {
        show()

        openQrGate()

        compose.onNodeWithTag(DISCLOSURE_STATEMENT_TAG).assertTextEquals(QR_DISCLOSURE_STATEMENT)
    }

    @Test
    fun `no code is drawn while the gate is open`() {
        show()

        openQrGate()

        compose.onNodeWithContentDescription(QR_SYMBOL).assertDoesNotExist()
    }

    @Test
    fun `a confirmed password draws the code`() {
        show()

        openQrGate()
        confirmGateWith(RIGHT_PASSWORD)

        compose.onNodeWithContentDescription(QR_SYMBOL).assertIsDisplayed()
    }

    // The symbol stands for whatever was encoded, and nothing about the drawing says which URI that
    // was, so what the screen handed the encoder is asserted rather than what it drew.
    @Test
    fun `the code drawn is the uri the vault disclosed`() {
        show()

        openQrGate()
        confirmGateWith(RIGHT_PASSWORD)

        compose.runOnIdle { assertEquals(DISCLOSED_URI, encodedText) }
    }

    @Test
    fun `a refused password draws no code`() {
        show()

        openQrGate()
        confirmGateWith(WRONG_PASSWORD)

        compose.onNodeWithContentDescription(QR_SYMBOL).assertDoesNotExist()
    }

    @Test
    fun `copying the uri from the dialog puts it on the clipboard`() {
        show()

        openQrGate()
        confirmGateWith(RIGHT_PASSWORD)
        compose.onNodeWithText(QR_COPY_URI).performClick()

        compose.runOnIdle { assertEquals(listOf(DISCLOSED_URI), clipboard.texts) }
    }

    // The file written has to be the symbol the user was looking at rather than a second encode of
    // the same account, which nothing about the drawing would reveal.
    @Test
    fun `saving hands over the symbol on screen`() {
        show()

        openQrGate()
        confirmGateWith(RIGHT_PASSWORD)
        compose.onNodeWithText(QR_SAVE).performClick()

        compose.runOnIdle { assertEquals(FAKE_SYMBOL, savedSymbol) }
    }

    @Test
    fun `a refused save is reported over the code`() {
        saveAnswer = Outcome.Failure(ExportError.NotRestricted)
        show()

        openQrGate()
        confirmGateWith(RIGHT_PASSWORD)
        compose.onNodeWithText(QR_SAVE).performClick()

        compose.onNodeWithTag(QR_SAVE_PROBLEM_TAG).assertIsDisplayed()
    }

    // The gate is typed at, so the input that answers it is what the idle watch is listening for.
    @Test
    fun `the gate in front of the code holds nothing off`() {
        show()

        openQrGate()

        compose.runOnIdle { assertEquals(emptyList(), idleHolds) }
    }

    @Test
    fun `a code on screen holds the idle lock off`() {
        show()

        openQrGate()
        confirmGateWith(RIGHT_PASSWORD)

        compose.runOnIdle { assertEquals(listOf(true), idleHolds) }
    }

    @Test
    fun `the code leaving the screen lets the idle lock back`() {
        show()

        openQrGate()
        confirmGateWith(RIGHT_PASSWORD)
        compose.onNodeWithText(QR_CLOSE).performClick()

        compose.runOnIdle { assertEquals(listOf(true, false), idleHolds) }
    }

    @Test
    fun `closing the dialog takes the code off the screen`() {
        show()

        openQrGate()
        confirmGateWith(RIGHT_PASSWORD)
        compose.onNodeWithText(QR_CLOSE).performClick()

        compose.onNodeWithContentDescription(QR_SYMBOL).assertDoesNotExist()
    }

    private fun openMenuOn(id: String) {
        compose.onAllNodesWithText(MENU)[if (id == HOTP.id) 0 else 1].performClick()
    }

    private fun openUriGate() {
        openMenuOn(TOTP.id)
        compose.onNodeWithText(COPY_URI).performClick()
    }

    private fun openQrGate() {
        openMenuOn(TOTP.id)
        compose.onNodeWithText(SHOW_QR).performClick()
    }

    // The search field is on screen too, so the gate's field is reached through the gate's own tag.
    private fun confirmGateWith(password: String) {
        compose.onNodeWithTag(DISCLOSURE_PASSWORD_TAG)
            .onChildren()
            .filterToOne(hasSetTextAction())
            .performTextInput(password)
        compose.onNodeWithText(DISCLOSE).performClick()
    }

    // One per branch of the mapping the list holds, which is every case a change to an entry reports.
    @Test
    fun `an account that is no longer there shows its own message`() {
        show(error = VaultError.NoSuchEntry)

        compose.onNodeWithText("That account is no longer in the vault.").assertIsDisplayed()
    }

    @Test
    fun `a refused value shows the rule it broke`() {
        show(error = VaultError.InvalidEntry("the counter is at its maximum"))

        compose.onNodeWithText("The change was refused: the counter is at its maximum.").assertIsDisplayed()
    }

    @Test
    fun `a lock before the write shows its own message`() {
        show(error = VaultError.VaultClosed)

        compose.onNodeWithText("The vault locked before the change was saved.").assertIsDisplayed()
    }

    @Test
    fun `a vault held by another process shows its own message`() {
        show(error = VaultError.LockedByAnotherProcess("vault.tauth.lock"))

        compose.onNodeWithText("Another TAuth process is holding the vault file.").assertIsDisplayed()
    }

    @Test
    fun `a failed write shows the write message`() {
        show(error = VaultError.Io(RuntimeException("no space")))

        compose.onNodeWithText("The vault file could not be written.").assertIsDisplayed()
    }

    @Test
    fun `a vault past the size the writer will produce shows its own message`() {
        show(error = VaultError.TooLarge(size = 2, limit = 1))

        compose.onNodeWithText("The vault is larger than the file format allows.").assertIsDisplayed()
    }

    @Test
    fun `a version the reader does not know shows its own message`() {
        show(error = VaultError.UnsupportedVersion(found = 2, supported = 1))

        compose.onNodeWithText("The vault file is in a format this version of TAuth does not read.")
            .assertIsDisplayed()
    }

    private fun show(
        entries: List<UnlockedEntry> = listOf(TOTP, HOTP),
        sortOrder: SortOrder = SortOrder.MANUAL,
        clearSeconds: Int = CLEAR_SECONDS,
        codes: Map<String, TotpCode> = mapOf(TOTP.id to TotpCode(TOTP_CODE, 30, 30)),
        height: Dp = Dp.Unspecified,
        error: EntryChangeError? = null,
    ) {
        compose.setContent {
            TauthTheme {
                AccountListScreen(
                    entries = entries,
                    codes = codes,
                    modifier = if (height == Dp.Unspecified) Modifier.fillMaxSize() else Modifier.height(height),
                    sortOrder = sortOrder,
                    clipboardClearSeconds = clearSeconds,
                    clipboard = clipboard,
                    qrEncoding = QrEncoding { text ->
                        encodedText = text
                        FAKE_SYMBOL
                    },
                    error = error,
                    onSortOrderChange = { chosenSort = it },
                    onVisibleChange = { visible = it },
                    onIdleLockSuppressed = { idleHolds += it },
                    onSaveQrImage = { symbol ->
                        savedSymbol = symbol
                        saveAnswer
                    },
                    onGenerate = {
                        generations++
                        generateAnswer
                    },
                    onDiscloseUri = { _, password ->
                        if (password.contentEquals(RIGHT_PASSWORD.toCharArray())) {
                            Outcome.Success(DISCLOSED_URI)
                        } else {
                            Outcome.Failure(VaultError.WrongPassword)
                        }
                    },
                    onMove = { id, index -> moved = id to index },
                    onDelete = { deleted = it },
                    onEdit = { edited = it },
                    onAdd = { adds++ },
                    onLock = { locks++ },
                )
            }
        }
    }
}
