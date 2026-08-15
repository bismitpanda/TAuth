package com.panda.tauth

import com.panda.tauth.vault.OperatingSystem
import com.panda.tauth.vault.VaultPaths
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The timeout the cases run the handshake under. What a wait on it ends is a peer's silence rather
// than a slow machine: a peer that never writes has nothing to send however long it is given.
private const val HANDSHAKE_MILLIS = 250

// Longer than the handshake above, so a read that ends on this side is the case failing rather than
// the listener dropping a silent peer.
private const val PATIENCE_MILLIS = 5_000

// A bound on a call that has to return. Nothing waits for it in a run where the code is right.
private const val BOUND_SECONDS = 30L

private const val OVERSIZED_PORT_FILE_BYTES = 4096

private val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()

private val DIRECTORY_OWNER_ONLY_MODE = PosixFilePermissions.fromString("rwx------")

// A lock file another process holds: tryLock reports that by returning null. A second SingleInstance
// in this JVM cannot stand in for it — an overlapping lock raises instead.
private object LockHeldElsewhere : InstanceLockFile {
    override fun tryHold(path: Path): HeldInstanceLock? = null
}

// What FileChannel.tryLock does when another process holds the lock. Inside one JVM it raises
// OverlappingFileLockException instead, so this is the only way a case reaches that refusal.
private val DECLINED = LockAttempt { null }

// Refuses once and then lets the real lock through: the holder exited between the two attempts.
private class LockFreedAfterRefusal : InstanceLockFile {
    private var refused = false

    override fun tryHold(path: Path): HeldInstanceLock? {
        if (!refused) {
            refused = true
            return null
        }
        return ChannelInstanceLock().tryHold(path)
    }
}

// A filesystem that will not open the lock file at all.
private object LockRefusedByFilesystem : InstanceLockFile {
    override fun tryHold(path: Path): HeldInstanceLock = throw IOException("no lock file here")
}

// The port a launch records once it has the lock, as a literal: nothing here reads it back from the
// code that writes one.
private const val NEXT_LAUNCH_PORT = "40000"

// The next launch, taking the lock the instant this one gives it up and recording its own port the
// way any primary does. What happens to that file afterwards is the case's subject.
private class NextLaunchOnRelease(private val portFile: Path, private val delegate: InstanceLockFile) :
    InstanceLockFile {
    override fun tryHold(path: Path): HeldInstanceLock? {
        val held = delegate.tryHold(path) ?: return null
        return HeldInstanceLock {
            held.release()
            Files.writeString(portFile, NEXT_LAUNCH_PORT)
        }
    }
}

// A listener on loopback that is not TAuth: a program that took a port a crashed instance left in
// its port file. It serves one connection, which is all any case sends it.
private class ForeignPeer(private val reply: String?) : AutoCloseable {
    private val server = ServerSocket(0, 1, LOOPBACK)
    private val silence = CountDownLatch(1)

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "foreign-peer") {
            try {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    // A null reply holds the connection open and says nothing until close(), which
                    // is the peer the handshake timeout exists for.
                    if (reply == null) silence.await() else socket.getOutputStream().write("$reply\n".toByteArray())
                }
            } catch (_: IOException) {
                // The case is over and the socket is closed; there is nothing left to serve.
            }
        }
    }

    override fun close() {
        silence.countDown()
        server.close()
    }
}

class SingleInstanceTest {
    private lateinit var directory: Path
    private lateinit var paths: VaultPaths
    private val opened = mutableListOf<AutoCloseable>()
    private val worker = Executors.newSingleThreadExecutor()

    @BeforeTest
    fun setUp() {
        // Tests never touch the real vault path.
        directory = Files.createTempDirectory("tauth-instance")
        paths = VaultPaths(OperatingSystem.LINUX, { directory.toString() }, directory.toString())
    }

    @AfterTest
    fun tearDown() {
        opened.reversed().forEach { it.close() }
        worker.shutdownNow()
        directory.toFile().deleteRecursively()
    }

    private fun instance(lockFile: InstanceLockFile = ChannelInstanceLock()) =
        SingleInstance(paths, lockFile, HANDSHAKE_MILLIS)

    // Every primary a case claims is closed when the case ends, so no lock and no listener outlives
    // it.
    private fun claim(lockFile: InstanceLockFile = ChannelInstanceLock()): InstanceRole =
        instance(lockFile).claim().also { if (it is InstanceRole.Primary) opened += it }

