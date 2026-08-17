package com.panda.tauth

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// A bound on the handshake this role never runs: nothing here starts its listener.
private const val HANDSHAKE_MILLIS = 250

class InstanceStartupTest {
    private lateinit var directory: Path

    // What the launch went on to open, so a launch that opened nothing is a list that stayed empty
    // rather than an assertion about what was not called.
    private val started = mutableListOf<InstanceRole>()

    @BeforeTest
    fun setUp() {
        // Tests never touch the real vault path.
        directory = Files.createTempDirectory("tauth-startup")
    }

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    // Unbound, so this role holds no port and answers nothing.
    private fun primary(): InstanceRole.Primary =
        InstanceRole.Primary(ServerSocket(), {}, directory.resolve("instance.port"), HANDSHAKE_MILLIS)

    @Test
    fun `a superseded launch opens nothing`() {
        startUnlessSuperseded(InstanceRole.Superseded) { started += it }

        assertEquals(emptyList(), started)
    }

    @Test
    fun `a primary launch opens its window`() {
        val role = primary()

        startUnlessSuperseded(role) { started += it }

        assertEquals(listOf<InstanceRole>(role), started)
    }

    @Test
    fun `an unprotected launch opens its window`() {
        val role = InstanceRole.Unprotected(UnprotectedReason.NOTHING_ANSWERED)

        startUnlessSuperseded(role) { started += it }

        assertEquals(listOf<InstanceRole>(role), started)
    }

    // A second reason, so what reaches the window is the role rather than the one case a fixture
    // happens to carry.
    @Test
    fun `a launch whose location does not resolve opens its window`() {
        val role = InstanceRole.Unprotected(UnprotectedReason.LOCATION_UNRESOLVED)

        startUnlessSuperseded(role) { started += it }

        assertEquals(listOf<InstanceRole>(role), started)
    }
}
