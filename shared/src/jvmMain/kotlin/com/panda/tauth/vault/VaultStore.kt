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
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock

// A FileLock is held per JVM, so two VaultStore instances in one process would collide on it rather
// than queue. This makes them queue; the FileLock keeps other processes out.
private val PROCESS_LOCK = ReentrantLock()

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
private val DIRECTORY_OWNER_ONLY = PosixFilePermissions.fromString("rwx------")

private val LOGGER = System.getLogger("com.panda.tauth.vault.VaultStore")

private val WRITE_NEW = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

class VaultStore(private val paths: VaultPaths = VaultPaths()) {
    fun exists(): Boolean = try {
        paths.isResolved && Files.isRegularFile(paths.vaultFile)
    } catch (e: InvalidPathException) {
        LOGGER.log(System.Logger.Level.WARNING, "vault location is not a valid path", e)
        false
    }

    fun read(): Outcome<ByteArray, VaultError> = guarded { readFile(paths.vaultFile) }

    fun write(bytes: ByteArray): Outcome<Unit, VaultError> = guarded {
        withLocks {
            try {
                writeTemp(bytes)
                promoteTemp()
                forceDirectory()
                Outcome.Success(Unit)
            } catch (e: IOException) {
                Outcome.Failure(abandon(e))
            } catch (e: UnsupportedOperationException) {
                Outcome.Failure(abandon(e))
            }
        }
    }

    // Without an atomic rename the temp file can be the only complete copy, so its path is logged.
    private fun abandon(cause: Throwable): VaultError {
        try {
            Files.deleteIfExists(paths.tempFile)
        } catch (e: IOException) {
            LOGGER.log(System.Logger.Level.WARNING, "a failed save left a work file at ${paths.tempFile}", e)
        }
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

    private fun readFile(path: Path): Outcome<ByteArray, VaultError> = try {
        when {
            !Files.isRegularFile(path) -> Outcome.Failure(VaultError.NoVaultFile)

            // readAllBytes on a hostile file raises OutOfMemoryError, which no catch here would see.
            Files.size(path) > MAX_VAULT_BYTES -> Outcome.Failure(
                VaultError.Corrupt("file is larger than a vault can be"),
            )

            else -> Outcome.Success(Files.readAllBytes(path))
        }
    } catch (e: IOException) {
        Outcome.Failure(VaultError.Io(e))
    }

    // The permissions are a creation attribute, so the file never exists readable.
    private fun writeTemp(bytes: ByteArray) {
        Files.deleteIfExists(paths.tempFile)
        FileChannel.open(paths.tempFile, WRITE_NEW, *ownerOnlyAttributes()).use {
            verifyOwnerOnly(paths.tempFile)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                it.write(buffer)
            }
            it.force(true)
        }
    }

    // The rename is the commit point: until it completes, a reader sees the previous vault whole.
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
            FileChannel.open(paths.directory, StandardOpenOption.READ).use { it.force(true) }
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
            val lockOptions = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            FileChannel.open(paths.lockFile, lockOptions, *ownerOnlyAttributes()).use { channel ->
                restrictToOwner(paths.lockFile)
                val lock = channel.tryLock()
                lock?.use { block() } ?: Outcome.Failure(VaultError.LockedByAnotherProcess(paths.lockFile.toString()))
            }
        } catch (e: IOException) {
            Outcome.Failure(VaultError.Io(e))
        } catch (e: OverlappingFileLockException) {
            Outcome.Failure(VaultError.Io(e))
        } finally {
            PROCESS_LOCK.unlock()
        }
    }

    // A traversable directory leaks every account name whatever the file's own mode is.
    private fun createRestrictedDirectory() {
        if (!Files.isDirectory(paths.directory)) {
            if (isPosix()) {
                Files.createDirectories(paths.directory, PosixFilePermissions.asFileAttribute(DIRECTORY_OWNER_ONLY))
            } else {
                Files.createDirectories(paths.directory)
            }
        }
        if (isPosix()) {
            if (Files.getPosixFilePermissions(paths.directory) != DIRECTORY_OWNER_ONLY) {
                Files.setPosixFilePermissions(paths.directory, DIRECTORY_OWNER_ONLY)
            }
        } else {
            restrictToOwner(paths.directory)
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

    // The directory's entry is inheritable so a file created inside it is restricted at creation.
    private fun restrictToOwner(path: Path) {
        if (isPosix()) {
            Files.setPosixFilePermissions(path, OWNER_ONLY)
            return
        }
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: throw IOException("no access control view on $path; cannot restrict it to the owner")
        val builder = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(view.owner)
            .setPermissions(AclEntryPermission.entries.toSet())
        if (Files.isDirectory(path)) {
            builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
        }
        view.acl = listOf(builder.build())
    }

    // A vfat or exFAT mount discards the mode passed to open(2).
    private fun verifyOwnerOnly(path: Path) {
        if (!isPosix()) return
        val actual = Files.getPosixFilePermissions(path)
        if ((actual - OWNER_ONLY).isNotEmpty()) {
            throw IOException("$path is $actual; this filesystem does not honour permissions")
        }
    }
}
