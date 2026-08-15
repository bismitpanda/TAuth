package com.panda.tauth.ui.list

import androidx.compose.ui.graphics.Color
import com.panda.tauth.ui.theme.TauthColors

// A code copied inside this many seconds of the boundary is likely to have expired by the time it is
// typed into another window, so the ring changes colour.
const val EXPIRING_SECONDS = 5

// The one place the two countdown colours are chosen between, so the ring's colour and whatever else
// reports the same state cannot disagree about where the boundary is.
fun countdownColor(secondsRemaining: Int, colors: TauthColors): Color =
    if (secondsRemaining <= EXPIRING_SECONDS) colors.countdownExpiring else colors.countdown

// How much of the ring is left to sweep. The period is the one the code was generated under, so two
// accounts at the same reading draw different arcs when their periods differ.
fun countdownFraction(secondsRemaining: Int, period: Int): Float =
    if (period > 0) secondsRemaining.toFloat() / period else 0f
