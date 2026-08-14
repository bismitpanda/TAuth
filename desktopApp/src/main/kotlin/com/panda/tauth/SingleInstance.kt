package com.panda.tauth

import com.panda.tauth.vault.VaultPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread

private val LOGGER = System.getLogger("com.panda.tauth.SingleInstance")

// The whole protocol: one US-ASCII line naming the command, one US-ASCII line answering it. A web
// page can be made to open a connection to any loopback port, but not to choose the first line it
// sends, so a request that must match this exactly is out of a browser's reach.
//
// The command carries no credential and the listener is found by scanning loopback, so anything on
// this machine can send it, a different OS user included: loopback carries no owner check. What it
// does is raise a window; it names no account, reaches no vault operation and unlocks nothing.
private const val SHOW_COMMAND = "SHOW"
private const val ACKNOWLEDGEMENT = "OK"

// A command is one short line, and the cap is what stops the listener reading for as long as a peer
// keeps writing.
private const val MAX_LINE_BYTES = 32

// Decimal digits and a newline. Anything longer is not a port file.
private const val MAX_PORT_FILE_BYTES = 16

private const val MIN_PORT = 1
private const val MAX_PORT = 65535

// The kernel chooses the port; the file is how the next launch learns it.
private const val EPHEMERAL_PORT = 0

// One connection is served at a time, so a peer that stalls delays the next launch's handshake by
// the timeout below; the queue is what keeps a launch arriving meanwhile from being refused.
private const val BACKLOG = 4

// Bounds every wait in the handshake: the connect, the listener's read of the command, and the
// launch's read of the answer. A peer that connects and then says nothing is dropped after this
// rather than holding the listener.
private const val HANDSHAKE_TIMEOUT_MILLIS = 2_000

private const val LISTENER_THREAD_NAME = "tauth-single-instance"

private const val POSIX_ATTRIBUTE_VIEW = "posix"

private val DIRECTORY_OWNER_ONLY = PosixFilePermissions.fromString("rwx------")

// The lock file outlives a run, so its open accepts an existing file. NOFOLLOW_LINKS keeps a link
// left at that name from opening — and creating, when it dangles — whatever it points at.
private val LOCK_OPEN = setOf<OpenOption>(
    StandardOpenOption.CREATE,
    StandardOpenOption.WRITE,
    LinkOption.NOFOLLOW_LINKS,
)

// TRUNCATE_EXISTING keeps a shorter port from leaving the tail of a longer one behind. NOFOLLOW_LINKS
// keeps a link left at that name from truncating whatever it points at.
private val PORT_FILE_WRITE = arrayOf<OpenOption>(
    StandardOpenOption.CREATE,
    StandardOpenOption.WRITE,
    StandardOpenOption.TRUNCATE_EXISTING,
    LinkOption.NOFOLLOW_LINKS,
)

