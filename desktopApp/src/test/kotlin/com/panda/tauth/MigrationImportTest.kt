package com.panda.tauth

import com.panda.tauth.ui.qr.QrSymbol
import com.panda.tauth.vault.ImportReadError
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// A file rather than an encode this test performs, so what it reads cannot be an agreement between
// the encoder and the decoder.
private const val GOLDEN_IMAGE = "src/test/resources/google-export.png"

// What that image carries: two accounts over RFC 4226 §5.1's published seed, as part one of two.
private const val GOLDEN_URI = "otpauth-migration://offline?data=CicKFDEyMzQ1Njc4OTAxMjM0NTY3ODkwEgVhbGljZR" +
    "oGR2l0SHViMAIKHwoUMTIzNDU2Nzg5MDEyMzQ1Njc4OTASA2JvYjABOCkYAiAA"

private const val ACCOUNT_URI = "otpauth://totp/GitHub:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

private const val SCALE = 8
private const val MARGIN = 24
private const val BLANK_PX = 64

class MigrationImportTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("tauth-migration")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `an export code is read off the image that carries it`() {
        assertEquals(GOLDEN_URI, read(Path.of(GOLDEN_IMAGE)).valueOrNull)
    }

    @Test
    fun `an image the user declined is nothing to read`() {
        assertNull(read(null).valueOrNull)
    }

    // The add screen's own scan reads these, and taking one here would offer a single account where
    // the user asked to import a vault's worth.
    @Test
    fun `an image carrying an account code rather than an export code is refused`() {
        assertIs<VaultError.Corrupt>(read(imageOf(checkNotNull(QrEncoder.encode(ACCOUNT_URI)))).errorOrNull)
    }

    @Test
    fun `an image holding no code at all is refused`() {
        assertIs<VaultError.Corrupt>(read(imageOf(null)).errorOrNull)
    }

    private fun read(path: Path?): Outcome<String?, ImportReadError> = runBlocking { readExportCode { path } }

    private fun imageOf(symbol: QrSymbol?): Path {
        val side = symbol?.let { it.width * SCALE + MARGIN * 2 } ?: BLANK_PX
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, side, side)
        graphics.color = Color.BLACK
        symbol?.let {
            for (y in 0 until it.width) {
                for (x in 0 until it.width) {
                    if (it.isDark(x, y)) graphics.fillRect(MARGIN + x * SCALE, MARGIN + y * SCALE, SCALE, SCALE)
                }
            }
        }
        graphics.dispose()
        return root.resolve("code.png").also { ImageIO.write(image, "png", it.toFile()) }
    }
}
