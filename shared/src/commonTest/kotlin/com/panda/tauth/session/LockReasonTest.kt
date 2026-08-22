package com.panda.tauth.session

import com.panda.tauth.settings.SecurityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Every duration differs from every other, so a reason reading the wrong field of the policy answers
// with a number no assertion below accepts.
private val POLICY = SecurityPolicy(
    idleTimeoutMinutes = 7,
    lockOnMinimize = true,
    lockOnFocusLoss = true,
    hideGraceSeconds = 30,
    clipboardClearSeconds = 20,
)

class LockReasonTest {
    @Test
    fun `hiding to the tray is armed whatever the policy says`() {
        assertTrue(LockReason.HiddenToTray.isArmedBy(POLICY.copy(lockOnMinimize = false)))
    }

    @Test
    fun `a manual lock is armed whatever the policy says`() {
        assertTrue(LockReason.Manual.isArmedBy(POLICY.copy(lockOnMinimize = false, lockOnFocusLoss = false)))
    }

    @Test
    fun `an exit is armed whatever the policy says`() {
        assertTrue(LockReason.Exit.isArmedBy(POLICY.copy(lockOnMinimize = false, lockOnFocusLoss = false)))
    }

    @Test
    fun `minimizing is armed when the policy sets it`() {
        assertTrue(LockReason.Minimized.isArmedBy(POLICY.copy(lockOnMinimize = true)))
    }

    @Test
    fun `minimizing is disarmed when the policy clears it`() {
        assertFalse(LockReason.Minimized.isArmedBy(POLICY.copy(lockOnMinimize = false)))
    }

    @Test
    fun `losing focus is armed when the policy sets it`() {
        assertTrue(LockReason.FocusLost.isArmedBy(POLICY.copy(lockOnFocusLoss = true)))
    }

    @Test
    fun `losing focus is disarmed when the policy clears it`() {
        assertFalse(LockReason.FocusLost.isArmedBy(POLICY.copy(lockOnFocusLoss = false)))
    }

    @Test
    fun `going idle is armed by a timeout the policy names`() {
        assertTrue(LockReason.Idle.isArmedBy(POLICY.copy(idleTimeoutMinutes = 1)))
    }

    @Test
    fun `going idle is disarmed by a timeout of zero`() {
        assertFalse(LockReason.Idle.isArmedBy(POLICY.copy(idleTimeoutMinutes = 0)))
    }

    @Test
    fun `hiding to the tray waits the grace period`() {
        assertEquals(30, LockReason.HiddenToTray.graceSeconds(POLICY))
    }

    @Test
    fun `minimizing waits the grace period`() {
        assertEquals(30, LockReason.Minimized.graceSeconds(POLICY))
    }

    @Test
    fun `a manual lock waits for nothing`() {
        assertEquals(0, LockReason.Manual.graceSeconds(POLICY))
    }

    // Closing a dialog leaves the window unfocused until the desktop hands the focus back, and a
    // trigger that fired on that transition would lock the vault on every file the user chose.
    @Test
    fun `losing focus waits for the focus to settle`() {
        assertEquals(FOCUS_SETTLE_SECONDS, LockReason.FocusLost.graceSeconds(POLICY))
    }

    @Test
    fun `the focus settles in the time a desktop takes to hand it back`() {
        assertEquals(1, FOCUS_SETTLE_SECONDS)
    }

    // The settle covers a transition, not a policy: it is fixed here rather than read from the
    // document an attacker can rewrite.
    @Test
    fun `the focus settle is not a policy the file carries`() {
        assertEquals(FOCUS_SETTLE_SECONDS, LockReason.FocusLost.graceSeconds(POLICY.copy(hideGraceSeconds = 120)))
    }

    @Test
    fun `going idle waits for nothing beyond the timeout already spent`() {
        assertEquals(0, LockReason.Idle.graceSeconds(POLICY))
    }

    @Test
    fun `an exit waits for nothing`() {
        assertEquals(0, LockReason.Exit.graceSeconds(POLICY))
    }
}
