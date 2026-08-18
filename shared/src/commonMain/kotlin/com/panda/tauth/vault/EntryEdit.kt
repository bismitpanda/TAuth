package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpCore

// The secret and the type are absent from this type rather than checked for, so no caller can ask to
// change either: a mistyped secret would destroy the only copy of a credential.
data class EntryEdit(
    val accountName: String,
    val issuer: String? = null,
    val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    val digits: Int = OtpCore.DIGITS_DEFAULT,
    val period: Int? = null,
    // The counter is editable because a client past the server's look-ahead window recovers only by
    // being set back or forward.
    val counter: ULong? = null,
)

// The entry model states its rules by refusing a value, and these values come from text fields, so
// the refusal is returned rather than left to escape as a throw.
internal fun VaultEntry.edited(edit: EntryEdit): Outcome<VaultEntry, VaultError.InvalidEntry> = try {
    Outcome.Success(
        copy(
            accountName = edit.accountName,
            issuer = edit.issuer,
            algorithm = edit.algorithm,
            digits = edit.digits,
            period = edit.period,
            counter = edit.counter,
        ),
    )
} catch (e: IllegalArgumentException) {
    // The model's messages state the rule rather than the value, and the value here is never the
    // secret: this edit carries none.
    Outcome.Failure(VaultError.InvalidEntry(e.message ?: "the edit does not make a valid entry"))
}
