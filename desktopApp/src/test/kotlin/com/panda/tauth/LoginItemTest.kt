package com.panda.tauth

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LAUNCHER = "/opt/tauth/bin/TAuth"
private const val OLDER_LAUNCHER = "/opt/tauth-1.0/bin/TAuth"
private const val JVM = "/usr/lib/jvm/temurin-25/bin/java"

private class FakeLoginItem(
    var command: String? = null,
    private val readFails: Boolean = false,
    private val writeFails: Boolean = false,
) : LoginItem {
    var writes = 0
    var removals = 0

    override fun read(): Outcome<String?, AutostartError> =
        if (readFails) Outcome.Failure(AutostartError.Io(IOException("unreadable"))) else Outcome.Success(command)

    override fun write(command: String): Outcome<Unit, AutostartError> {
        writes++
        if (writeFails) return Outcome.Failure(AutostartError.Io(IOException("read-only")))
        this.command = command
        return Outcome.Success(Unit)
    }

    override fun remove(): Outcome<Unit, AutostartError> {
        removals++
        command = null
        return Outcome.Success(Unit)
    }
}

class LoginItemTest {
    @Test
    fun `enabling writes a record naming the launcher`() {
        val item = FakeLoginItem()

        reconcileLoginItem(isEnabled = true, launcher = LAUNCHER, item = item)

        assertEquals(LAUNCHER, item.command)
    }

    @Test
    fun `a record left by an earlier install is rewritten to the current path`() {
        val item = FakeLoginItem(command = OLDER_LAUNCHER)

        reconcileLoginItem(isEnabled = true, launcher = LAUNCHER, item = item)

        assertEquals(LAUNCHER, item.command)
    }

    @Test
    fun `a record already naming the current path is not written again`() {
        val item = FakeLoginItem(command = LAUNCHER)

        reconcileLoginItem(isEnabled = true, launcher = LAUNCHER, item = item)

        assertEquals(0, item.writes)
    }

    @Test
    fun `disabling removes the record`() {
        val item = FakeLoginItem(command = LAUNCHER)

        reconcileLoginItem(isEnabled = false, launcher = LAUNCHER, item = item)

        assertNull(item.command)
    }

    @Test
    fun `disabling with no record writes nothing`() {
        val item = FakeLoginItem()

        reconcileLoginItem(isEnabled = false, launcher = LAUNCHER, item = item)

        assertEquals(0, item.writes)
    }

    @Test
    fun `a run with no packaged launcher writes no record`() {
        val item = FakeLoginItem()

        reconcileLoginItem(isEnabled = true, launcher = JVM, item = item)

        assertEquals(0, item.writes)
    }

    @Test
    fun `a run with no packaged launcher reports why`() {
        val outcome = reconcileLoginItem(isEnabled = true, launcher = JVM, item = FakeLoginItem())

        assertEquals(Outcome.Failure(AutostartError.NoLauncher), outcome)
    }

    @Test
    fun `a run with no packaged launcher still removes a record when the setting is off`() {
        val item = FakeLoginItem(command = OLDER_LAUNCHER)

        reconcileLoginItem(isEnabled = false, launcher = JVM, item = item)

        assertNull(item.command)
    }

    @Test
    fun `a record that cannot be read is reported rather than overwritten`() {
        val item = FakeLoginItem(readFails = true)

        val outcome = reconcileLoginItem(isEnabled = true, launcher = LAUNCHER, item = item)

        assertTrue(outcome is Outcome.Failure)
    }

    @Test
    fun `a record that cannot be read is not written over`() {
        val item = FakeLoginItem(readFails = true)

        reconcileLoginItem(isEnabled = true, launcher = LAUNCHER, item = item)

        assertEquals(0, item.writes)
    }

    @Test
    fun `a refused write is reported`() {
        val item = FakeLoginItem(writeFails = true)

        val outcome = reconcileLoginItem(isEnabled = true, launcher = LAUNCHER, item = item)

        assertTrue(outcome is Outcome.Failure)
    }
}
