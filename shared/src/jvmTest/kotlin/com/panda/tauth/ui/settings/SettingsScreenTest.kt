package com.panda.tauth.ui.settings

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.panda.tauth.Outcome
import com.panda.tauth.settings.Preferences
import com.panda.tauth.settings.SecurityPolicy
import com.panda.tauth.settings.SortOrder
import com.panda.tauth.settings.Theme
import com.panda.tauth.ui.theme.TauthTheme
import com.panda.tauth.vault.ImportReadError
import com.panda.tauth.vault.VaultError
import com.panda.tauth.vault.VaultRewriteError
import org.junit.Rule
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// Every field off its default, so a control read against this disagrees with a screen that draws the
// defaults rather than what it was given.
private val STORED_POLICY = SecurityPolicy(
    idleTimeoutMinutes = 1,
    lockOnMinimise = false,
    lockOnFocusLoss = true,
    hideGraceSeconds = 30,
    clipboardClearSeconds = 10,
)

// Every field off its default here too, and off the ones the policy above carries.
private val STORED_PREFERENCES = Preferences(
    theme = Theme.DARK,
    sortOrder = SortOrder.RECENTLY_ADDED,
    startMinimised = true,
    minimiseToTray = false,
)

private const val PASSWORD = "correct horse battery staple"
private const val NEW_PASSWORD = "a rather different passphrase"
private const val SHORT_PASSWORD = "sixchr"

private const val VAULT_LOCATION = "/tmp/tauth-test/vault.tauth"
private const val VERSION = "9.9.9-test"
private const val LICENCE = "The licence this build carries"

// The distinction the screen states once, written out rather than read from the screen's own
// constant, so wording that stops making it fails this.
private const val HEADER_STATEMENT =
    "Appearance and tray settings are kept in a plaintext file that anything running as you can " +
        "rewrite. Everything governing when the vault locks is kept inside the vault, and changing " +
        "one of those needs your master password."

// What a note repeating the header would have to say for the plaintext file or the vault to be
// where a control's own wording places it.
private val PLACEMENT_WORDS = listOf("plaintext", "inside the vault", "kept in a")

private fun mentionsPlaintext(note: String): Boolean = PLACEMENT_WORDS.any { it in note.lowercase() }

// The screen's own wording, repeated here as literals so a changed label fails the test that names it
// rather than following it.
private const val CHANGE = "Change master password"
private const val REENCRYPT = "Re-encrypt vault"
private const val REVEAL = "Show in file manager"
private const val EXPORT = "Export an encrypted copy"
private const val BACK = "Back to accounts"
private const val DARK = "Dark"
private const val LIGHT = "Light"
private const val RECENTLY_ADDED = "Recently added"
private const val BY_ISSUER = "Issuer A–Z"