// What a launch turned out to be. Primary and Unprotected both open a window; Superseded exits with
// status 0, having handed its request to the instance that was already running.
sealed interface InstanceRole {
    // Holds the instance lock and answers SHOW for the life of the process.
    class Primary internal constructor(
        private val listener: ServerSocket,
        private val lock: HeldInstanceLock,
        private val portFile: Path,
        private val handshakeTimeoutMillis: Int,
    ) : InstanceRole,
        AutoCloseable {
        private val _showRequests = MutableStateFlow(0L)

        // Raised once per accepted SHOW, for the window layer to collect and raise itself on, which
        // keeps Compose out of here. A count rather than a signal, so two launches in quick
        // succession are two requests rather than one.
        //
        // A request here is another process's, and anything on the machine can send one. The window
        // layer owes it a raised window and nothing else: it is not evidence the user is present,
        // so a relock already scheduled has to survive it rather than being cancelled the way a
        // window the user reopens cancels one.
        val showRequests: StateFlow<Long> = _showRequests.asStateFlow()

        internal fun startListening() {
            // A blocking accept is a thread rather than a coroutine: cancelling a coroutine does not
            // return from accept, so the socket has to be closed to stop the loop either way. The
            // thread is a daemon, so it holds no exit open.
            thread(isDaemon = true, name = LISTENER_THREAD_NAME) { serve() }
        }

        private fun serve() {
            while (true) {
                val socket = try {
                    listener.accept()
                } catch (e: IOException) {
                    // close() closes the listener, which is how this loop is meant to end. Any other
                    // refusal ends it too, rather than spinning on a socket that cannot accept.
                    if (!listener.isClosed) {
                        LOGGER.log(System.Logger.Level.WARNING, "the single-instance listener stopped", e)
                    }
                    return
                }
                try {
                    socket.use { answer(it) }
                } catch (e: IOException) {
                    // One connection's failure is not the listener's.
                    LOGGER.log(System.Logger.Level.DEBUG, "a connection to this instance ended early", e)
                }
            }
        }

        private fun answer(socket: Socket) {
            socket.soTimeout = handshakeTimeoutMillis
            // An unrecognised line is answered with nothing and the connection is closed. The line
            // itself is a stranger's to choose, so it is not logged.
            if (readAsciiLine(socket.getInputStream()) != SHOW_COMMAND) return
            // The count is raised before the acknowledgement is written, so a launch that reads the
            // acknowledgement knows its request is recorded and can exit.
            _showRequests.update { it + 1 }
            socket.getOutputStream().apply {
                write(asciiLine(ACKNOWLEDGEMENT))
                flush()
            }
        }

        // The operating system closes the socket and releases the lock when the process exits,
        // however it exits, so an abrupt exit leaves at most a port file naming nothing — which the
        // next launch probes and replaces.
        override fun close() {
            closeListener()
            // The port file goes while the lock is still held. Releasing first opens a window for
            // the next launch to take the lock and record its own port, which this deletion would
            // then remove, leaving a running primary no later launch can find.
            deletePortFile()
            lock.release()
        }

        private fun deletePortFile() {
            try {
                Files.deleteIfExists(portFile)
            } catch (e: IOException) {
                LOGGER.log(System.Logger.Level.DEBUG, "the port file outlived the instance that wrote it", e)
            }
        }

        private fun closeListener() {
            try {
                listener.close()
            } catch (e: IOException) {
                LOGGER.log(System.Logger.Level.DEBUG, "the single-instance listener did not close cleanly", e)
            }
        }
    }

    // A running instance took the show request. This process opens no window.
    data object Superseded : InstanceRole

    // The launch could neither serve nor hand over, and holds no lock: the window opens without
    // single-instance service. A launch that exited here instead would leave the application
    // unstartable for as long as whatever holds the lock does.
    //
    // What this costs, and what the window layer has to tell the user: two live instances each hold
    // their own decrypted body and each save rewrites the whole file, so the later save drops
    // whatever the other wrote. The vault's own lock spans one write() and refuses only writes that
    // overlap it, and read() takes no lock at all, so nothing reports the loss. It is not a rare
    // state either — a port file that cannot be written puts every launch here.
    data class Unprotected(val reason: UnprotectedReason) : InstanceRole
}

// Why a launch has no single-instance service, for the window layer to put on screen.
enum class UnprotectedReason {
    // The vault location is relative, so the lock and the port file would guard nothing.
    LOCATION_UNRESOLVED,

    // The lock file could not be created or opened, its directory included.
    LOCK_UNUSABLE,

    // Something holds the lock and nothing answered on the port it left, if it left one.
    NOTHING_ANSWERED,

    // No loopback socket, so a later launch would have nothing to connect to.
    NO_LISTENER,

    // The socket is bound but its port could not be recorded, which no later launch can find.
    PORT_NOT_RECORDED,
}

// Gives the lock back before the process exits.
internal fun interface HeldInstanceLock {
    fun release()
}

// A FileLock taken twice inside one JVM raises OverlappingFileLockException rather than returning
// null, so no second object in a test can stand in for a second process holding it. This is the seam
// a stand-in goes through: the socket, the port file and the handshake are real on both paths.
internal fun interface InstanceLockFile {
    // Null when another process holds the lock.
    fun tryHold(path: Path): HeldInstanceLock?
}

// Taking the lock, separated from opening the file and wrapping the result, because what decides
// whether two processes can run at once is the null this returns and a case has to reach it.
internal fun interface LockAttempt {
    fun tryLock(channel: FileChannel): FileLock?
}

