package com.panda.tauth.vault

import com.panda.tauth.Outcome
import java.io.IOError
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock

// A FileLock is held per JVM, so two VaultStore instances in one process would collide on it rather
// than queue. This makes them queue; the FileLock keeps other processes out.
private val PROCESS_LOCK = ReentrantLock()

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
private val DIRECTORY_OWNER_ONLY = PosixFilePermissions.fromString("rwx------")

private val LOGGER = System.getLogger("com.panda.tauth.vault.VaultStore")

// CREATE_NEW refuses a name that already exists, a symlink included, so it opens nothing but the
// file it creates.
private val WRITE_NEW = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

// The lock file outlives a run, so its open has to accept an existing file. NOFOLLOW_LINKS is what
// keeps that from opening — and creating, when the link dangles — whatever a link put in its place
// points at.
private val LOCK_OPEN = setOf<OpenOption>(
    StandardOpenOption.CREATE,
    StandardOpenOption.WRITE,
    LinkOption.NOFOLLOW_LINKS,
)

private val READ_ONLY = setOf<OpenOption>(StandardOpenOption.READ)

class VaultStore internal constructor(private val paths: VaultPaths, private val files: FileAccess) {
    constructor(paths: VaultPaths = VaultPaths()) : this(paths, SystemFileAccess)

    // A location that cannot be looked into holds a vault; one that cannot be named or resolved does not.
    fun exists(): Boolean = try {
        paths.isResolved && !Files.notExists(paths.vaultFile)
    } catch (e: InvalidPathException) {
        LOGGER.log(System.Logger.Level.WARNING, "vault location is not a valid path", e)
        false
    }

    fun read(): Outcome<ByteArray, VaultError> = guarded { readFile(paths.vaultFile) }

    fun write(bytes: ByteArray): Outcome<Unit, VaultError> = guarded {
        withLocks {
            try {
                writeTemp(bytes)
            } catch (e: IOException) {
                return@withLocks Outcome.Failure(discardTemp(e))
            } catch (e: UnsupportedOperationException) {
                return@withLocks Outcome.Failure(discardTemp(e))
            }
            try {
                promoteTemp()
                forceDirectory()
                Outcome.Success(Unit)
            } catch (e: IOException) {
                Outcome.Failure(keepTemp(e))
            } catch (e: UnsupportedOperationException) {
                Outcome.Failure(keepTemp(e))
            }
        }
    }

    // Nothing can read a temp file the write did not finish, and the vault file is untouched.
    private fun discardTemp(cause: Throwable): VaultError {
        try {
            Files.deleteIfExists(paths.tempFile)
        } catch (e: IOException) {
            LOGGER.log(System.Logger.Level.WARNING, "a failed save left a work file at ${paths.tempFile}", e)
        }
        return VaultError.Io(cause)
    }

    // The rename did not happen, so this file holds the whole new vault and can be the only copy.
    private fun keepTemp(cause: Throwable): VaultError {
        LOGGER.log(System.Logger.Level.WARNING, "a save that failed to commit left a vault at ${paths.tempFile}", cause)
        return VaultError.Io(cause)
    }

    // A relative location would put the vault wherever the app was launched from.
    private fun <T> guarded(block: () -> Outcome<T, VaultError>): Outcome<T, VaultError> = try {
        if (paths.isResolved) {
            block()
        } else {
            Outcome.Failure(VaultError.Io(IOException("the vault location does not resolve to an absolute path")))
        }
    } catch (e: InvalidPathException) {
        Outcome.Failure(VaultError.Io(e))
    } catch (e: IOError) {
        Outcome.Failure(VaultError.Io(e))
    }