class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var chosenPolicy: SecurityPolicy? = null
    private var chosenTheme: Theme? = null
    private var chosenSortOrder: SortOrder? = null
    private var chosenMinimiseToTray: Boolean? = null
    private var chosenStartMinimised: Boolean? = null
    private var currentPassword: CharArray? = null
    private var newPassword: CharArray? = null
    private var rotationPassword: CharArray? = null
    private var exports = 0
    private var plaintextExports = 0
    private var importRequests = 0
    private var reveals = 0
    private var backs = 0

    @Test
    fun `the header states where each kind of setting is kept`() {
        show()

        compose.onNodeWithTag(SETTINGS_HEADER_TAG).assertTextContains(HEADER_STATEMENT)
    }

    // The distinction belongs to the header, and a control repeating it is a control that has to be
    // kept in step with the header when either is edited.
    @Test
    fun `the re-encryption note repeats nothing of the header`() {
        assertFalse(mentionsPlaintext(ROTATE_NOTE))
    }

    @Test
    fun `the export note repeats nothing of the header`() {
        assertFalse(mentionsPlaintext(EXPORT_NOTE))
    }

    @Test
    fun `the missing tray note repeats nothing of the header`() {
        assertFalse(mentionsPlaintext(NO_TRAY_NOTE))
    }

    @Test
    fun `the about note repeats nothing of the header`() {
        assertFalse(mentionsPlaintext(PROTECTS_NOTE))
    }

    @Test
    fun `the idle timeout it opens with is the one the policy carries`() {
        show()

        compose.onNodeWithText("1 min").assertIsSelected()
    }

    @Test
    fun `a chosen idle timeout is handed over with the rest of the policy unmoved`() {
        show()

        tap("15 min")

        compose.runOnIdle {
            assertEquals(
                SecurityPolicy(
                    idleTimeoutMinutes = 15,
                    lockOnMinimise = false,
                    lockOnFocusLoss = true,
                    hideGraceSeconds = 30,
                    clipboardClearSeconds = 10,
                ),
                chosenPolicy,
            )
        }
    }

    @Test
    fun `the minimise lock it opens with is the one the policy carries`() {
        show()

        compose.onNodeWithTag(MINIMISE_LOCK_TAG).assertIsOff()
    }

    @Test
    fun `a switched minimise lock is handed over with the rest of the policy unmoved`() {
        show()

        toggle(MINIMISE_LOCK_TAG)

        compose.runOnIdle {
            assertEquals(
                SecurityPolicy(
                    idleTimeoutMinutes = 1,
                    lockOnMinimise = true,
                    lockOnFocusLoss = true,
                    hideGraceSeconds = 30,
                    clipboardClearSeconds = 10,
                ),
                chosenPolicy,
            )
        }
    }

    @Test
    fun `the grace period it opens with is the one the policy carries`() {
        show()

        compose.onNodeWithText("30 s").assertIsSelected()
    }

    @Test
    fun `a chosen grace period is handed over with the rest of the policy unmoved`() {
        show()

        tap("2 min")

        compose.runOnIdle {
            assertEquals(
                SecurityPolicy(
                    idleTimeoutMinutes = 1,
                    lockOnMinimise = false,
                    lockOnFocusLoss = true,
                    hideGraceSeconds = 120,
                    clipboardClearSeconds = 10,
                ),
                chosenPolicy,
            )
        }
    }

    @Test
    fun `the focus loss lock it opens with is the one the policy carries`() {
        show()

        compose.onNodeWithTag(FOCUS_LOSS_TAG).assertIsOn()
    }

    @Test
    fun `a switched focus loss lock is handed over with the rest of the policy unmoved`() {
        show()

        toggle(FOCUS_LOSS_TAG)

        compose.runOnIdle {
            assertEquals(
                SecurityPolicy(
                    idleTimeoutMinutes = 1,
                    lockOnMinimise = false,
                    lockOnFocusLoss = false,
                    hideGraceSeconds = 30,
                    clipboardClearSeconds = 10,
                ),
                chosenPolicy,
            )
        }
    }

    @Test
    fun `the clipboard delay it opens with is the one the policy carries`() {
        show()

        compose.onNodeWithText("10 s").assertIsSelected()
    }

    @Test
    fun `a chosen clipboard delay is handed over with the rest of the policy unmoved`() {
        show()

        tap("60 s")

        compose.runOnIdle {
            assertEquals(
                SecurityPolicy(
                    idleTimeoutMinutes = 1,
                    lockOnMinimise = false,
                    lockOnFocusLoss = true,
                    hideGraceSeconds = 30,
                    clipboardClearSeconds = 60,
                ),
                chosenPolicy,
            )
        }
    }

    // The control reflects the policy it is given and holds none of its own, so a write the vault
    // refused leaves it where it stood.
    @Test
    fun `a policy the caller did not move leaves the control where it stood`() {
        show(error = VaultError.LockedByAnotherProcess("vault.lock"))

        tap("15 min")

        compose.onNodeWithText("1 min").assertIsSelected()
    }

    @Test
    fun `a refused policy write is reported on the screen`() {
        show(error = VaultError.LockedByAnotherProcess("vault.lock"))

        compose.onNodeWithTag(SETTINGS_PROBLEM_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the theme it opens with is the one stored`() {
        show()

        compose.onNodeWithText(DARK).assertIsSelected()
    }

    @Test
    fun `a chosen theme is handed over`() {
        show()

        tap(LIGHT)

        compose.runOnIdle { assertEquals(Theme.LIGHT, chosenTheme) }
    }

    @Test
    fun `the account order it opens with is the one stored`() {
        show()

        compose.onNodeWithText(RECENTLY_ADDED).assertIsSelected()
    }

    @Test
    fun `a chosen account order is handed over`() {
        show()

        tap(BY_ISSUER)

        compose.runOnIdle { assertEquals(SortOrder.ISSUER, chosenSortOrder) }
    }

    @Test
    fun `the tray preference it opens with is the one stored`() {
        show()

        compose.onNodeWithTag(MINIMISE_TO_TRAY_TAG).assertIsOff()
    }

    @Test
    fun `a switched tray preference is handed over`() {
        show()

        toggle(MINIMISE_TO_TRAY_TAG)

        compose.runOnIdle { assertEquals(true, chosenMinimiseToTray) }
    }

    @Test
    fun `the start preference it opens with is the one stored`() {
        show()

        compose.onNodeWithTag(START_MINIMISED_TAG).assertIsOn()
    }

    @Test
    fun `a switched start preference is handed over`() {
        show()

        toggle(START_MINIMISED_TAG)

        compose.runOnIdle { assertEquals(false, chosenStartMinimised) }
    }

    @Test
    fun `a desktop with no tray cannot switch the tray preference`() {
        show(shell = shellSettings(canConfigureTray = false))

        compose.onNodeWithTag(MINIMISE_TO_TRAY_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a desktop with no tray cannot switch the start preference`() {
        show(shell = shellSettings(canConfigureTray = false))

        compose.onNodeWithTag(START_MINIMISED_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a desktop with no tray says why the tray settings are refused`() {
        show(shell = shellSettings(canConfigureTray = false))

        compose.onNodeWithText(NO_TRAY_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a desktop with a tray offers the tray preference`() {
        show(shell = shellSettings(canConfigureTray = true))

        compose.onNodeWithTag(MINIMISE_TO_TRAY_TAG).assertIsEnabled()
    }

    @Test
    fun `a desktop with a tray says nothing about a missing one`() {
        show(shell = shellSettings(canConfigureTray = true))

        compose.onNodeWithText(NO_TRAY_NOTE).assertDoesNotExist()
    }

    @Test
    fun `the vault file location is the one the shell reports`() {
        show()

        compose.onNodeWithTag(SETTINGS_LOCATION_TAG).assertTextContains(VAULT_LOCATION)
    }

    @Test
    fun `the reveal control reports what it was pressed for`() {
        show()

        tap(REVEAL)

        compose.runOnIdle { assertEquals(1, reveals) }
    }

    // The vault is untouched by a failed export, and a message about the vault file sends the user
    // looking at the wrong one.
    @Test
    fun `a destination that cannot restrict the copy is reported as the destination`() {
        show(exportError = ExportError.NotRestricted)

        compose.onNodeWithText(
            "That location cannot keep the copy to you alone, so nothing was written there. " +
                "The vault is unchanged.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a destination that could not be written is reported as the destination`() {
        show(exportError = ExportError.Io(IOException("read-only file system")))

        compose.onNodeWithText("The copy could not be written to that location. The vault is unchanged.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // An export reads the vault and writes nowhere near it, so nothing it reports may claim a write
    // was attempted on the vault file.
    @Test
    fun `a vault that cannot be read is reported as a read`() {
        show(exportError = ExportError.VaultUnreadable(VaultError.Io(IOException("no such device"))))

        compose.onNodeWithText("No copy was made: the vault file could not be read.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a damaged vault is reported without claiming a write`() {
        show(exportError = ExportError.VaultUnreadable(VaultError.Corrupt("the body tag did not verify")))

        compose.onNodeWithText("No copy was made: the vault file is damaged.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a missing vault file is reported as missing`() {
        show(exportError = ExportError.VaultUnreadable(VaultError.NoVaultFile))

        compose.onNodeWithText("No copy was made: there is no vault file at this location.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the unencrypted export reports the request`() {
        show()

        compose.onNodeWithText("Export accounts unencrypted").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(1, plaintextExports) }
    }

    // Two exports over two destinations: neither message may name the other's file, and the copy of
    // the vault is the only one of the two with a vault to say is unchanged.
    @Test
    fun `a destination that cannot restrict the accounts is reported as the destination`() {
        show(plaintextError = ExportError.NotRestricted)

        compose.onNodeWithText("That location cannot keep the accounts to you alone, so nothing was written there.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a destination that could not take the accounts is reported as the destination`() {
        show(plaintextError = ExportError.Io(IOException("read-only file system")))

        compose.onNodeWithText("The accounts could not be written to that location.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the import reports the request`() {
        show()

        compose.onNodeWithText("Import accounts").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(1, importRequests) }
    }

    // A read that failed opens no preview, so what it reports is reported here. Nothing about the
    // vault reaches this: the file is one TAuth did not necessarily write.
    @Test
    fun `a file that is not an export is reported where it was chosen`() {
        show(importError = VaultError.Corrupt("this file is not an export TAuth wrote"))

        compose.onNodeWithText("Nothing was imported: this file is not an export TAuth wrote.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a file that could not be read is reported as the file`() {
        show(importError = VaultError.Io(IOException("permission denied")))

        compose.onNodeWithText("That file could not be read, so nothing was imported.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a vault locked before the read says nothing was imported`() {
        show(importError = VaultError.VaultClosed)

        compose.onNodeWithText("The vault locked before the file was read, so nothing was imported.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a refused import leaves the two export slots empty`() {
        show(importError = VaultError.VaultClosed)

        compose.onNodeWithTag(SETTINGS_EXPORT_PROBLEM_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SETTINGS_PLAINTEXT_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `a refused export leaves the import's slot empty`() {
        show(exportError = ExportError.NotRestricted)

        compose.onNodeWithTag(SETTINGS_IMPORT_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `a refused plaintext export leaves the import's slot empty`() {
        show(plaintextError = ExportError.NotRestricted)

        compose.onNodeWithTag(SETTINGS_IMPORT_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `a refused import leaves the unencrypted export's slot empty`() {
        show(importError = VaultError.VaultClosed)

        compose.onNodeWithTag(SETTINGS_PLAINTEXT_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `a refused plaintext export leaves the vault copy's slot empty`() {
        show(plaintextError = ExportError.NotRestricted)

        compose.onNodeWithTag(SETTINGS_EXPORT_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `a refused vault copy leaves the plaintext slot empty`() {
        show(exportError = ExportError.NotRestricted)

        compose.onNodeWithTag(SETTINGS_PLAINTEXT_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `a refused export leaves the vault's own slot empty`() {
        show(exportError = ExportError.NotRestricted)

        compose.onNodeWithTag(SETTINGS_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `a refused vault write leaves the export's slot empty`() {
        show(error = VaultError.LockedByAnotherProcess("vault.lock"))

        compose.onNodeWithTag(SETTINGS_EXPORT_PROBLEM_TAG).assertDoesNotExist()
    }

    @Test
    fun `the export control reports what it was pressed for`() {
        show()

        tap(EXPORT)

        compose.runOnIdle { assertEquals(1, exports) }
    }

    @Test
    fun `the version is the one the shell reports`() {
        show()

        compose.onNodeWithText("$VERSION_LABEL: $VERSION").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the licence is the one the shell reports`() {
        show()

        compose.onNodeWithText("$LICENCE_LABEL: $LICENCE").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the about group states what the vault protects`() {
        show()

        compose.onNodeWithText(PROTECTS_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the about group states what the vault does not protect`() {
        show()

        compose.onNodeWithText(PROTECTS_NOT_NOTE).performScrollTo().assertIsDisplayed()
    }

    // The sentence is written out rather than read from the screen's own constant, so a wording that
    // stopped naming the clock or the consequence would fail this.
    @Test
    fun `the about group states that a skewed clock produces rejected codes`() {
        show()

        compose.onNodeWithText(
            "Codes are read off this computer's clock, which TAuth does not correct against a time " +
                "server. A clock out by more than an account's period produces codes the other side " +
                "rejects.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a password change is held back until a current password is typed`() {
        show()

        type(NEW_PASSWORD_TAG, NEW_PASSWORD)
        type(CONFIRM_PASSWORD_TAG, NEW_PASSWORD)

        compose.onNodeWithText(CHANGE).assertIsNotEnabled()
    }

    @Test
    fun `a password change is held back while the two new passwords differ`() {
        show()

        type(CURRENT_PASSWORD_TAG, PASSWORD)
        type(NEW_PASSWORD_TAG, NEW_PASSWORD)
        type(CONFIRM_PASSWORD_TAG, "$NEW_PASSWORD ")

        compose.onNodeWithText(CHANGE).assertIsNotEnabled()
    }

    @Test
    fun `a new password below the minimum length holds the change back`() {
        show()

        type(CURRENT_PASSWORD_TAG, PASSWORD)
        type(NEW_PASSWORD_TAG, SHORT_PASSWORD)
        type(CONFIRM_PASSWORD_TAG, SHORT_PASSWORD)

        compose.onNodeWithText(CHANGE).assertIsNotEnabled()
    }

    @Test
    fun `a password change hands over the current password that was typed`() {
        show()

        changePassword()

        compose.runOnIdle { assertContentEquals(PASSWORD.toCharArray(), currentPassword) }
    }

    @Test
    fun `a password change hands over the new password that was typed`() {
        show()

        changePassword()

        compose.runOnIdle { assertContentEquals(NEW_PASSWORD.toCharArray(), newPassword) }
    }

    @Test
    fun `a wrong current password is reported on the screen`() {
        show(error = VaultError.WrongPassword)

        compose.onNodeWithText("That password did not open the vault, so nothing was changed.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // The remaining branches of the mapping this screen holds, which is every case a rewrite of the
    // whole file reports.
    @Test
    fun `a lock during the change is reported without claiming where it stands`() {
        show(error = VaultError.VaultClosed)

        compose.onNodeWithText("The vault locked during the change. Unlock to see where it stands.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a vault held by another process shows its own message`() {
        show(error = VaultError.LockedByAnotherProcess("vault.lock"))

        compose.onNodeWithText("Another TAuth process is holding the vault file.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // A rewrite reads and writes, so the sentence names neither half on its own.
    @Test
    fun `a failed read or write says it could be either`() {
        show(error = VaultError.Io(IOException("no such device")))

        compose.onNodeWithText("The vault file could not be read or written.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a vault past the size the writer will produce shows its own message`() {
        show(error = VaultError.TooLarge(size = 2, limit = 1))

        compose.onNodeWithText("The vault is larger than the file format allows.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a structure that does not parse shows the damaged-file message`() {
        show(error = VaultError.Corrupt("header checksum does not match the header"))

        compose.onNodeWithText("The vault file is damaged.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a failed tag shows the damaged-file message`() {
        show(error = VaultError.IntegrityFailure)

        compose.onNodeWithText("The vault file is damaged.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a secret that will not decode shows the damaged-file message`() {
        show(error = VaultError.InvalidSecret("invalid base32 character"))

        compose.onNodeWithText("The vault file is damaged.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a version the reader does not know shows its own message`() {
        show(error = VaultError.UnsupportedVersion(found = 2, supported = 1))

        compose.onNodeWithText("The vault file is in a format this version of TAuth does not read.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a vault file that is not there shows its own message`() {
        show(error = VaultError.NoVaultFile)

        compose.onNodeWithText("There is no vault file at this location.").performScrollTo().assertIsDisplayed()
    }

    // One means retype and the other means the file, and neither may stand in for the other.
    @Test
    fun `a damaged file does not show the password message`() {
        show(error = VaultError.Corrupt("header checksum does not match the header"))

        compose.onNodeWithText("That password did not open the vault, so nothing was changed.").assertDoesNotExist()
    }

    @Test
    fun `a wrong password does not show the damaged-file message`() {
        show(error = VaultError.WrongPassword)

        compose.onNodeWithText("The vault file is damaged.").assertDoesNotExist()
    }

    @Test
    fun `a re-encryption is held back until a password is typed`() {
        show()

        compose.onNodeWithText(REENCRYPT).assertIsNotEnabled()
    }

    @Test
    fun `a re-encryption hands over the password that was typed`() {
        show()

        type(ROTATE_PASSWORD_TAG, PASSWORD)
        tap(REENCRYPT)

        compose.runOnIdle { assertContentEquals(PASSWORD.toCharArray(), rotationPassword) }
    }

    @Test
    fun `a running derivation holds the timeout choices`() {
        show(isBusy = true)

        compose.onNodeWithText("15 min").assertIsNotEnabled()
    }

    @Test
    fun `a running derivation holds the locking switches`() {
        show(isBusy = true)

        compose.onNodeWithTag(FOCUS_LOSS_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a running derivation holds the export`() {
        show(isBusy = true)

        compose.onNodeWithText(EXPORT).assertIsNotEnabled()
    }

    @Test
    fun `leaving reports that it was asked for`() {
        show()

        tap(BACK)

        compose.runOnIdle { assertEquals(1, backs) }
    }

    private fun changePassword() {
        type(CURRENT_PASSWORD_TAG, PASSWORD)
        type(NEW_PASSWORD_TAG, NEW_PASSWORD)
        type(CONFIRM_PASSWORD_TAG, NEW_PASSWORD)
        tap(CHANGE)
    }

    // The screen scrolls, and a control below the fold takes a click that lands nowhere unless it is
    // brought into view first.
    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun toggle(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun type(tag: String, text: String) = field(tag).performScrollTo().performTextInput(text)

    // The tag names the field's own row, and the editable node is inside it.
    private fun field(tag: String): SemanticsNodeInteraction =
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))

    private fun shellSettings(canConfigureTray: Boolean) = ShellSettings(
        vaultLocation = VAULT_LOCATION,
        version = VERSION,
        licence = LICENCE,
        canConfigureTray = canConfigureTray,
        onReveal = { reveals++ },
        onExport = { Outcome.Success(Unit) },
    )

    private fun show(
        policy: SecurityPolicy = STORED_POLICY,
        preferences: Preferences = STORED_PREFERENCES,
        shell: ShellSettings = shellSettings(canConfigureTray = true),
        isBusy: Boolean = false,
        error: VaultRewriteError? = null,
        exportError: VaultExportError? = null,
        plaintextError: FileWriteError? = null,
        importError: ImportReadError? = null,
    ) {
        compose.setContent {
            TauthTheme {
                SettingsScreen(
                    policy = policy,
                    preferences = preferences,
                    shell = shell,
                    isBusy = isBusy,
                    error = error,
                    exportError = exportError,
                    plaintextError = plaintextError,
                    importError = importError,
                    onPolicyChange = { chosenPolicy = it },
                    onThemeChange = { chosenTheme = it },
                    onSortOrderChange = { chosenSortOrder = it },
                    onMinimiseToTrayChange = { chosenMinimiseToTray = it },
                    onStartMinimisedChange = { chosenStartMinimised = it },
                    onChangePassword = { current, next ->
                        currentPassword = current.copyOf()
                        newPassword = next.copyOf()
                    },
                    onRotate = { rotationPassword = it.copyOf() },
                    onExport = { exports++ },
                    onPlaintextExport = { plaintextExports++ },
                    onImport = { importRequests++ },
                    onBack = { backs++ },
                )
            }
        }
    }
}
