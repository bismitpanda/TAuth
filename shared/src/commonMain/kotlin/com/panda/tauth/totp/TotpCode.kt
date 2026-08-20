package com.panda.tauth.totp

data class TotpCode(val code: String, val secondsRemaining: Int, val period: Int)