    // Only the exception that establishes absence produces NoVaultFile; failing to look is Io.
    private fun readFile(path: Path): Outcome<ByteArray, VaultError> = try {
        // A fifo blocks the open until a writer appears and a directory fails on the first read.
        // The type is the type at the path: one swapped in after it is read is not covered.
        if (Files.readAttributes(path, BasicFileAttributes::class.java).isRegularFile) {
            files.openChannel(path, READ_ONLY).use { readWithinLimit(it) }
        } else {
            Outcome.Failure(VaultError.Io(IOException("the vault path holds something that is not a regular file")))
        }
    } catch (e: NoSuchFileException) {
        LOGGER.log(System.Logger.Level.DEBUG, "no vault at $path", e)
        Outcome.Failure(VaultError.NoVaultFile)
    } catch (e: IOException) {
        Outcome.Failure(VaultError.Io(e))
    }

    // The size and the bytes come from one open descriptor, so the file that is measured is the file
    // that is read. The ceiling is checked before the buffer is allocated, because an allocation the
    // size of a hostile file raises OutOfMemoryError: an Error that no catch converts and no Outcome
    // carries.
    private fun readWithinLimit(channel: FileChannel): Outcome<ByteArray, VaultError> {
        val size = channel.size()
        if (size > MAX_VAULT_BYTES) {
            return Outcome.Failure(VaultError.Corrupt("file is larger than a vault can be"))
        }
        val buffer = ByteBuffer.allocate(size.toInt())
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break
        }
        return Outcome.Success(buffer.array().copyOf(buffer.position()))
    }

    // On POSIX the owner-only mode is a creation attribute, so the file never exists readable, and
    // it is read back before any ciphertext is written. Off POSIX the file is created with no mode
    // of its own and what restricts it is the inheritable entry on the directory.
    private fun writeTemp(bytes: ByteArray) {
        Files.deleteIfExists(paths.tempFile)
        files.openChannel(paths.tempFile, WRITE_NEW, *ownerOnlyAttributes()).use {
            verifyOwnerOnly(paths.tempFile, OWNER_ONLY, LinkOption.NOFOLLOW_LINKS)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                it.write(buffer)
            }
            it.force(true)
        }
    }

    // The atomic rename is the commit point: until it completes, a reader sees the previous vault
    // whole. The fallback below has no commit point, so a reader there can catch a partial file.
    private fun promoteTemp() {
        try {
            Files.move(
                paths.tempFile,
                paths.vaultFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            LOGGER.log(System.Logger.Level.WARNING, "no atomic rename here; a crash can lose the vault", e)
            Files.move(paths.tempFile, paths.vaultFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // Forcing the parent makes the rename durable, not just the bytes it renames. Windows will not
    // open a directory as a channel.
    private fun forceDirectory() {
        try {
            files.openChannel(paths.directory, READ_ONLY).use { it.force(true) }
        } catch (e: IOException) {
            LOGGER.log(System.Logger.Level.DEBUG, "the directory entry is unflushed", e)
        }
    }

    private fun <T> withLocks(block: () -> Outcome<T, VaultError>): Outcome<T, VaultError> {
        PROCESS_LOCK.lock()
        return try {
            createRestrictedDirectory()
            // Beside the vault so that locking never opens the vault for writing. Whoever can
            // replace it can hold the lock for ever, so its mode is not trusted.
            files.openChannel(paths.lockFile, LOCK_OPEN, *ownerOnlyAttributes()).use { channel ->
                restrictToOwner(paths.lockFile)
                val lock = channel.tryLock()
                lock?.use { block() } ?: Outcome.Failure(VaultError.LockedByAnotherProcess(paths.lockFile.toString()))
            }
        } catch (e: IOException) {
            Outcome.Failure(VaultError.Io(e))
        } catch (e: OverlappingFileLockException) {
            Outcome.Failure(VaultError.Io(e))
        } catch (e: UnsupportedOperationException) {
            // A platform that rejects an open option or a creation attribute is a returned error.
            Outcome.Failure(VaultError.Io(e))
        } finally {
            PROCESS_LOCK.unlock()
        }
    }

    // A directory another local user can traverse discloses the vault's size and the time of its
    // last write; one they can traverse and write to lets them unlink or replace it.
    private fun createRestrictedDirectory() {
        if (!Files.isDirectory(paths.directory)) {
            createLeafDirectory()
        }
        if (isPosix()) {
            restrictDirectoryMode()
        } else {
            restrictToOwner(paths.directory)
        }
    }

    // A chmod through a symbolic link tightens whatever it points at, a home directory included, so
    // a linked directory is left as found and the mode checked is the resolved directory's own.
    private fun restrictDirectoryMode() {
        val isLink = Files.isSymbolicLink(paths.directory)
        if (!isLink && files.permissionsOf(paths.directory) != DIRECTORY_OWNER_ONLY) {
            files.setPermissions(paths.directory, DIRECTORY_OWNER_ONLY)
        }
        val target = paths.directory.toRealPath()
        if (isLink) verifyLinkTarget(target) else verifyOwnerOnly(target, DIRECTORY_OWNER_ONLY)
    }

    // No chmod is attempted through the link, so a mode wider than owner-only here is the one the
    // target was given and the remedy is the user's rather than the filesystem's.
    private fun verifyLinkTarget(target: Path) {
        if (!isPosix()) return
        val actual = files.permissionsOf(target)
        if ((actual - DIRECTORY_OWNER_ONLY).isNotEmpty()) {
            throw IOException(
                "${paths.directory} is a symbolic link to $target, which is $actual; make $target " +
                    "owner-only with chmod 700, or point the link at a directory that already is",
            )
        }
    }

    // A creation attribute on createDirectories reaches every parent it creates, and those parents
    // are the data root shared with every other application. createDirectory refuses a name that
    // already exists, a dangling link included, so nothing is created through one.
    private fun createLeafDirectory() {
        paths.directory.parent?.let { Files.createDirectories(it) }
        if (isPosix()) {
            Files.createDirectory(paths.directory, PosixFilePermissions.asFileAttribute(DIRECTORY_OWNER_ONLY))
        } else {
            Files.createDirectory(paths.directory)
        }
    }

    private fun isPosix(): Boolean = try {
        Files.getFileStore(existingAncestor()).supportsFileAttributeView(PosixFileAttributeView::class.java)
    } catch (e: IOException) {
        LOGGER.log(System.Logger.Level.DEBUG, "cannot determine the file store; assuming no posix support", e)
        false
    }

    private fun existingAncestor(): Path {
        var candidate: Path? = paths.directory.toAbsolutePath()
        while (candidate != null && !Files.exists(candidate)) {
            candidate = candidate.parent
        }
        return candidate ?: paths.directory.toAbsolutePath()
    }

    private fun ownerOnlyAttributes(): Array<FileAttribute<*>> =
        if (isPosix()) arrayOf(PosixFilePermissions.asFileAttribute(OWNER_ONLY)) else emptyArray()

    private fun restrictToOwner(path: Path) {
        if (isPosix()) {
            files.setPermissions(path, OWNER_ONLY)
            return
        }
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: throw IOException("no access control view on $path; cannot restrict it to the owner")
        val builder = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(view.owner)
            .setPermissions(AclEntryPermission.entries.toSet())
        if (Files.isDirectory(path)) {
            // The entry is inherited by what is created inside, so off POSIX, where no creation
            // attribute carries a mode, a file written here is restricted at creation.
            builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
        }
        view.acl = listOf(builder.build())
    }

    // A mount can take the mode passed to open(2) and to chmod(2) and discard it; reading it back
    // is what turns that into a refused write rather than a vault left readable.
    private fun verifyOwnerOnly(path: Path, allowed: Set<PosixFilePermission>, vararg options: LinkOption) {
        if (!isPosix()) return
        val actual = files.permissionsOf(path, *options)
        if ((actual - allowed).isNotEmpty()) {
            throw IOException("$path is $actual; this filesystem does not honour permissions")
        }
    }
}
