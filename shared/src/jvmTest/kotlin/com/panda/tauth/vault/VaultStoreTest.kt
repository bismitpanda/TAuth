package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.valueOrNull
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val OWNER_ONLY_MODE = PosixFilePermissions.fromString("rw-------")
private val DIRECTORY_OWNER_ONLY_MODE = PosixFilePermissions.fromString("rwx------")
private val WORLD_MODE = PosixFilePermissions.fromString("rwxrwxrwx")

// A mount that takes a chmod and leaves the mode as it found it.
private object ChmodDiscarded : FileAccess by SystemFileAccess {
    override fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) = Unit
}

// A descriptor on a file other than the one the path names: what a reader is left holding when the
// file it measured is replaced between the measurement and the open.
private class ChannelOver(private val other: Path) : FileAccess by SystemFileAccess {
    override fun openChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): FileChannel =
        SystemFileAccess.openChannel(other, options, *attributes)
}

// Everything a case does not override is the real channel's, so the file it names is created,
// written and closed as it would be.
private open class DelegatingChannel(protected val delegate: FileChannel) : FileChannel() {
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? =
        delegate.tryLock(position, size, shared)

    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = delegate.lock(position, size, shared)

    override fun read(dst: ByteBuffer): Int = delegate.read(dst)

    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = delegate.read(dsts, offset, length)

    override fun read(dst: ByteBuffer, position: Long): Int = delegate.read(dst, position)

    override fun write(src: ByteBuffer): Int = delegate.write(src)

    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long =
        delegate.write(srcs, offset, length)

    override fun write(src: ByteBuffer, position: Long): Int = delegate.write(src, position)

    override fun position(): Long = delegate.position()

    override fun position(newPosition: Long): FileChannel = delegate.position(newPosition)

    override fun size(): Long = delegate.size()

    override fun truncate(size: Long): FileChannel = delegate.truncate(size)

    override fun force(metaData: Boolean) = delegate.force(metaData)

    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long =
        delegate.transferTo(position, count, target)

    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long =
        delegate.transferFrom(src, position, count)

    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = delegate.map(mode, position, size)

    override fun implCloseChannel() = delegate.close()
}

// A channel on a file another process has locked: tryLock reports the refusal by returning null.
// Two channels inside this JVM cannot stand in for that — an overlapping lock in one process raises
// OverlappingFileLockException, and two VaultStores queue on the process lock long before either
// reaches the file lock at all.
private class LockHeldElsewhere(delegate: FileChannel) : DelegatingChannel(delegate) {
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = null
}

// A channel whose single-buffer write fails, which is the one the store uses: a full disk or a
// revoked quota, refusing after the open has already put the file on disk.
private class WriteRefused(delegate: FileChannel) : DelegatingChannel(delegate) {
    override fun write(src: ByteBuffer): Int = throw IOException("no room for the vault")
}

// The lock file alone is unlockable; the vault and its directory are opened as usual.
private class LockedByAnotherProcess(private val lockFile: Path) : FileAccess by SystemFileAccess {
    override fun openChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): FileChannel {
        val channel = SystemFileAccess.openChannel(path, options, *attributes)
        return if (path == lockFile) LockHeldElsewhere(channel) else channel
    }
}

// The named file is created and then refuses its writes; every other open is untouched.
private class WritesRefusedOn(private val target: Path) : FileAccess by SystemFileAccess {
    override fun openChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): FileChannel {
        val channel = SystemFileAccess.openChannel(path, options, *attributes)
        return if (path == target) WriteRefused(channel) else channel
    }
}

// A mount that takes the mode passed to open(2) and discards it, so the file the store just created
// is readable to everyone while the creation attribute reported success.
private class ModeWiderThanCreated(private val target: Path) : FileAccess by SystemFileAccess {
    override fun permissionsOf(path: Path, vararg options: LinkOption): Set<PosixFilePermission> =
        if (path == target) WORLD_MODE else SystemFileAccess.permissionsOf(path, *options)
}

class VaultStoreTest {
    private lateinit var directory: Path
    private lateinit var paths: VaultPaths
    private lateinit var store: VaultStore

