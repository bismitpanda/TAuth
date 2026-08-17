package com.panda.tauth

import com.panda.tauth.session.LockReason
import com.panda.tauth.session.SessionState
import com.panda.tauth.settings.SecurityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

private fun unlocked(idleTimeoutMinutes: Int): SessionState.Unlocked =
    SessionState.Unlocked(emptyList(), SecurityPolicy(idleTimeoutMinutes = idleTimeoutMinutes))

class RelockTriggersTest {

    private val scheduled = mutableListOf<LockReason>()
    private var cancels = 0

    // Both calls in the order they were made, for the presence that makes two of them.
    private val calls = mutableListOf<String>()

    private fun apply(presence: WindowPresence) = applyWindowPresence(
        presence,
        schedule = {
            scheduled += it
            calls += "schedule $it"
        },
        cancel = {
            cancels++
            calls += "cancel"
        },
    )

    @Test
    fun `a window off the screen reports the hide trigger`() {
        val report = windowReport(
            WindowPresence(isVisible = false, isMinimised = false, isFocused = false, shownBy = ShowSource.USER),
        )

        assertEquals(WindowReport.Trigger(LockReason.HiddenToTray), report)
    }

    @Test
    fun `a window hidden while minimised reports the hide trigger`() {
        val report = windowReport(
            WindowPresence(isVisible = false, isMinimised = true, isFocused = false, shownBy = ShowSource.USER),
        )

        assertEquals(WindowReport.Trigger(LockReason.HiddenToTray), report)
    }

    @Test
    fun `a minimised window reports the minimise trigger`() {
        val report = windowReport(
            WindowPresence(isVisible = true, isMinimised = true, isFocused = false, shownBy = ShowSource.USER),
        )

        assertEquals(WindowReport.Trigger(LockReason.Minimised), report)
    }

    @Test
    fun `a minimised window that kept its focus reports the minimise trigger`() {
        val report = windowReport(
            WindowPresence(isVisible = true, isMinimised = true, isFocused = true, shownBy = ShowSource.USER),
        )

        assertEquals(WindowReport.Trigger(LockReason.Minimised), report)
    }

    @Test
    fun `a window on the screen that lost focus reports a return without focus`() {
        val report = windowReport(
            WindowPresence(isVisible = true, isMinimised = false, isFocused = false, shownBy = ShowSource.USER),
        )

        assertEquals(WindowReport.ReturnedUnfocused, report)
    }

    @Test
    fun `a window a show request raised while unfocused reports the raise`() {
        val report = windowReport(
            WindowPresence(
                isVisible = true,
                isMinimised = false,
                isFocused = false,
                shownBy = ShowSource.SHOW_REQUEST,
            ),
        )

        assertEquals(WindowReport.Raised, report)
    }

    @Test
    fun `a window the user has come back to reports the return`() {
        val report = windowReport(
            WindowPresence(isVisible = true, isMinimised = false, isFocused = true, shownBy = ShowSource.USER),
        )

        assertEquals(WindowReport.Returned, report)
    }

    @Test
    fun `a window a show request put on the screen reports the raise`() {
        val report = windowReport(
            WindowPresence(
                isVisible = true,
                isMinimised = false,
                isFocused = true,
                shownBy = ShowSource.SHOW_REQUEST,
            ),
        )

        assertEquals(WindowReport.Raised, report)
    }

    @Test
    fun `hiding the window schedules a lock for the hide`() {
        apply(WindowPresence(isVisible = false, isMinimised = false, isFocused = false, shownBy = ShowSource.USER))

        assertEquals(listOf(LockReason.HiddenToTray), scheduled)
    }

    @Test
    fun `minimising the window schedules a lock for the minimise`() {
        apply(WindowPresence(isVisible = true, isMinimised = true, isFocused = false, shownBy = ShowSource.USER))

        assertEquals(listOf(LockReason.Minimised), scheduled)
    }

    @Test
    fun `losing focus schedules a lock for the focus loss`() {
        apply(WindowPresence(isVisible = true, isMinimised = false, isFocused = false, shownBy = ShowSource.USER))

        assertEquals(listOf(LockReason.FocusLost), scheduled)
    }

    // A window restored from the tray onto a desktop that gives it no focus is back, and the relock
    // the hide scheduled must not fire in front of the user.
    @Test
    fun `a window that comes back without focus cancels the scheduled lock`() {
        apply(WindowPresence(isVisible = true, isMinimised = false, isFocused = false, shownBy = ShowSource.USER))

        assertEquals(1, cancels)
    }

    @Test
    fun `a window that comes back without focus cancels before it reports the focus loss`() {
        apply(WindowPresence(isVisible = true, isMinimised = false, isFocused = false, shownBy = ShowSource.USER))

        assertEquals(listOf("cancel", "schedule FocusLost"), calls)
    }

    @Test
    fun `a window a show request raised while unfocused leaves the scheduled lock standing`() {
        apply(
            WindowPresence(
                isVisible = true,
                isMinimised = false,
                isFocused = false,
                shownBy = ShowSource.SHOW_REQUEST,
            ),
        )

        assertEquals(emptyList(), calls)
    }

    @Test
    fun `the user coming back to the window cancels the scheduled lock`() {
        apply(WindowPresence(isVisible = true, isMinimised = false, isFocused = true, shownBy = ShowSource.USER))

        assertEquals(1, cancels)
    }

    @Test
    fun `the user coming back to the window schedules nothing`() {
        apply(WindowPresence(isVisible = true, isMinimised = false, isFocused = true, shownBy = ShowSource.USER))

        assertEquals(emptyList(), scheduled)
    }

    @Test
    fun `a window raised by a show request leaves the scheduled lock standing`() {
        apply(
            WindowPresence(isVisible = true, isMinimised = false, isFocused = true, shownBy = ShowSource.SHOW_REQUEST),
        )

        assertEquals(0, cancels)
    }

    @Test
    fun `a window raised by a show request schedules nothing of its own`() {
        apply(
            WindowPresence(isVisible = true, isMinimised = false, isFocused = true, shownBy = ShowSource.SHOW_REQUEST),
        )

        assertEquals(emptyList(), scheduled)
    }

    @Test
    fun `hiding a window a show request raised schedules a lock for the hide`() {
        apply(
            WindowPresence(
                isVisible = false,
                isMinimised = false,
                isFocused = false,
                shownBy = ShowSource.SHOW_REQUEST,
            ),
        )

        assertEquals(listOf(LockReason.HiddenToTray), scheduled)
    }

    @Test
    fun `an unlocked vault answers with the interval its policy carries`() {
        assertEquals(7, idleTimeoutMinutes(unlocked(idleTimeoutMinutes = 7)))
    }

    @Test
    fun `a second policy answers with its own interval`() {
        assertEquals(3, idleTimeoutMinutes(unlocked(idleTimeoutMinutes = 3)))
    }

    @Test
    fun `a policy with the idle timeout switched off answers with no interval`() {
        assertEquals(0, idleTimeoutMinutes(unlocked(idleTimeoutMinutes = 0)))
    }

    @Test
    fun `a locked vault has no interval to watch`() {
        assertEquals(0, idleTimeoutMinutes(SessionState.Locked(LockReason.Idle)))
    }

    @Test
    fun `a session with no vault has no interval to watch`() {
        assertEquals(0, idleTimeoutMinutes(SessionState.NoVault))
    }

    @Test
    fun `a derivation in progress has no interval to watch`() {
        assertEquals(0, idleTimeoutMinutes(SessionState.Unlocking))
    }
}
