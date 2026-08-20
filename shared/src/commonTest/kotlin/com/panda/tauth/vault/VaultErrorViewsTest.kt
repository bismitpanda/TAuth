package com.panda.tauth.vault

import kotlin.test.Test
import kotlin.test.assertEquals

// Each view is asserted as a whole set, so a membership added to a case fails as well as one dropped.
class VaultErrorViewsTest {
    @Test
    fun `a read reports absence, an unreadable file and a file past the reader's ceiling`() {
        assertEquals(setOf("NoVaultFile", "Io", "Corrupt"), namesIn<VaultReadError>())
    }

    @Test
    fun `a write reports an io failure and another process holding the vault`() {
        assertEquals(setOf("Io", "LockedByAnotherProcess"), namesIn<VaultWriteError>())
    }

    @Test
    fun `an open reports a wrong password apart from the three ways a file is damaged`() {
        assertEquals(
            setOf("WrongPassword", "IntegrityFailure", "Corrupt", "UnsupportedVersion"),
            namesIn<VaultOpenError>(),
        )
    }

    @Test
    fun `assembling a file reports the reader's own limits and nothing about a vault`() {
        assertEquals(setOf("UnsupportedVersion", "TooLarge"), namesIn<VaultAssembleError>())
    }

    @Test
    fun `an encode reports the reader's own limits and a vault closed under it`() {
        assertEquals(setOf("UnsupportedVersion", "TooLarge", "VaultClosed"), namesIn<VaultEncodeError>())
    }

    @Test
    fun `reopening and writing back reports an open and an encode and neither a read nor a write`() {
        assertEquals(
            setOf("WrongPassword", "IntegrityFailure", "Corrupt", "UnsupportedVersion", "TooLarge", "VaultClosed"),
            namesIn<VaultReencodeError>(),
        )
    }

    @Test
    fun `a password check reports the password and a header that does not decode`() {
        assertEquals(setOf("WrongPassword", "Corrupt"), namesIn<PasswordCheckError>())
    }

    @Test
    fun `adopting a body reports a secret that does not decode, a repeated id and a lock`() {
        assertEquals(setOf("InvalidSecret", "Corrupt", "VaultClosed"), namesIn<VaultAdoptError>())
    }

    @Test
    fun `a creation reports the path already holding a vault, a write, a read, an open and an adopt`() {
        assertEquals(
            setOf(
                "VaultFileExists",
                "NoVaultFile",
                "Io",
                "LockedByAnotherProcess",
                "UnsupportedVersion",
                "TooLarge",
                "WrongPassword",
                "IntegrityFailure",
                "Corrupt",
                "InvalidSecret",
                "VaultClosed",
            ),
            namesIn<VaultCreateError>(),
        )
    }

    @Test
    fun `an unlock reports a read, an open and an adopt, and neither a held lock nor a size`() {
        assertEquals(
            setOf(
                "NoVaultFile",
                "Io",
                "Corrupt",
                "UnsupportedVersion",
                "WrongPassword",
                "IntegrityFailure",
                "InvalidSecret",
                "VaultClosed",
            ),
            namesIn<VaultUnlockError>(),
        )
    }

    @Test
    fun `a rewrite reports every step of one, from the read through to the adopt`() {
        assertEquals(
            setOf(
                "NoVaultFile",
                "Io",
                "Corrupt",
                "LockedByAnotherProcess",
                "UnsupportedVersion",
                "WrongPassword",
                "IntegrityFailure",
                "TooLarge",
                "InvalidSecret",
                "VaultClosed",
            ),
            namesIn<VaultRewriteError>(),
        )
    }

    @Test
    fun `a commit reports what encoding a body and putting it on disk report and nothing else`() {
        assertEquals(
            setOf("UnsupportedVersion", "TooLarge", "VaultClosed", "Io", "LockedByAnotherProcess"),
            namesIn<VaultCommitError>(),
        )
    }

    @Test
    fun `storing a new entry reports a commit, the values it refused and its secret`() {
        assertEquals(
            setOf(
                "InvalidSecret",
                "InvalidEntry",
                "UnsupportedVersion",
                "TooLarge",
                "VaultClosed",
                "Io",
                "LockedByAnotherProcess",
            ),
            namesIn<EntryAddError>(),
        )
    }

    @Test
    fun `changing an entry reports a commit, the entry it named and the values it refused`() {
        assertEquals(
            setOf(
                "NoSuchEntry",
                "InvalidEntry",
                "UnsupportedVersion",
                "TooLarge",
                "VaultClosed",
                "Io",
                "LockedByAnotherProcess",
            ),
            namesIn<EntryChangeError>(),
        )
    }

