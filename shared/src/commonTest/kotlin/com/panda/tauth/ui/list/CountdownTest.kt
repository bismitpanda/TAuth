package com.panda.tauth.ui.list

import com.panda.tauth.ui.theme.DarkTauthColors
import com.panda.tauth.ui.theme.LightTauthColors
import kotlin.test.Test
import kotlin.test.assertEquals

// The boundary is stated here as the literal five seconds rather than read from the constant the
// function uses, so a change to that constant fails this rather than following it.
private const val EXPIRING_BOUNDARY = 5

class CountdownTest {
    @Test
    fun `a code with a whole period left runs in the running colour`() {
        assertEquals(LightTauthColors.countdown, countdownColor(30, LightTauthColors))
    }

    @Test
    fun `a code one second above the boundary is still running`() {
        assertEquals(LightTauthColors.countdown, countdownColor(EXPIRING_BOUNDARY + 1, LightTauthColors))
    }

    @Test
    fun `a code on the boundary is expiring`() {
        assertEquals(LightTauthColors.countdownExpiring, countdownColor(EXPIRING_BOUNDARY, LightTauthColors))
    }

    @Test
    fun `a code with one second left is expiring`() {
        assertEquals(LightTauthColors.countdownExpiring, countdownColor(1, LightTauthColors))
    }

    // The sweep is the entry's own period, not the default. Two accounts at the same reading draw
    // different arcs, which is the whole reason a code carries the period it was generated under.
    @Test
    fun `a thirty-second account halfway through its period fills half the ring`() {
        assertEquals(0.5f, countdownFraction(secondsRemaining = 15, period = 30))
    }

    @Test
    fun `a sixty-second account at the same reading fills a quarter of the ring`() {
        assertEquals(0.25f, countdownFraction(secondsRemaining = 15, period = 60))
    }

    @Test
    fun `a code with its whole period left fills the ring`() {
        assertEquals(1f, countdownFraction(secondsRemaining = 30, period = 30))
    }

    // A totp entry carries no period only where the model would already have refused it, and a
    // division by it would be by zero.
    @Test
    fun `a period of nothing fills nothing`() {
        assertEquals(0f, countdownFraction(secondsRemaining = 15, period = 0))
    }

    // The colours are read from whichever set the theme provides, so the dark set has to reach the
    // ring rather than the light one being baked in.
    @Test
    fun `the dark set supplies its own expiring colour`() {
        assertEquals(DarkTauthColors.countdownExpiring, countdownColor(EXPIRING_BOUNDARY, DarkTauthColors))
    }

    @Test
    fun `the dark set supplies its own running colour`() {
        assertEquals(DarkTauthColors.countdown, countdownColor(30, DarkTauthColors))
    }
}