    private fun claimPrimary(lockFile: InstanceLockFile = ChannelInstanceLock()): InstanceRole.Primary {
        val role = claim(lockFile)
        assertIs<InstanceRole.Primary>(role)
        return role
    }

    // The real lock file, meeting the refusal another process's lock produces.
    private fun refusedTheLock() = SingleInstance(paths, ChannelInstanceLock(DECLINED), HANDSHAKE_MILLIS)

    private fun peer(reply: String?): ForeignPeer = ForeignPeer(reply).also { opened += it }

    private fun writePortFile(text: String) {
        Files.createDirectories(paths.directory)
        Files.writeString(paths.instancePortFile, text)
    }

    private fun listeningPort(): Int = Files.readString(paths.instancePortFile).trim().toInt()

    // A port nothing listens on: bound to learn a free number, then closed. What a crashed instance
    // leaves in its port file.
    private fun refusedPort(): Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

    // Speaks the wire protocol as literals rather than through SingleInstance, so the answer is
    // checked against the line the protocol names and not the constant the code happens to hold.
    private fun exchange(port: Int, request: String): String? = try {
        Socket(LOOPBACK, port).use { socket ->
            socket.soTimeout = PATIENCE_MILLIS
            socket.getOutputStream().apply {
                write("$request\n".toByteArray())
                flush()
            }
            socket.getInputStream().bufferedReader().readLine()
        }
    } catch (_: IOException) {
        null
    }

    // Says nothing and reads until the listener gives up on it. Nothing is caught: a read that ends
    // at this side's own patience is the case failing, not the listener dropping a silent peer.
    private fun readUntilDropped(port: Int): String? = Socket(LOOPBACK, port).use { socket ->
        socket.soTimeout = PATIENCE_MILLIS
        socket.getInputStream().bufferedReader().readLine()
    }

    // Bounds a claim that has to return. A claim that hangs fails the case here instead of stopping
    // the build.
    private fun claimWithinBound(lockFile: InstanceLockFile): InstanceRole =
        worker.submit<InstanceRole> { instance(lockFile).claim() }.get(BOUND_SECONDS, TimeUnit.SECONDS)

    private fun linkOrSkip(link: Path, target: Path): Boolean = try {
        Files.createSymbolicLink(link, target)
        true
    } catch (e: UnsupportedOperationException) {
        println("skipping: no symbolic link support here (${e.message})")
        false
    } catch (e: FileSystemException) {
        println("skipping: no symbolic link support here (${e.message})")
        false
    }

    @Test
    fun `a lock another process holds is not reported as held`() {
        Files.createDirectories(paths.directory)
        assertNull(ChannelInstanceLock(DECLINED).tryHold(paths.instanceLockFile))
    }

    @Test
    fun `a launch refused the lock does not become a second primary`() {
        assertIs<InstanceRole.Unprotected>(refusedTheLock().claim())
    }

    @Test
    fun `a launch refused the lock leaves the running instance's port file alone`() {
        // Two primaries is the whole failure: the second binds its own socket over the first's port
        // file, and both write the vault from bodies neither has seen the other change.
        claimPrimary()
        val running = Files.readString(paths.instancePortFile)
        refusedTheLock().claim()
        assertEquals(running, Files.readString(paths.instancePortFile))
    }

    @Test
    fun `a first launch becomes the primary`() {
        assertIs<InstanceRole.Primary>(claim())
    }

    @Test
    fun `a first launch creates the vault directory when it is missing`() {
        claimPrimary()
        assertTrue(Files.isDirectory(paths.directory))
    }

