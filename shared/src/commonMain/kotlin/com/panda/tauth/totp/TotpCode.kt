package com.panda.tauth.totp

// The period travels with the code because a countdown drawn as a fraction needs the one the code was
// generated under rather than the one the row happens to hold.
data class TotpCode(val code: String, val secondsRemaining: Int, val period: Int)
