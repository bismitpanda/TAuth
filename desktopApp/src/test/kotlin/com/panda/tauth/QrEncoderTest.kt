package com.panda.tauth

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.ResultMetadataType
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.panda.tauth.totp.HashAlgorithm
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.OtpType
import com.panda.tauth.ui.qr.QrSymbol
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Base32 of the RFC 4226 seed "12345678901234567890", which is 160 bits.
private const val SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

// Nineteen characters, a colon and forty-four: the label length §13.1 holds the symbol version to.
private const val LONG_ISSUER = "Example Corporation"
private const val LONG_ACCOUNT = "alice.smith@corporate.department.example.com"

// Characters outside Latin-1 as well as outside ASCII, since the encoder is asked for UTF-8 and a
// URI reaches it percent-encoded, which would leave that hint carrying nothing a test could see.
private const val NON_ASCII_TEXT = "Übercorp ✓ álice"

private const val LARGEST_SCANNABLE_VERSION = 10

// A symbol grows by four modules a version over a fixed seventeen.
private const val MODULES_PER_VERSION = 4
private const val VERSION_OVERHEAD = 17

// Enough pixels a module that the binarizer has a block to average rather than a single sample.
private const val DECODE_SCALE = 8

private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

private fun imageOf(symbol: QrSymbol): BufferedImage {
    val size = symbol.width * DECODE_SCALE
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, size, size)
    graphics.color = Color.BLACK
    for (y in 0 until symbol.width) {
        for (x in 0 until symbol.width) {
            if (symbol.isDark(x, y)) {
                graphics.fillRect(x * DECODE_SCALE, y * DECODE_SCALE, DECODE_SCALE, DECODE_SCALE)
            }
        }
    }
    graphics.dispose()
    return image
}

private fun encoded(uri: OtpAuthUri): QrSymbol = checkNotNull(QrEncoder.encode(uri.build()))

private fun read(image: BufferedImage) =
    MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image))))

private fun scanned(symbol: QrSymbol) = read(imageOf(symbol))

private fun rendersBackTo(uri: OtpAuthUri) {
    assertEquals(uri.build(), scanned(encoded(uri)).text)
}

private fun versionOf(symbol: QrSymbol): Int =
    (symbol.width - 2 * QUIET_ZONE_MODULES - VERSION_OVERHEAD) / MODULES_PER_VERSION

class QrEncoderTest {
    @Test
    fun `a minimal totp entry scans back as the uri it was built from`() {
        rendersBackTo(OtpAuthUri(OtpType.TOTP, "alice", SECRET))
    }

    @Test
    fun `a totp entry with an issuer scans back as the uri it was built from`() {
        rendersBackTo(OtpAuthUri(OtpType.TOTP, "alice@example.com", SECRET, issuer = "GitHub"))
    }

    @Test
    fun `a totp entry with SHA-256 and eight digits scans back as the uri it was built from`() {
        rendersBackTo(
            OtpAuthUri(
                type = OtpType.TOTP,
                accountName = "alice",
                secret = SECRET,
                issuer = "GitHub",
                algorithm = HashAlgorithm.SHA256,
                digits = 8,
            ),
        )
    }

    @Test
    fun `a totp entry with SHA-512 and a non-default period scans back as the uri it was built from`() {
        rendersBackTo(
            OtpAuthUri(
                type = OtpType.TOTP,
                accountName = "alice",
                secret = SECRET,
                issuer = "GitHub",
                algorithm = HashAlgorithm.SHA512,
                digits = 7,
                period = 60,
            ),
        )
    }

    @Test
    fun `a totp entry whose names need escaping scans back as the uri it was built from`() {
        rendersBackTo(OtpAuthUri(OtpType.TOTP, "alice smith@bigco.com", SECRET, issuer = "Big Corporation"))
    }

    @Test
    fun `a totp entry with non-ASCII names scans back as the uri it was built from`() {
        rendersBackTo(OtpAuthUri(OtpType.TOTP, "álice", SECRET, issuer = "Übercorp ✓"))
    }

    @Test
    fun `a minimal hotp entry scans back as the uri it was built from`() {
        rendersBackTo(OtpAuthUri(OtpType.HOTP, "alice", SECRET, period = null, counter = 0uL))
    }