    @Test
    fun `an entry write reports the entry it named, the values it refused, an encode and a write`() {
        assertEquals(
            setOf(
                "NoSuchEntry",
                "InvalidEntry",
                "InvalidSecret",
                "UnsupportedVersion",
                "TooLarge",
                "VaultClosed",
                "Io",
                "LockedByAnotherProcess",
            ),
            namesIn<EntryWriteError>(),
        )
    }

    @Test
    fun `the check at the gate reports the password, a header that does not decode and a lock`() {
        assertEquals(setOf("WrongPassword", "Corrupt", "VaultClosed"), namesIn<PasswordGateError>())
    }

    @Test
    fun `looking an entry up reports the entry and a lock and nothing about a password`() {
        assertEquals(setOf("NoSuchEntry", "VaultClosed"), namesIn<EntryLookupError>())
    }

    @Test
    fun `a disclosure reports the check and the entry, and nothing about the file on disk`() {
        assertEquals(setOf("WrongPassword", "Corrupt", "VaultClosed", "NoSuchEntry"), namesIn<DiscloseError>())
    }

    @Test
    fun `parsing a URI reports its shape and its secret and nothing the entry model holds`() {
        assertEquals(setOf("MalformedUri", "InvalidSecret"), namesIn<UriParseError>())
    }

    @Test
    fun `a draft reports the three ways typed or pasted values fail to make an account`() {
        assertEquals(setOf("MalformedUri", "InvalidSecret", "InvalidEntry"), namesIn<DraftError>())
    }

    // The file offered is one TAuth did not necessarily write, so one that is not an export is the
    // damage `Corrupt` names. A line that will not read is a row of the preview rather than a case.
    @Test
    fun `reading an import reports a damaged file, one that would not be read, and a vault closed`() {
        assertEquals(setOf("Corrupt", "Io", "VaultClosed"), namesIn<ImportReadError>())
    }

    // Reading an image names no vault: the account a code holds is not stored by being found, so a
    // vault closed under it is not one of the cases it reports.
    @Test
    fun `reading an image reports the image and nothing about a vault`() {
        assertEquals(setOf("Corrupt", "Io"), namesIn<ImageReadError>())
    }

    @Test
    fun `every case belongs to at least one operation view`() {
        val covered = namesIn<VaultCreateError>() + namesIn<VaultUnlockError>() + namesIn<VaultRewriteError>() +
            namesIn<EntryWriteError>() + namesIn<DiscloseError>() + namesIn<DraftError>()
        assertEquals(ALL_CASES.map(::nameOf).toSet(), covered)
    }

    // A case added stops nameOf compiling; naming it there then fails this count until it joins the list.
    @Test
    fun `the hierarchy holds fourteen cases and the list measured over them holds all of them`() {
        assertEquals(14, ALL_CASES.size)
        assertEquals(ALL_CASES.map(::nameOf), ALL_CASES.map { it::class.simpleName })
    }
}

private val ALL_CASES: List<VaultError> = listOf(
    VaultError.NoVaultFile,
    VaultError.VaultFileExists,
    VaultError.WrongPassword,
    VaultError.IntegrityFailure,
    VaultError.Corrupt("a header field is missing, malformed or the wrong size"),
    VaultError.UnsupportedVersion(found = 2, supported = 1),
    VaultError.InvalidSecret("invalid base32 character"),
    VaultError.MalformedUri("not an otpauth URI"),
    VaultError.NoSuchEntry,
    VaultError.InvalidEntry("digits must be a whole number"),
    VaultError.VaultClosed,
    VaultError.TooLarge(size = 2, limit = 1),
    VaultError.Io(Throwable("the vault could not be read")),
    VaultError.LockedByAnotherProcess("/vault.lock"),
)

private inline fun <reified V : VaultError> namesIn(): Set<String> =
    ALL_CASES.filterIsInstance<V>().map { it::class.simpleName.orEmpty() }.toSet()

private fun nameOf(error: VaultError): String = when (error) {
    is VaultError.NoVaultFile -> "NoVaultFile"
    is VaultError.VaultFileExists -> "VaultFileExists"
    is VaultError.WrongPassword -> "WrongPassword"
    is VaultError.IntegrityFailure -> "IntegrityFailure"
    is VaultError.Corrupt -> "Corrupt"
    is VaultError.UnsupportedVersion -> "UnsupportedVersion"
    is VaultError.InvalidSecret -> "InvalidSecret"
    is VaultError.MalformedUri -> "MalformedUri"
    is VaultError.NoSuchEntry -> "NoSuchEntry"
    is VaultError.InvalidEntry -> "InvalidEntry"
    is VaultError.VaultClosed -> "VaultClosed"
    is VaultError.TooLarge -> "TooLarge"
    is VaultError.Io -> "Io"
    is VaultError.LockedByAnotherProcess -> "LockedByAnotherProcess"
}
