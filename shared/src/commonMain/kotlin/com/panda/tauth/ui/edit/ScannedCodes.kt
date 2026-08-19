package com.panda.tauth.ui.edit

import com.panda.tauth.Outcome
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.vault.ImageReadError

// One image can carry several codes, and a code can be anything at all: a payment link, a wireless
// password, a URL. Only the accounts among them are offered.
fun accountsIn(payloads: List<String>): List<OtpAuthUri> =
    payloads.mapNotNull { (OtpAuthUri.parse(it) as? Outcome.Success)?.value }

// The identity a scanned account is chosen by. The secret it carries is not part of it, since a
// selection list is on screen for as long as the user takes to read it.
fun scannedLabel(uri: OtpAuthUri): String = uri.issuer?.let { "$it — ${uri.accountName}" } ?: uri.accountName

// The payloads of every code in an image the user chose, or nothing where they chose none. Reading
// the image belongs to the shell; what the codes mean belongs here.
fun interface QrScanning {
    suspend fun scan(): Outcome<List<String>?, ImageReadError>
}
