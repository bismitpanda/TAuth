package com.panda.tauth

import com.panda.tauth.ui.settings.ExportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.InvalidPathException
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

internal const val EXPORT_DIALOG_TITLE = "Export an encrypted copy of the vault"
internal const val EXPORT_FILE_NAME = "vault-export.tauth"

private val LOGGER = System.getLogger("com.panda.tauth.VaultExport")

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

// CREATE_NEW refuses a name that already exists, a symlink included, so what this opens is a file it
// created. The bytes go into that channel rather than back through the name it was created under.
private val CREATE_EXPORT = setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

// The export's two calls on the filesystem, injected so a test can answer as a mount that takes the
// mode it is given and discards it.
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

// A destination the user declines is not a failure: nothing is written and nothing is reported.
internal suspend fun exportVault(bytes: ByteArray, destination: suspend () -> Path?): Outcome<Unit, ExportError> {
    val path = destination() ?: return Outcome.Success(Unit)
    return withContext(Dispatchers.IO) { writeExport(path, bytes) }
}

// The copy is the vault's ciphertext in full and is worth what the vault is worth to anyone who can
// guess the password, so it is restricted to its owner before any of that ciphertext reaches it.
internal fun writeExport(
    path: Path,
    bytes: ByteArray,
    files: ExportAccess = SystemExportAccess,
    // Runs with the copy created, restricted and still empty, which is the one moment its mode and
    // its identity can be read as the ciphertext would find them.
    beforeWrite: (Path) -> Unit = {},
): Outcome<Unit, ExportError> {
    var isCreated = false
    return try {
        // A save dialog answers with a file name, and replacing a directory is not what it asked for.
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("$path is a directory")
        val isPosix = isPosix(path)
        // Replaced rather than opened: an existing file keeps the mode it already has, which the
        // dialog has just asked to overwrite.
        Files.deleteIfExists(path)
        val refusal = files.openChannel(path, CREATE_EXPORT, *ownerOnlyAttributes(isPosix)).use { channel ->
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

// The dialog is modal and belongs to the toolkit thread, so it is entered there and left before the
// write runs.
internal suspend fun chooseExportDestination(owner: Frame? = null): Path? = withContext(Dispatchers.Swing) {
    val dialog = FileDialog(owner, EXPORT_DIALOG_TITLE, FileDialog.SAVE)
    dialog.file = EXPORT_FILE_NAME
    dialog.isVisible = true
    val directory = dialog.directory ?: return@withContext null
    val file = dialog.file ?: return@withContext null
    // The dialog answers with whatever the platform left in its fields, and a name the path layer
    // refuses is no destination. Converted here because nothing below this takes a name.
    try {
        Path.of(directory, file)
    } catch (e: InvalidPathException) {
        LOGGER.log(System.Logger.Level.WARNING, "the chosen destination is not a path this platform accepts", e)
        null
    }
}

// Null where the copy is the owner's alone. A mount can take the mode passed to open(2) and discard
// it, and reading it back is what turns that into a refused export rather than a vault left readable.
private fun restriction(files: ExportAccess, path: Path, isPosix: Boolean): ExportError? {
    if (isPosix) {
        return if ((files.permissionsOf(path) - OWNER_ONLY).isEmpty()) null else ExportError.NotRestricted
    }
    // Off POSIX no creation attribute carries a mode, so the empty file takes an owner-only entry
    // before it holds anything. A filesystem offering neither is one no copy is written to.
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

// A destination that refused the copy is left as this found it: an empty file is no backup, and one
// sitting under the name of a vault export is worse than none.
private fun discard(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (e: IOException) {
        LOGGER.log(System.Logger.Level.WARNING, "the refused export was not removed", e)
    }
}

// The answer is the mount the copy lands on rather than the default provider's, so a removable
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
