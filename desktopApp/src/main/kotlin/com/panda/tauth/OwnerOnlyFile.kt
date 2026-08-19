package com.panda.tauth

import com.panda.tauth.ui.settings.ExportError
import com.panda.tauth.ui.settings.FileWriteError
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

private val LOGGER = System.getLogger("com.panda.tauth.OwnerOnlyFile")

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

// CREATE_NEW refuses a name that already exists, a symlink included, so what this opens is a file it
// created. The bytes go into that channel rather than back through the name it was created under.
private val CREATE_OWNER_ONLY = setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

// The two calls on the filesystem a restricted write makes, injected so a test can answer as a mount
// that takes the mode it is given and discards it.
internal interface ExportAccess {
    fun openChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): FileChannel

    fun permissionsOf(path: Path): Set<PosixFilePermission>
}

internal object SystemExportAccess : ExportAccess {
    override fun openChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): FileChannel =
        FileChannel.open(path, options, *attributes)

    override fun permissionsOf(path: Path): Set<PosixFilePermission> =
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
}

// Everything this writes outside the vault directory carries a secret in a form something other than
// TAuth reads, so it is restricted to its owner before any of those bytes reach it.
internal fun writeOwnerOnly(
    path: Path,
    bytes: ByteArray,
    files: ExportAccess = SystemExportAccess,
    // Runs with the file created, restricted and still empty, which is the one moment its mode and
    // its identity can be read as the content would find them.
    beforeWrite: (Path) -> Unit = {},
): Outcome<Unit, FileWriteError> {
    var isCreated = false
    return try {
        // A save dialog answers with a file name, and replacing a directory is not what it asked for.
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("$path is a directory")
        val isPosix = isPosix(path)
        // Replaced rather than opened: an existing file keeps the mode it already has, which the
        // dialog has just asked to overwrite.
        Files.deleteIfExists(path)
        val refusal = files.openChannel(path, CREATE_OWNER_ONLY, *ownerOnlyAttributes(isPosix)).use { channel ->
            isCreated = true
            val refused = restriction(files, path, isPosix)
            if (refused == null) {
                beforeWrite(path)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
            }
            refused
        }
        if (refusal == null) {
            Outcome.Success(Unit)
        } else {
            discard(path)
            Outcome.Failure(refusal)
        }
    } catch (e: IOException) {
        if (isCreated) discard(path)
        Outcome.Failure(ExportError.Io(e))
    } catch (e: UnsupportedOperationException) {
        if (isCreated) discard(path)
        Outcome.Failure(ExportError.Io(e))
    } catch (e: SecurityException) {
        if (isCreated) discard(path)
        Outcome.Failure(ExportError.Io(e))
    }
}

// Null where the file is the owner's alone. A mount can take the mode passed to open(2) and discard
// it, and reading it back is what turns that into a refused write rather than a file left readable.
private fun restriction(files: ExportAccess, path: Path, isPosix: Boolean): FileWriteError? {
    if (isPosix) {
        return if ((files.permissionsOf(path) - OWNER_ONLY).isEmpty()) null else ExportError.NotRestricted
    }
    // Off POSIX no creation attribute carries a mode, so the empty file takes an owner-only entry
    // before it holds anything. A filesystem offering neither is one nothing is written to.
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return ExportError.NotRestricted
    view.acl = listOf(
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(view.owner)
            .setPermissions(AclEntryPermission.entries.toSet())
            .build(),
    )
    return null
}

// A destination that refused the file is left as this found it: an empty file is no backup, and one
// sitting under the name of an export is worse than none.
private fun discard(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (e: IOException) {
        LOGGER.log(System.Logger.Level.WARNING, "the refused file was not removed", e)
    }
}

// The answer is the mount the file lands on rather than the default provider's, so a removable
// filesystem that carries no modes is one this restricts by access control entry instead.
private fun isPosix(path: Path): Boolean = try {
    Files.getFileStore(existingAncestor(path)).supportsFileAttributeView(PosixFileAttributeView::class.java)
} catch (e: IOException) {
    LOGGER.log(System.Logger.Level.DEBUG, "cannot determine the file store; assuming no posix support", e)
    false
}

private fun existingAncestor(path: Path): Path {
    var candidate: Path? = path.toAbsolutePath()
    while (candidate != null && !Files.exists(candidate)) {
        candidate = candidate.parent
    }
    return candidate ?: path.toAbsolutePath()
}

private fun ownerOnlyAttributes(isPosix: Boolean): Array<FileAttribute<*>> =
    if (isPosix) arrayOf(PosixFilePermissions.asFileAttribute(OWNER_ONLY)) else emptyArray()