    @Test
    fun `an hotp entry at the 64-bit counter maximum scans back as the uri it was built from`() {
        rendersBackTo(
            OtpAuthUri(
                type = OtpType.HOTP,
                accountName = "alice",
                secret = SECRET,
                issuer = "Acme",
                algorithm = HashAlgorithm.SHA512,
                digits = 8,
                period = null,
                counter = ULong.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `a symbol is encoded at error correction level M`() {
        val symbol = encoded(OtpAuthUri(OtpType.TOTP, "alice", SECRET, issuer = "GitHub"))

        assertEquals("M", scanned(symbol).resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    @Test
    fun `a symbol carries a quiet zone two modules deep on every side`() {
        val symbol = encoded(OtpAuthUri(OtpType.TOTP, "alice", SECRET, issuer = "GitHub"))
        val edge = symbol.width - 1

        val lit = (0 until symbol.width).flatMap { along ->
            (0 until QUIET_ZONE_MODULES).flatMap { depth ->
                listOf(depth to along, edge - depth to along, along to depth, along to edge - depth)
            }
        }.filter { (x, y) -> symbol.isDark(x, y) }

        assertEquals(emptyList(), lit)
    }

    @Test
    fun `a symbol places its finder pattern where the quiet zone ends`() {
        val symbol = encoded(OtpAuthUri(OtpType.TOTP, "alice", SECRET, issuer = "GitHub"))

        assertTrue(symbol.isDark(QUIET_ZONE_MODULES, QUIET_ZONE_MODULES))
    }

    @Test
    fun `a 160-bit secret under a 64-character label stays inside the scannable versions`() {
        val symbol = encoded(
            OtpAuthUri(OtpType.TOTP, LONG_ACCOUNT, SECRET, issuer = LONG_ISSUER),
        )

        assertTrue(versionOf(symbol) <= LARGEST_SCANNABLE_VERSION, "version ${versionOf(symbol)}")
    }

    @Test
    fun `text outside ASCII scans back verbatim`() {
        val symbol = checkNotNull(QrEncoder.encode(NON_ASCII_TEXT))

        assertEquals(NON_ASCII_TEXT, scanned(symbol).text)
    }

    @Test
    fun `text past the format's capacity has no symbol`() {
        assertNull(QrEncoder.encode("a".repeat(4096)))
    }

    @Test
    fun `a saved image is a png`() {
        val bytes = qrPngBytes(encoded(OtpAuthUri(OtpType.TOTP, "alice", SECRET)))

        assertContentEquals(PNG_MAGIC, bytes.copyOf(PNG_MAGIC.size))
    }

    // A scanner reads the image rather than the grid, so what the file carries is asserted by reading
    // it back the way one would.
    @Test
    fun `a saved image scans back as the uri it was built from`() {
        val uri = OtpAuthUri(OtpType.TOTP, "alice@example.com", SECRET, issuer = "GitHub")
        val image = ImageIO.read(ByteArrayInputStream(qrPngBytes(encoded(uri))))

        assertEquals(uri.build(), read(image).text)
    }

    @Test
    fun `a saved image lays every module down whole`() {
        val symbol = encoded(OtpAuthUri(OtpType.TOTP, "alice", SECRET))
        val image = ImageIO.read(ByteArrayInputStream(qrPngBytes(symbol)))

        assertEquals(0, image.width % symbol.width)
    }

    // Whole modules come first: the image reaches the size asked of it only where that size divides
    // into them, and overshooting it would put module edges on fractional pixels.
    @Test
    fun `a saved image is as close to the size asked for as whole modules reach`() {
        val symbol = encoded(OtpAuthUri(OtpType.TOTP, "alice", SECRET))
        val image = ImageIO.read(ByteArrayInputStream(qrPngBytes(symbol, targetPx = 512)))

        assertEquals(symbol.width * (512 / symbol.width), image.width)
    }

    @Test
    fun `a target smaller than the symbol still lays down one pixel a module`() {
        val symbol = encoded(OtpAuthUri(OtpType.TOTP, "alice", SECRET))
        val image = ImageIO.read(ByteArrayInputStream(qrPngBytes(symbol, targetPx = 1)))

        assertEquals(symbol.width, image.width)
    }
}
