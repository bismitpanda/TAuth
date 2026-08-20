package com.panda.tauth

import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.OtpType
import com.panda.tauth.ui.qr.QrSymbol
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// Base32 of the RFC 4226 seed "12345678901234567890".
private const val SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

private val GITHUB = OtpAuthUri(OtpType.TOTP, "alice", SECRET, issuer = "GitHub")
private val ZENDESK = OtpAuthUri(OtpType.HOTP, "bob", SECRET, period = null, counter = 41uL)

// Enough pixels a module that the binarizer has a block to average rather than a single sample.
private const val SCALE = 8

private const val MARGIN = 24

private fun symbolOf(text: String): QrSymbol = checkNotNull(QrEncoder.encode(text))

// The codes laid side by side on one canvas, which is what a screenshot of a page of them looks like.
private fun imageOf(vararg texts: String): BufferedImage {
    val symbols = texts.map(::symbolOf)
    val height = symbols.maxOf { it.width } * SCALE + MARGIN * 2
    val width = symbols.sumOf { it.width * SCALE + MARGIN } + MARGIN
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, width, height)
    graphics.color = Color.BLACK
    var left = MARGIN
    for (symbol in symbols) {
        for (y in 0 until symbol.width) {
            for (x in 0 until symbol.width) {
                if (symbol.isDark(x, y)) {
                    graphics.fillRect(left + x * SCALE, MARGIN + y * SCALE, SCALE, SCALE)
                }
            }
        }
        left += symbol.width * SCALE + MARGIN
    }
    graphics.dispose()
    return image
}

class QrDecoderTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        // Tests never write outside a temp directory and never touch the real vault path.
        root = Files.createTempDirectory("tauth-scan")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `an image holding one code reads it`() {
        assertContentEquals(listOf(GITHUB.build()), decodeQrCodes(imageOf(GITHUB.build())))
    }

    // One screenshot can hold a page of them, so the reader takes every code rather than the first.
    @Test
    fun `an image holding several codes reads all of them`() {
        val read = decodeQrCodes(imageOf(GITHUB.build(), ZENDESK.build()))

        assertEquals(setOf(GITHUB.build(), ZENDESK.build()), read.toSet())
    }

    @Test
    fun `an image holding no code reads nothing`() {
        val blank = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)

        assertEquals(emptyList(), decodeQrCodes(blank))
    }

    // A code carrying anything at all is read as what it is; whether it is an account is decided
    // where the accounts are, not here.
    @Test
    fun `a code that is not an account is read as what it holds`() {
        assertContentEquals(listOf("https://example.com"), decodeQrCodes(imageOf("https://example.com")))
    }

    @Test
    fun `a chosen image is read`() {
        val source = root.resolve("code.png")
        ImageIO.write(imageOf(GITHUB.build()), "png", source.toFile())

        val outcome = runBlocking { readQrImage { source } }

        assertContentEquals(listOf(GITHUB.build()), outcome.valueOrNull)
    }

    @Test
    fun `a declined image is read as nothing`() {
        val outcome = runBlocking { readQrImage { null } }

        assertIs<Outcome.Success<List<String>?>>(outcome)
        assertNull(outcome.value)
    }

    @Test
    fun `a file that is not an image is refused`() {
        val source = root.resolve("notes.txt")
        Files.writeString(source, "this is not a picture")

        assertIs<VaultError.Corrupt>(runBlocking { readQrImage { source } }.errorOrNull)
    }

    @Test
    fun `a file that could not be read reports the failure`() {
        val source = root.resolve("directory")
        Files.createDirectories(source)

        assertIs<VaultError.Io>(runBlocking { readQrImage { source } }.errorOrNull)
    }

    @Test
    fun `a pasted image is read`() {
        val outcome = runBlocking { readQrClipboard { imageOf(GITHUB.build()) } }

        assertContentEquals(listOf(GITHUB.build()), outcome.valueOrNull)
    }

    @Test
    fun `every code in a pasted image is read`() {
        val outcome = runBlocking { readQrClipboard { imageOf(GITHUB.build(), ZENDESK.build()) } }

        assertEquals(2, outcome.valueOrNull?.size)
    }

    // Declining a file dialog is nothing to report; pressing paste against a clipboard holding a
    // screenshot of nothing, or holding text, is a question that was asked and needs answering.
    @Test
    fun `a clipboard holding no image is refused rather than passed over`() {
        assertIs<VaultError.Corrupt>(runBlocking { readQrClipboard { null } }.errorOrNull)
    }

    @Test
    fun `a clipboard holding no image says which`() {
        val error = runBlocking { readQrClipboard { null } }.errorOrNull

        assertEquals("the clipboard holds no image", (error as VaultError.Corrupt).detail)
    }

    @Test
    fun `a pasted image holding no code is read as no codes`() {
        val blank = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)

        assertContentEquals(emptyList(), runBlocking { readQrClipboard { blank } }.valueOrNull)
    }
}