    @Test
    fun `the vault directory this launch creates is reachable by the owner alone`() {
        // A directory another local user can traverse discloses the vault's size and the time of its
        // last write, and this mechanism creates it before any vault exists.
        val plain = Files.getPosixFilePermissions(Files.createDirectory(directory.resolve("plain")))
        if (plain == DIRECTORY_OWNER_ONLY_MODE) {
            println("skipping: this umask creates a plain directory $plain, the mode under test")
            return
        }
        claimPrimary()
        assertEquals(DIRECTORY_OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.directory))
    }

    @Test
    fun `the primary starts with no show request`() {
        assertEquals(0L, claimPrimary().showRequests.value)
    }

    @Test
    fun `the primary acknowledges a SHOW line on the port its port file names`() {
        claimPrimary()
        assertEquals("OK", exchange(listeningPort(), "SHOW"))
    }

    @Test
    fun `a SHOW line raises one show request`() {
        val primary = claimPrimary()
        exchange(listeningPort(), "SHOW")
        assertEquals(1L, primary.showRequests.value)
    }

    @Test
    fun `a second launch is superseded by the running instance`() {
        claimPrimary()
        assertEquals(InstanceRole.Superseded, claim(LockHeldElsewhere))
    }

    @Test
    fun `a second launch asks the running instance to show its window`() {
        val primary = claimPrimary()
        claim(LockHeldElsewhere)
        assertEquals(1L, primary.showRequests.value)
    }

    @Test
    fun `two later launches are two show requests`() {
        // A count rather than a signal: the second launch must not be swallowed as a repeat of the
        // first.
        val primary = claimPrimary()
        claim(LockHeldElsewhere)
        claim(LockHeldElsewhere)
        assertEquals(2L, primary.showRequests.value)
    }

    @Test
    fun `a second launch leaves the running instance's port file alone`() {
        claimPrimary()
        val running = Files.readString(paths.instancePortFile)
        claim(LockHeldElsewhere)
        assertEquals(running, Files.readString(paths.instancePortFile))
    }

    @Test
    fun `a launch that finds no port file opens its own window`() {
        // Two launches at once: the one holding the lock has not written its port yet.
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.NOTHING_ANSWERED), claim(LockHeldElsewhere))
    }

    @Test
    fun `a launch whose port file names a refused port opens its own window`() {
        // The port file a crashed instance left, with something still holding the lock. Nothing
        // answers there, so the request cannot be handed over and this launch shows its own window.
        writePortFile(refusedPort().toString())
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.NOTHING_ANSWERED), claim(LockHeldElsewhere))
    }

    @Test
    fun `a launch whose port file holds no port opens its own window`() {
        writePortFile("not-a-port")
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.NOTHING_ANSWERED), claim(LockHeldElsewhere))
    }

    @Test
    fun `a launch whose port file holds a number no port can be opens its own window`() {
        writePortFile("70000")
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.NOTHING_ANSWERED), claim(LockHeldElsewhere))
    }

    @Test
    fun `a launch whose port file is longer than a port file can be opens its own window`() {
        // The port is only the head of the file. A bounded read that trimmed what it got would take
        // this for a port file and hand the request to whatever answers there.
        val answering = peer(reply = "OK")
        writePortFile(answering.port.toString() + "\n".repeat(OVERSIZED_PORT_FILE_BYTES))
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.NOTHING_ANSWERED), claimWithinBound(LockHeldElsewhere))
    }

    @Test
    fun `a launch whose port file is a directory opens its own window`() {
        Files.createDirectories(paths.instancePortFile)
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.NOTHING_ANSWERED), claim(LockHeldElsewhere))
    }

    @Test
    fun `a launch does not read its port through a symbolic link`() {
        // A link at that name is one an attacker points at a file they write, which would send the
        // show request — and the fact that TAuth was launched — to a listener of their choosing.
        val answering = peer(reply = "OK")
        val elsewhere = Files.writeString(directory.resolve("outside"), answering.port.toString())
        Files.createDirectories(paths.directory)
        if (!linkOrSkip(paths.instancePortFile, elsewhere)) return
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.NOTHING_ANSWERED), claimWithinBound(LockHeldElsewhere))
    }

    @Test
    fun `a primary whose port file is a symbolic link leaves its target uncreated`() {
        Files.createDirectories(paths.directory)
        val target = directory.resolve("outside")
        if (!linkOrSkip(paths.instancePortFile, target)) return
        claim()
        assertFalse(Files.exists(target))
    }

    @Test
    fun `a primary that cannot record its port opens its window without a listener`() {
        Files.createDirectories(paths.directory)
        if (!linkOrSkip(paths.instancePortFile, directory.resolve("outside"))) return
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.PORT_NOT_RECORDED), claim())
    }

    @Test
    fun `a launch that cannot record its port hands the lock back`() {
        // Holding the lock while answering nothing would leave every later launch unprotected too.
        Files.createDirectories(paths.directory)
        if (!linkOrSkip(paths.instancePortFile, directory.resolve("outside"))) return
        claim()
        Files.deleteIfExists(paths.instancePortFile)
        assertIs<InstanceRole.Primary>(claim())
    }

    @Test
    fun `a launch whose peer never answers opens its own window`() {
        val silent = peer(reply = null)
        writePortFile(silent.port.toString())
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.NOTHING_ANSWERED), claimWithinBound(LockHeldElsewhere))
    }

    @Test
    fun `a launch whose peer is not this protocol opens its own window`() {
        // A program that took the port a crashed instance recorded. Exiting here would leave the
        // user's launch with nothing on screen and nothing said.
        val foreign = peer(reply = "HTTP/1.1 400 Bad Request")
        writePortFile(foreign.port.toString())
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.NOTHING_ANSWERED), claimWithinBound(LockHeldElsewhere))
    }

    @Test
    fun `a launch retakes a lock freed since it was refused`() {
        writePortFile(refusedPort().toString())
        assertIs<InstanceRole.Primary>(claimPrimary(LockFreedAfterRefusal()))
    }

    @Test
    fun `a crashed instance's port file is replaced by the launch that follows it`() {
        // The next launch is the primary, and the port file names the socket it answers on rather
        // than the one nobody listens on.
        writePortFile(refusedPort().toString())
        claimPrimary()
        assertEquals("OK", exchange(listeningPort(), "SHOW"))
    }

    @Test
    fun `a lock the filesystem refuses leaves the launch unprotected`() {
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.LOCK_UNUSABLE), claim(LockRefusedByFilesystem))
    }

    @Test
    fun `a launch whose location does not resolve opens its own window`() {
        // A relative location would put the lock and the port file under the directory the
        // application was launched from.
        val unresolved =
            SingleInstance(VaultPaths(OperatingSystem.LINUX, { null }, ""), ChannelInstanceLock(), HANDSHAKE_MILLIS)
        assertEquals(InstanceRole.Unprotected(UnprotectedReason.LOCATION_UNRESOLVED), unresolved.claim())
    }

    @Test
    fun `an unrecognised command is answered with nothing`() {
        claimPrimary()
        assertNull(exchange(listeningPort(), "QUIT"))
    }

    @Test
    fun `an unrecognised command raises no show request`() {
        val primary = claimPrimary()
        exchange(listeningPort(), "QUIT")
        assertEquals(0L, primary.showRequests.value)
    }

    @Test
    fun `an unrecognised command leaves the listener serving`() {
        val primary = claimPrimary()
        exchange(listeningPort(), "QUIT")
        exchange(listeningPort(), "SHOW")
        assertEquals(1L, primary.showRequests.value)
    }

    @Test
    fun `a line longer than a command can be raises no show request`() {
        val primary = claimPrimary()
        exchange(listeningPort(), "SHOW".repeat(16))
        assertEquals(0L, primary.showRequests.value)
    }

    @Test
    fun `a peer that connects and says nothing is dropped`() {
        claimPrimary()
        assertNull(worker.submit<String?> { readUntilDropped(listeningPort()) }.get(BOUND_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun `a peer that connects and says nothing leaves the listener serving`() {
        val primary = claimPrimary()
        worker.submit<String?> { readUntilDropped(listeningPort()) }.get(BOUND_SECONDS, TimeUnit.SECONDS)
        exchange(listeningPort(), "SHOW")
        assertEquals(1L, primary.showRequests.value)
    }

    @Test
    fun `closing the primary releases the lock for the next launch`() {
        claimPrimary().close()
        assertIs<InstanceRole.Primary>(claimPrimary())
    }

    @Test
    fun `a closing primary leaves the port file of the launch that takes over`() {
        // The deletion belongs under the lock: releasing first lets the next launch record its port
        // into the gap, and this close would then take that file with it.
        val primary = claimPrimary(NextLaunchOnRelease(paths.instancePortFile, ChannelInstanceLock()))
        primary.close()
        assertEquals(NEXT_LAUNCH_PORT, Files.readString(paths.instancePortFile))
    }

    @Test
    fun `closing the primary leaves no port file behind`() {
        claimPrimary().close()
        assertFalse(Files.exists(paths.instancePortFile))
    }

    @Test
    fun `closing the primary stops the listener`() {
        val primary = claimPrimary()
        val port = listeningPort()
        primary.close()
        assertNull(exchange(port, "SHOW"))
    }
}
