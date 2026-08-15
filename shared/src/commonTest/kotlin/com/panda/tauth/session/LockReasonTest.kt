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
    lockOnMinimise = true,
    lockOnFocusLoss = true,
    hideGraceSeconds = 30,
    clipboardClearSeconds = 20,
)

class LockReasonTest {
    @Test
    fun `hiding to the tray is armed whatever the policy says`() {
        assertTrue(LockReason.HiddenToTray.isArmedBy(POLICY.copy(lockOnMinimise = false)))
    }

    @Test
    fun `a manual lock is armed whatever the policy says`() {
        assertTrue(LockReason.Manual.isArmedBy(POLICY.copy(lockOnMinimise = false, lockOnFocusLoss = false)))
    }

    @Test
    fun `an exit is armed whatever the policy says`() {
        assertTrue(LockReason.Exit.isArmedBy(POLICY.copy(lockOnMinimise = false, lockOnFocusLoss = false)))
    }

    @Test
    fun `minimising is armed when the policy sets it`() {
        assertTrue(LockReason.Minimised.isArmedBy(POLICY.copy(lockOnMinimise = true)))
    }

    @Test
    fun `minimising is disarmed when the policy clears it`() {
        assertFalse(LockReason.Minimised.isArmedBy(POLICY.copy(lockOnMinimise = false)))
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
    fun `minimising waits the grace period`() {
        assertEquals(30, LockReason.Minimised.graceSeconds(POLICY))
    }

    @Test
    fun `a manual lock waits for nothing`() {
        assertEquals(0, LockReason.Manual.graceSeconds(POLICY))
    }

    @Test
    fun `losing focus waits for nothing`() {
        assertEquals(0, LockReason.FocusLost.graceSeconds(POLICY))
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
