package com.panda.tauth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.panda.tauth.ui.qr.QrEncoding
import com.panda.tauth.ui.qr.QrSymbol
import com.panda.tauth.ui.settings.FileWriteError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.max

// In modules, and the symbol carries it on every side. Level M is what Google Authenticator's own
// provisioning codes are encoded at, which is the population of scanners these have to satisfy.
internal const val QUIET_ZONE_MODULES = 2

private val HINTS = mapOf(
    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
    EncodeHintType.CHARACTER_SET to "UTF-8",
)

// The writer scales its result up to the size asked of it, so the smallest request leaves the module
// grid itself, which is the resolution the drawing scales from.
private const val MODULE_RESOLUTION = 1

object QrEncoder : QrEncoding {
    override fun encode(text: String): QrSymbol? = try {
        symbolOf(QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, MODULE_RESOLUTION, MODULE_RESOLUTION, HINTS))
    } catch (_: WriterException) {
        // Thrown for text past the format's capacity, and the absent symbol is that answer.
        null
    }
}

private fun symbolOf(matrix: BitMatrix): QrSymbol {
    val width = matrix.width
    return QrSymbol(width, BooleanArray(width * matrix.height) { matrix[it % width, it / width] })
}

internal const val QR_IMAGE_TITLE = "Save this QR code"
internal const val QR_IMAGE_FILE_NAME = "account-qr.png"

// Large enough that a camera reads it off a screen at arm's length and a print of it stays scannable.
private const val QR_IMAGE_TARGET_PX = 512

private const val PNG_FORMAT = "png"

// The grid the screen drew rather than a fresh encode of the same URI, so what is saved is the symbol
// the user was looking at.
internal suspend fun saveQrImage(symbol: QrSymbol, destination: suspend () -> Path?): Outcome<Unit, FileWriteError> {
    val path = destination() ?: return Outcome.Success(Unit)
    return withContext(Dispatchers.IO) { writeOwnerOnly(path, qrPngBytes(symbol)) }
}

// Whole modules, as on screen: a scaled bitmap would put module edges on fractional pixels and
// scanners refuse those far more often than the difference looks.
internal fun qrPngBytes(symbol: QrSymbol, targetPx: Int = QR_IMAGE_TARGET_PX): ByteArray {
    val scale = max(1, targetPx / symbol.width)
    val size = symbol.width * scale
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until size) {
        for (x in 0 until size) {
            image.setRGB(x, y, if (symbol.isDark(x / scale, y / scale)) DARK_RGB else LIGHT_RGB)
        }
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(image, PNG_FORMAT, out)
    return out.toByteArray()
}

// The symbol is dark-on-light wherever it is drawn, which the screen states for itself and a file
// carries to whatever opens it.
private const val DARK_RGB = 0x000000
private const val LIGHT_RGB = 0xFFFFFF