internal class ChannelInstanceLock(private val attempt: LockAttempt = LockAttempt(FileChannel::tryLock)) :
    InstanceLockFile {
    override fun tryHold(path: Path): HeldInstanceLock? {
        val channel = FileChannel.open(path, LOCK_OPEN)
        // A null lock is another process holding it. Returning a handle here would make this process
        // a second primary: it would bind its own socket over the running instance's port file, and
        // both would write the vault from bodies neither had seen the other change.
        val lock = attemptOrClose(channel) ?: run {
            channel.close()
            return null
        }
        return HeldInstanceLock { release(lock, channel) }
    }

    // Closing the channel releases every lock this JVM holds on the file, so the lock going back is
    // what the close turns on and the close happens whether or not the release above it does. A
    // second call finds a lock already invalid and a channel already closed, which is the ordinary
    // shutdown path calling this after a caller has: neither is a failure.
    private fun release(lock: FileLock, channel: FileChannel) {
        try {
            if (lock.isValid) lock.release()
        } catch (e: IOException) {
            LOGGER.log(System.Logger.Level.DEBUG, "the instance lock did not release cleanly", e)
        } finally {
            closeChannel(channel)
        }
    }

    private fun closeChannel(channel: FileChannel) {
        try {
            channel.close()
        } catch (e: IOException) {
            LOGGER.log(System.Logger.Level.DEBUG, "the instance lock file did not close cleanly", e)
        }
    }

    private fun attemptOrClose(channel: FileChannel): FileLock? = try {
        attempt.tryLock(channel)
    } catch (e: IOException) {
        channel.close()
        throw e
    } catch (e: OverlappingFileLockException) {
        channel.close()
        throw e
    }
}

