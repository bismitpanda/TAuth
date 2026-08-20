package com.panda.tauth.ui.list

import androidx.compose.ui.graphics.Color
import com.panda.tauth.ui.theme.TauthColors

const val EXPIRING_SECONDS = 5

fun countdownColor(secondsRemaining: Int, colors: TauthColors): Color =
    if (secondsRemaining <= EXPIRING_SECONDS) colors.countdownExpiring else colors.countdown

fun isExpiring(secondsRemaining: Int): Boolean = secondsRemaining <= EXPIRING_SECONDS

fun countdownFraction(secondsRemaining: Int, period: Int): Float =
    if (period > 0) (secondsRemaining.toFloat() / period).coerceIn(0f, 1f) else 0f
