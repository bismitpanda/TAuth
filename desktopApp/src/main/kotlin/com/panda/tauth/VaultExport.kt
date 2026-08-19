package com.panda.tauth

import com.panda.tauth.ui.settings.FileWriteError
import com.panda.tauth.vault.ExportFormat
import com.panda.tauth.vault.ImportReadError
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal const val EXPORT_DIALOG_TITLE = "Export an encrypted copy of the vault"
internal const val EXPORT_FILE_NAME = "vault-export.tauth"

private val LOGGER = System.getLogger("com.panda.tauth.VaultExport")

// A destination the user declines is not a failure: nothing is written and nothing is reported.
internal suspend fun exportVault(bytes: ByteArray, destination: suspend () -> Path?): Outcome<Unit, FileWriteError> {
    val path = destination() ?: return Outcome.Success(Unit)
    // The copy is the vault's ciphertext in full and is worth what the vault is worth to anyone who
    // can guess the password, so it goes down the restricted path like everything else that leaves.
    return withContext(Dispatchers.IO) { writeOwnerOnly(path, bytes) }
}

// The dialog is modal and belongs to the toolkit thread, so it is entered there and left before the
// write runs.
internal suspend fun chooseSaveDestination(title: String, fileName: String, owner: Frame? = null): Path? =
    withContext(Dispatchers.Swing) {
        val dialog = FileDialog(owner, title, FileDialog.SAVE)
        dialog.file = fileName
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

internal suspend fun chooseExportDestination(owner: Frame? = null): Path? =
    chooseSaveDestination(EXPORT_DIALOG_TITLE, EXPORT_FILE_NAME, owner)

internal const val IMPORT_DIALOG_TITLE = "Choose the accounts to import"

// The ceiling §6.2 puts on a vault file: nothing that would fit in one arrives larger than this.
private const val MAX_IMPORT_BYTES = 16L * 1024 * 1024

// A file the user declines is nothing to import and nothing to report. The text is read whole
// because the reader works over the document rather than a stream of it.
internal suspend fun readImportSource(destination: suspend () -> Path?): Outcome<String?, ImportReadError> {
    val path = destination() ?: return Outcome.Success(null)
    return withContext(Dispatchers.IO) {
        try {
            // Refused by size before it is read rather than by OutOfMemoryError afterwards, which is
            // an Error no Outcome carries. The ceiling is the one §6.2 puts on a vault.
            if (Files.size(path) > MAX_IMPORT_BYTES) {
                Outcome.Failure(VaultError.Corrupt("that file is larger than any export TAuth writes"))
            } else {
                Outcome.Success(Files.readString(path))
            }
        } catch (_: CharacterCodingException) {
            // Caught ahead of its IOException supertype, and discarded: the message names the bytes
            // it stopped on, and the file may hold every secret in a vault.
            Outcome.Failure(VaultError.Corrupt("that file is not text TAuth can read"))
        } catch (e: IOException) {
            Outcome.Failure(VaultError.Io(e))
        }
    }
}

// The dialog is modal and belongs to the toolkit thread, so it is entered there and left before the
// file is read.
internal suspend fun chooseImportSource(owner: Frame? = null): Path? = withContext(Dispatchers.Swing) {
    val dialog = FileDialog(owner, IMPORT_DIALOG_TITLE, FileDialog.LOAD)
    dialog.isVisible = true
    val directory = dialog.directory ?: return@withContext null
    val file = dialog.file ?: return@withContext null
    try {
        Path.of(directory, file)
    } catch (e: InvalidPathException) {
        LOGGER.log(System.Logger.Level.WARNING, "the chosen file is not a path this platform accepts", e)
        null
    }
}

internal const val PLAINTEXT_DIALOG_TITLE = "Export the accounts unencrypted"

internal fun plaintextFileName(format: ExportFormat): String = when (format) {
    ExportFormat.JSON -> "vault-accounts.json"
    ExportFormat.URI_LIST -> "vault-accounts.txt"
}

// The text is every secret in the vault in the clear, so it goes down the restricted path and is
// encoded here rather than being carried as bytes through a screen that has no use for them.
internal suspend fun exportPlaintext(
    text: String,
    format: ExportFormat,
    destination: suspend (String) -> Path?,
): Outcome<Unit, FileWriteError> {
    val path = destination(plaintextFileName(format)) ?: return Outcome.Success(Unit)
    return withContext(Dispatchers.IO) { writeOwnerOnly(path, text.encodeToByteArray()) }
}