    @BeforeTest
    fun setUp() {
        // Tests never touch the real vault path.
        directory = Files.createTempDirectory("tauth-store")
        paths = VaultPaths(OperatingSystem.LINUX, { directory.toString() }, directory.toString())
        store = VaultStore(paths)
    }

    @AfterTest
    fun tearDown() {
        // Nothing can be deleted from under a directory a test left unreachable.
        if (isPosix() && Files.isDirectory(paths.directory)) {
            Files.setPosixFilePermissions(paths.directory, DIRECTORY_OWNER_ONLY_MODE)
        }
        directory.toFile().deleteRecursively()
    }

    private fun isPosix(): Boolean =
        Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView::class.java)

    private fun vaultBytes(body: VaultBody = VaultBody()) =
        checkNotNull(VaultCodec.create("pw".toCharArray(), body).valueOrNull)

    @Test
    fun `a written vault reads back byte for byte`() {
        val bytes = vaultBytes()
        store.write(bytes)
        assertContentEquals(bytes, store.read().valueOrNull)
    }

    @Test
    fun `a second write replaces the first`() {
        store.write(vaultBytes())
        val second = vaultBytes(VaultBody(entries = listOf(totpEntry())))
        store.write(second)
        assertContentEquals(second, store.read().valueOrNull)
    }

    @Test
    fun `reading a path with no vault reports no vault file`() {
        assertEquals(VaultError.NoVaultFile, store.read().errorOrNull)
    }

    @Test
    fun `exists is false before the first write`() {
        assertFalse(store.exists())
    }

    @Test
    fun `exists is true after a write`() {
        store.write(vaultBytes())
        assertTrue(store.exists())
    }

    @Test
    fun `a successful write leaves no temp file`() {
        store.write(vaultBytes())
        assertFalse(Files.exists(paths.tempFile))
    }

    @Test
    fun `the written file is readable and writable by the owner alone`() {
        store.write(vaultBytes())
        assertEquals(OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.vaultFile))
    }

    @Test
    fun `the vault directory is reachable by the owner alone`() {
        // A directory another local user can traverse discloses the vault's size and the time of
        // its last write; one they can traverse and write to lets them unlink or replace it,
        // however tight the file's own mode is.
        store.write(vaultBytes())
        assertEquals(DIRECTORY_OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.directory))
    }

    @Test
    fun `the lock file is readable and writable by the owner alone`() {
        store.write(vaultBytes())
        assertEquals(OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.lockFile))
    }

    @Test
    fun `a directory that already exists too widely is tightened`() {
        Files.createDirectories(paths.directory)
        Files.setPosixFilePermissions(paths.directory, PosixFilePermissions.fromString("rwxrwxrwx"))
        store.write(vaultBytes())
        assertEquals(DIRECTORY_OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.directory))
    }

    @Test
    fun `a lock file left too widely by anyone else is tightened`() {
        Files.createDirectories(paths.directory)
        Files.createFile(paths.lockFile)
        Files.setPosixFilePermissions(paths.lockFile, PosixFilePermissions.fromString("rw-rw-rw-"))
        store.write(vaultBytes())
        assertEquals(OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.lockFile))
    }

    @Test
    fun `the write creates the vault directory when it is missing`() {
        val nested = directory.resolve("missing")
        val store = VaultStore(VaultPaths(OperatingSystem.LINUX, { nested.toString() }, nested.toString()))
        assertIs<Outcome.Success<Unit>>(store.write(vaultBytes()))
    }

    @Test
    fun `a failed write leaves the previous vault intact`() {
        val original = vaultBytes()
        store.write(original)
        // A non-empty directory where the temp file belongs cannot be deleted or created over, so
        // the write fails at its first step with the original still in place.
        Files.createDirectory(paths.tempFile)
        Files.createFile(paths.tempFile.resolve("blocker"))
        val failed = store.write(vaultBytes(VaultBody(entries = listOf(totpEntry()))))
        assertIs<VaultError.Io>(failed.errorOrNull)
        paths.tempFile.toFile().deleteRecursively()
        assertContentEquals(original, store.read().valueOrNull)
    }

    @Test
    fun `a write that fails part-way through leaves no work file`() {
        // The open created the file and the bytes never landed in it, so what is asserted is a
        // deletion and not a file that was never there. A half-written file is nobody's vault.
        val failing = VaultStore(paths, WritesRefusedOn(paths.tempFile))
        check(failing.write(vaultBytes()) is Outcome.Failure) { "the write was meant to fail at its first step" }
        assertFalse(Files.exists(paths.tempFile))
    }

    @Test
    fun `a save that fails at the rename leaves the whole new vault in the work file`() {
        // The bytes are fsynced and the rename that would commit them did not happen, so this file
        // is the only complete copy of them. A non-empty directory cannot be renamed over.
        Files.createDirectories(paths.directory)
        Files.createDirectory(paths.vaultFile)
        Files.createFile(paths.vaultFile.resolve("blocker"))
        val bytes = vaultBytes(VaultBody(entries = listOf(totpEntry())))
        store.write(bytes)
        assertContentEquals(bytes, Files.readAllBytes(paths.tempFile))
    }

    // Takes every permission off the vault directory and answers whether the look is refused from
    // here. A caller the kernel exempts — root, or a holder of CAP_DAC_OVERRIDE — reads straight
    // through mode 000, which leaves the cases that use this with no refusal to be about, so they
    // say so and stop rather than reporting on a premise the machine has voided.
    private fun deniedOrSkip(): Boolean {
        if (!posixOrSkip()) return false
        Files.setPosixFilePermissions(paths.directory, PosixFilePermissions.fromString("---------"))
        return try {
            Files.newByteChannel(paths.vaultFile, StandardOpenOption.READ).close()
            println("skipping: this user reads a file under a directory at mode 000")
            false
        } catch (e: IOException) {
            // The refusal is the whole answer: this is the premise the cases need.
            println("the directory denies this user (${e.javaClass.simpleName})")
            true
        }
    }

    @Test
    fun `a vault the store cannot look at is not reported as absent`() {
        // NoVaultFile would send a caller to the create flow, whose write replaces the vault that is
        // sitting there unread.
        store.write(vaultBytes())
        if (!deniedOrSkip()) return
        assertIs<VaultError.Io>(store.read().errorOrNull)
    }

    @Test
    fun `a location the store cannot look into still counts as holding a vault`() {
        store.write(vaultBytes())
        if (!deniedOrSkip()) return
        assertTrue(store.exists())
    }

    @Test
    fun `a directory where the vault file belongs is an error rather than an absent vault`() {
        // NoVaultFile would send a caller to the create flow, whose write replaces what it could not
        // read; a directory here is a hostile or a botched setup, not a first run.
        Files.createDirectories(paths.vaultFile)
        assertIs<VaultError.Io>(store.read().errorOrNull)
    }

    // A NUL is the one byte no supported platform allows in a path, so Path.of raises
    // InvalidPathException — unchecked, and raised while resolving, before any try block the
    // callers own. Built rather than written as a literal, which no editor renders.
    private fun unnameable(): VaultStore {
        val location = "/tmp/ta" + Char(0) + "uth"
        return VaultStore(VaultPaths(OperatingSystem.LINUX, { location }, "/tmp"))
    }

    @Test
    fun `writing to a location that cannot be named reports an error rather than throwing`() {
        assertIs<VaultError.Io>(unnameable().write(vaultBytes()).errorOrNull)
    }

    @Test
    fun `reading a location that cannot be named reports an error rather than throwing`() {
        assertIs<VaultError.Io>(unnameable().read().errorOrNull)
    }

    @Test
    fun `a location that does not resolve is refused rather than written to the working directory`() {
        val unresolved = VaultStore(VaultPaths(OperatingSystem.LINUX, { null }, ""))
        assertIs<VaultError.Io>(unresolved.write(vaultBytes()).errorOrNull)
    }

    @Test
    fun `a location that does not resolve holds no vault`() {
        assertFalse(VaultStore(VaultPaths(OperatingSystem.LINUX, { null }, "")).exists())
    }

    @Test
    fun `a location that cannot be named holds no vault`() {
        assertFalse(unnameable().exists())
    }

    @Test
    fun `a file larger than a vault can be is corrupt rather than an allocation`() {
        // Sparse, so the test costs no disk: only the reported size matters.
        store.write(vaultBytes())
        RandomAccessFile(paths.vaultFile.toFile(), "rw").use { it.setLength(MAX_VAULT_BYTES + 1L) }
        assertIs<VaultError.Corrupt>(store.read().errorOrNull)
    }

    // A file store with no posix permissions has no mode for these cases to be about.
    private fun posixOrSkip(): Boolean = isPosix().also {
        if (!it) println("skipping: this file store carries no posix permissions")
    }

    // A filesystem with no symbolic links cannot present the hazard these cases describe.
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
    fun `a lock file that is a symbolic link leaves its target uncreated`() {
        Files.createDirectories(paths.directory)
        val target = directory.resolve("outside")
        if (!linkOrSkip(paths.lockFile, target)) return
        store.write(vaultBytes())
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a write whose lock file is a symbolic link reports an error`() {
        Files.createDirectories(paths.directory)
        if (!linkOrSkip(paths.lockFile, directory.resolve("outside"))) return
        assertIs<VaultError.Io>(store.write(vaultBytes()).errorOrNull)
    }

    @Test
    fun `a vault directory linked onto a traversable directory leaves its target's mode alone`() {
        val target = Files.createDirectory(directory.resolve("target"))
        val traversable = PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(target, traversable)
        if (!linkOrSkip(paths.directory, target)) return
        store.write(vaultBytes())
        assertEquals(traversable, Files.getPosixFilePermissions(target))
    }

    @Test
    fun `a vault directory linked onto a traversable directory leaves no vault in its target`() {
        val target = Files.createDirectory(directory.resolve("target"))
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
        if (!linkOrSkip(paths.directory, target)) return
        store.write(vaultBytes())
        assertFalse(Files.exists(target.resolve(VAULT_FILE_NAME)))
    }

    // The write fails, and what it says is the whole of what the user has to go on.
    private fun failedWriteMessage(): String {
        val error = store.write(vaultBytes()).errorOrNull
        assertIs<VaultError.Io>(error)
        return error.cause.message.orEmpty()
    }

    private fun linkedOntoTraversableDirectory(): Boolean {
        if (!posixOrSkip()) return false
        val target = Files.createDirectory(directory.resolve("target"))
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
        return linkOrSkip(paths.directory, target)
    }

    @Test
    fun `a write refused for a linked directory's mode names the link`() {
        if (!linkedOntoTraversableDirectory()) return
        assertTrue(paths.directory.toString() in failedWriteMessage())
    }

    @Test
    fun `a write refused for a linked directory's mode does not blame the filesystem`() {
        // The target sits on the same mount as every other file here, which honours a chmod; what
        // the mode survived is the link, through which no chmod is attempted.
        if (!linkedOntoTraversableDirectory()) return
        assertFalse("does not honour permissions" in failedWriteMessage())
    }

    @Test
    fun `a write refused for a linked directory's mode names the remedy`() {
        if (!linkedOntoTraversableDirectory()) return
        assertTrue("chmod 700" in failedWriteMessage())
    }

    @Test
    fun `a vault directory linked onto an owner-only directory holds the vault`() {
        val target = Files.createDirectory(directory.resolve("target"))
        Files.setPosixFilePermissions(target, DIRECTORY_OWNER_ONLY_MODE)
        if (!linkOrSkip(paths.directory, target)) return
        store.write(vaultBytes())
        assertTrue(Files.exists(target.resolve(VAULT_FILE_NAME)))
    }

    @Test
    fun `the data root above the vault directory keeps the mode a plain directory is created with`() {
        if (!posixOrSkip()) return
        val plain = Files.getPosixFilePermissions(Files.createDirectory(directory.resolve("plain")))
        // A umask that makes a plain directory owner-only makes it the mode a data root would take
        // from a creation attribute as well, and there is then nothing to tell the two apart.
        if (plain == DIRECTORY_OWNER_ONLY_MODE) {
            println("skipping: this umask creates a plain directory $plain, the vault directory's own mode")
            return
        }
        // ~/.local/share stands under every XDG application, not only this one.
        val dataRoot = directory.resolve("share")
        val nested = VaultStore(VaultPaths(OperatingSystem.LINUX, { dataRoot.toString() }, dataRoot.toString()))
        nested.write(vaultBytes())
        assertEquals(plain, Files.getPosixFilePermissions(dataRoot))
    }

    @Test
    fun `a vault read through a descriptor on an oversized file is refused`() {
        store.write(vaultBytes())
        // Sparse, so the test costs no disk: only the size the descriptor reports matters. Past
        // Integer.MAX_VALUE it does not survive the conversion to the Int the allocation takes, so
        // what makes this a refusal is the ceiling checked ahead of that conversion.
        val oversized = directory.resolve("oversized")
        RandomAccessFile(oversized.toFile(), "rw").use { it.setLength(Int.MAX_VALUE + 1L) }
        val readingOversized = VaultStore(paths, ChannelOver(oversized))
        assertIs<VaultError.Corrupt>(readingOversized.read().errorOrNull)
    }

    @Test
    fun `a directory mode a chmod does not take fails the write`() {
        if (!posixOrSkip()) return
        Files.createDirectories(paths.directory)
        Files.setPosixFilePermissions(paths.directory, WORLD_MODE)
        val onIgnoringMount = VaultStore(paths, ChmodDiscarded)
        assertIs<VaultError.Io>(onIgnoringMount.write(vaultBytes()).errorOrNull)
    }

    @Test
    fun `a work file wider than owner-only fails the write`() {
        if (!posixOrSkip()) return
        val onIgnoringMount = VaultStore(paths, ModeWiderThanCreated(paths.tempFile))
        assertIs<VaultError.Io>(onIgnoringMount.write(vaultBytes()).errorOrNull)
    }

    @Test
    fun `a work file wider than owner-only leaves no vault`() {
        // The mode is read back before any ciphertext is written, so a mount that discards it never
        // holds a vault every local user can read.
        if (!posixOrSkip()) return
        val onIgnoringMount = VaultStore(paths, ModeWiderThanCreated(paths.tempFile))
        onIgnoringMount.write(vaultBytes())
        assertFalse(Files.exists(paths.vaultFile))
    }

    @Test
    fun `a write blocked by another process reports the lock`() {
        // Io would send the user to their disk; this names the second TAuth that is holding the
        // vault. The path in it is the lock file, which is not the vault file.
        val blocked = VaultStore(paths, LockedByAnotherProcess(paths.lockFile))
        assertIs<VaultError.LockedByAnotherProcess>(blocked.write(vaultBytes()).errorOrNull)
    }

    @Test
    fun `a write blocked by another process leaves the vault alone`() {
        val original = vaultBytes()
        store.write(original)
        val blocked = VaultStore(paths, LockedByAnotherProcess(paths.lockFile))
        blocked.write(vaultBytes(VaultBody(entries = listOf(totpEntry()))))
        assertContentEquals(original, store.read().valueOrNull)
    }

    @Test
    fun `every concurrent write from two stores succeeds`() {
        assertTrue(concurrentWrites().all { it.second is Outcome.Success })
    }

    @Test
    fun `the file left by concurrent writes is one whole payload, not a mixture`() {
        // What "serialise" has to mean observably: no interleaving, so the survivor is byte-for-byte
        // one of the payloads written rather than a blend of two.
        val payloads = concurrentWrites().map { it.first }
        val survivor = checkNotNull(store.read().valueOrNull)
        assertTrue(payloads.any { it.contentEquals(survivor) })
    }

    @Test
    fun `concurrent writes leave no temp file behind`() {
        concurrentWrites()
        assertFalse(Files.exists(paths.tempFile))
    }

    // Eight writes across two stores, every result collected so a failure cannot pass unnoticed.
    private fun concurrentWrites(): List<Pair<ByteArray, Outcome<Unit, VaultError>>> {
        val other = VaultStore(paths)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val submitted = (0 until 8).map { round ->
            val target = if (round % 2 == 0) store else other
            val payload = vaultBytes(VaultBody(entries = listOf(totpEntry(id = "id-$round"))))
            payload to pool.submit<Outcome<Unit, VaultError>> {
                start.await()
                target.write(payload)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        return submitted.map { (payload, future) -> payload to future.get() }
    }
}