class SingleInstance internal constructor(
    private val paths: VaultPaths,
    private val lockFile: InstanceLockFile,
    private val handshakeTimeoutMillis: Int,
) {
    constructor(paths: VaultPaths = VaultPaths()) : this(paths, ChannelInstanceLock(), HANDSHAKE_TIMEOUT_MILLIS)

    fun claim(): InstanceRole {
        // A relative location would put the lock and the port file under whatever directory the
        // application was launched from, where they guard nothing.
        if (!paths.isResolved) {
            return unprotected(UnprotectedReason.LOCATION_UNRESOLVED, "the vault location is not absolute", null)
        }
        return try {
            createVaultDirectory()
            takeRoleOrHandOff()
        } catch (e: IOException) {
            unprotected(UnprotectedReason.LOCK_UNUSABLE, "no lock file at ${paths.instanceLockFile}", e)
        } catch (e: InvalidPathException) {
            unprotected(UnprotectedReason.LOCK_UNUSABLE, "the instance lock has no valid path", e)
        } catch (e: UnsupportedOperationException) {
            // A filesystem that rejects the creation attribute below.
            unprotected(UnprotectedReason.LOCK_UNUSABLE, "no lock file at ${paths.instanceLockFile}", e)
        } catch (e: OverlappingFileLockException) {
            unprotected(UnprotectedReason.LOCK_UNUSABLE, "this process already holds the instance lock", e)
        }
    }

    // A first run reaches its window before any vault exists, so this mechanism is what creates the
    // directory. The leaf carries the mode as a creation attribute, so it never exists at the
    // process umask: a directory another local user can traverse discloses the vault's size and the
    // time of its last write. The parents are the data root shared with every other application and
    // keep the mode they are created with.
    private fun createVaultDirectory() {
        if (Files.isDirectory(paths.directory)) return
        paths.directory.parent?.let { Files.createDirectories(it) }
        if (isPosix()) {
            Files.createDirectory(paths.directory, PosixFilePermissions.asFileAttribute(DIRECTORY_OWNER_ONLY))
        } else {
            Files.createDirectory(paths.directory)
        }
    }

    private fun isPosix(): Boolean =
        paths.directory.fileSystem.supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW)

    private fun takeRoleOrHandOff(): InstanceRole {
        lockFile.tryHold(paths.instanceLockFile)?.let { return listenOn(it) }
        // Something holds the lock. Whether it is a running TAuth is answered by the handshake, not
        // by the lock: a port file left by a crashed instance names a port nobody listens on, or one
        // an unrelated program has since taken.
        if (handOff()) return InstanceRole.Superseded
        // The lock file is not deleted. Unlinking it releases no lock a process holds on the inode,
        // and the next launch would create a second file and take a second lock, which is two
        // primaries. Taking the lock again is what tells a crashed instance's leftovers from a live
        // one; the port file it left is replaced rather than deleted, so a live holder that has yet
        // to write its own is not robbed of it.
        val retaken = lockFile.tryHold(paths.instanceLockFile) ?: return unprotected(
            UnprotectedReason.NOTHING_ANSWERED,
            "another process holds the instance lock and answers nothing",
            null,
        )
        return listenOn(retaken)
    }

    private fun listenOn(lock: HeldInstanceLock): InstanceRole {
        // Loopback only: nothing off this machine can reach the listener.
        val listener = try {
            ServerSocket(EPHEMERAL_PORT, BACKLOG, InetAddress.getLoopbackAddress())
        } catch (e: IOException) {
            lock.release()
            return unprotected(UnprotectedReason.NO_LISTENER, "no loopback socket for a second launch", e)
        }
        val primary = InstanceRole.Primary(listener, lock, paths.instancePortFile, handshakeTimeoutMillis)
        return try {
            writePort(listener.localPort)
            primary.startListening()
            primary
        } catch (e: IOException) {
            // Nothing can find this listener, so it is closed and the lock goes back rather than
            // being held by a process that answers nothing.
            primary.close()
            unprotected(UnprotectedReason.PORT_NOT_RECORDED, "the port could not be recorded", e)
        }
    }

    private fun writePort(port: Int) {
        Files.newOutputStream(paths.instancePortFile, *PORT_FILE_WRITE).use { it.write(asciiLine(port.toString())) }
    }

    // True when a running instance acknowledged the request. Everything else — no port file, a file
    // naming no port, a refused connection, a peer that does not speak this protocol — is false.
    private fun handOff(): Boolean {
        val port = readPort() ?: return false
        return sendShow(port)
    }

    private fun readPort(): Int? = try {
        // An open on a fifo blocks until a writer appears, and a launch must reach its window
        // whatever sits at this path, so the type is read before anything is opened.
        val attributes = Files.readAttributes(
            paths.instancePortFile,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.isRegularFile) parsePort(readPortFile()) else null
    } catch (e: IOException) {
        // Absent is the ordinary case of two launches at once: the instance holding the lock has
        // not written its port yet.
        LOGGER.log(System.Logger.Level.DEBUG, "no port file to hand this launch's request to", e)
        null
    }

    // The open refuses a symbolic link itself rather than trusting the type read a moment earlier,
    // so a link swapped in at that name sends this launch's request nowhere. The ceiling bounds the
    // read rather than a size measured beforehand, so a file that grows between the two is still
    // bounded.
    private fun readPortFile(): ByteArray = Files.newInputStream(paths.instancePortFile, LinkOption.NOFOLLOW_LINKS)
        .use { it.readNBytes(MAX_PORT_FILE_BYTES + 1) }

    private fun parsePort(bytes: ByteArray): Int? {
        if (bytes.size > MAX_PORT_FILE_BYTES) return null
        return bytes.decodeToString().trim().toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT }
    }

    private fun sendShow(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), handshakeTimeoutMillis)
            socket.soTimeout = handshakeTimeoutMillis
            socket.getOutputStream().apply {
                write(asciiLine(SHOW_COMMAND))
                flush()
            }
            // The acknowledgement is what tells a running TAuth from whatever else has taken a port
            // a crashed one left behind, and it arrives only after the request is recorded.
            readAsciiLine(socket.getInputStream()) == ACKNOWLEDGEMENT
        }
    } catch (e: IOException) {
        // A refusal, a timeout, or a peer that closed: no instance answered on that port.
        LOGGER.log(System.Logger.Level.DEBUG, "no running instance answered on port $port", e)
        false
    }

    private fun unprotected(reason: UnprotectedReason, detail: String, cause: Throwable?): InstanceRole {
        LOGGER.log(System.Logger.Level.WARNING, "this launch has no single-instance service: $detail", cause)
        return InstanceRole.Unprotected(reason)
    }
}

private fun asciiLine(text: String): ByteArray = "$text\n".toByteArray(Charsets.US_ASCII)

// One US-ASCII line, bounded: a peer that sends no newline is cut off at the limit rather than read
// for as long as it keeps writing. Null when the line ends without one.
private fun readAsciiLine(input: InputStream): String? {
    val line = ByteArrayOutputStream()
    while (line.size() < MAX_LINE_BYTES) {
        val byte = input.read()
        if (byte < 0) return null
        if (byte == '\n'.code) return line.toByteArray().toString(Charsets.US_ASCII)
        line.write(byte)
    }
    return null
}
