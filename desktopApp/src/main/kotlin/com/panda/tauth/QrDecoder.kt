package com.panda.tauth

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.panda.tauth.Outcome
import com.panda.tauth.totp.isMigrationUri
import com.panda.tauth.vault.ImageReadError
import com.panda.tauth.vault.ImportReadError
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.imageio.ImageIO

internal const val SCAN_DIALOG_TITLE = "Choose an image holding a QR code"

// The dialog filters on these, and a file that is none of them decodes to no image.
internal val SCANNABLE_EXTENSIONS = listOf("png", "jpg", "jpeg", "gif", "bmp")

private val LOGGER = System.getLogger("com.panda.tauth.QrDecoder")

// Every code in the image rather than the first: one screenshot can hold a page of them, and which
// one the user meant is theirs to say.
internal fun decodeQrCodes(image: BufferedImage): List<String> {
    val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
    return try {
        GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(bitmap).map { it.text }
    } catch (_: NotFoundException) {
        // The reader's answer for an image holding none, which is not a failure to report: the
        // screen says no code was found.
        emptyList()
    }
}

// An image the user declines is nothing to read and nothing to report.
internal suspend fun readQrImage(destination: suspend () -> Path?): Outcome<List<String>?, ImageReadError> {
    val path = destination() ?: return Outcome.Success(null)
    return withContext(Dispatchers.IO) {
        try {
            val image = ImageIO.read(path.toFile())
                ?: return@withContext Outcome.Failure(VaultError.Corrupt("it is not an image TAuth reads"))
            Outcome.Success(decodeQrCodes(image))
        } catch (e: IOException) {
            Outcome.Failure(VaultError.Io(e))
        }
    }
}

internal suspend fun readQrClipboard(source: suspend () -> BufferedImage?): Outcome<List<String>?, ImageReadError> {
    val image = source() ?: return Outcome.Failure(VaultError.Corrupt("the clipboard holds no image"))
    return withContext(Dispatchers.Default) { Outcome.Success(decodeQrCodes(image)) }
}

// The clipboard belongs to the toolkit thread. A flavour it does not hold is an ordinary answer, and
// so is contents that changed between the offer and the read.
internal suspend fun clipboardImage(): BufferedImage? = withContext(Dispatchers.Swing) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
            ?.takeIf { it.isDataFlavorSupported(DataFlavor.imageFlavor) }
            ?.getTransferData(DataFlavor.imageFlavor) as? BufferedImage
    } catch (e: IllegalStateException) {
        LOGGER.log(System.Logger.Level.WARNING, "the clipboard could not be read for an image", e)
        null
    } catch (_: HeadlessException) {
        null
    } catch (_: UnsupportedFlavorException) {
        null
    } catch (e: IOException) {
        LOGGER.log(System.Logger.Level.WARNING, "the clipboard could not be read for an image", e)
        null
    }
}

// One export code holds many accounts, so the image is read for that one rather than for every code
// in it.
internal suspend fun readExportCode(destination: suspend () -> Path?): Outcome<String?, ImportReadError> =
    when (val read = readQrImage(destination)) {
        is Outcome.Failure -> Outcome.Failure(asImportError(read.error))

        is Outcome.Success ->
            read.value
                ?.let { codes ->
                    codes.firstOrNull(::isMigrationUri)?.let { Outcome.Success(it) }
                        ?: Outcome.Failure(VaultError.Corrupt("that image holds no export code"))
                }
                ?: Outcome.Success(null)
    }

// Neither view is the other's subtype, so the reading's failure is restated as the import's.
private fun asImportError(error: ImageReadError): ImportReadError = when (error) {
    is VaultError.Corrupt -> error
    is VaultError.Io -> error
}

// The dialog is modal and belongs to the toolkit thread, so it is entered there and left before the
// image is decoded.
internal suspend fun chooseQrImage(owner: Frame? = null): Path? = withContext(Dispatchers.Swing) {
    val dialog = FileDialog(owner, SCAN_DIALOG_TITLE, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> SCANNABLE_EXTENSIONS.any { name.lowercase().endsWith(".$it") } }
    dialog.isVisible = true
    val directory = dialog.directory ?: return@withContext null
    val file = dialog.file ?: return@withContext null
    try {
        Path.of(directory, file)
    } catch (e: InvalidPathException) {
        LOGGER.log(System.Logger.Level.WARNING, "the chosen image is not a path this platform accepts", e)
        null
    }
}
