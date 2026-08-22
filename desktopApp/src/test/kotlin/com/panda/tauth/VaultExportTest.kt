package com.panda.tauth

import com.panda.tauth.vault.ExportFormat
import com.panda.tauth.vault.VaultError
import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

// Stand-in ciphertext: the export path never reads what it copies.
private val EXPORTED = byteArrayOf(1, 2, 3, 4, 5)

// Stand-in plaintext, for the same reason. What the accounts render to is the vault package's.
private const val ACCOUNTS = "otpauth://totp/GitHub:alice?secret=AAAA\n"

class VaultExportTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        // Tests never write outside a temp directory and never touch the real vault path.
        root = Files.createTempDirectory("tauth-export")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun destination(): Path = root.resolve("vault-export.tauth")

    // Read over the whole directory rather than over one name, since a declined destination names no
    // file for an assertion to look at.
    @Test
    fun `a declined destination creates nothing`() {
        runBlocking { exportVault(EXPORTED) { null } }

        assertEquals(emptyList(), Files.list(root).use { it.toList() })
    }

    // Nothing was written and nothing went wrong, so the screen has nothing to report.
    @Test
    fun `a declined destination reports no failure`() {
        val outcome = runBlocking { exportVault(EXPORTED) { null } }

        assertIs<Outcome.Success<Unit>>(outcome)
    }

    @Test
    fun `a chosen destination receives the bytes`() {
        runBlocking { exportVault(EXPORTED) { destination() } }

        assertContentEquals(EXPORTED, Files.readAllBytes(destination()))
    }

    @Test
    fun `a plaintext export receives the text it was given`() {
        runBlocking { exportPlaintext(ACCOUNTS, ExportFormat.URI_LIST) { root.resolve(it) } }

        assertEquals(ACCOUNTS, Files.readString(root.resolve("vault-accounts.txt")))
    }

    // Every secret in the vault in the clear, so it is created the way the encrypted copy is.
    @Test
    fun `a plaintext export is readable by its owner alone`() {
        runBlocking { exportPlaintext(ACCOUNTS, ExportFormat.URI_LIST) { root.resolve(it) } }

        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("vault-accounts.txt"))),
        )
    }

    // The name the dialog opens on says which of the two formats it is, since neither reads as the
    // other and a file named for the wrong one is opened by the wrong thing.
    @Test
    fun `a json export is named as json`() {
        assertEquals("vault-accounts.json", plaintextFileName(ExportFormat.JSON))
    }

    @Test
    fun `a uri list export is named as text`() {
        assertEquals("vault-accounts.txt", plaintextFileName(ExportFormat.URI_LIST))
    }

    @Test
    fun `a declined destination writes no accounts`() {
        runBlocking { exportPlaintext(ACCOUNTS, ExportFormat.JSON) { null } }

        assertEquals(emptyList(), Files.list(root).use { it.toList() })
    }

    @Test
    fun `a declined destination reports no failure from a plaintext export`() {
        val outcome = runBlocking { exportPlaintext(ACCOUNTS, ExportFormat.JSON) { null } }

        assertIs<Outcome.Success<Unit>>(outcome)
    }

    @Test
    fun `a chosen file is read whole`() {
        val source = root.resolve("accounts.txt")
        Files.writeString(source, ACCOUNTS)

        assertEquals(ACCOUNTS, runBlocking { readImportSource { source } }.valueOrNull)
    }

    // Declining the picker is nothing to import and nothing to report.
    @Test
    fun `a declined file is read as nothing`() {
        val outcome = runBlocking { readImportSource { null } }

        assertIs<Outcome.Success<String?>>(outcome)
        assertNull(outcome.value)
    }

    // A file chosen by hand can be any size and readString takes it whole, so the ceiling is what
    // stands between it and an OutOfMemoryError no Outcome carries.
    @Test
    fun `a file past the size a vault reaches is refused`() {
        val source = root.resolve("enormous")
        RandomAccessFile(source.toFile(), "rw").use { it.setLength(17L * 1024 * 1024) }

        assertIs<VaultError.Corrupt>(runBlocking { readImportSource { source } }.errorOrNull)
    }

    @Test
    fun `a file inside that size is read`() {
        val source = root.resolve("large")
        RandomAccessFile(source.toFile(), "rw").use { it.setLength(15L * 1024 * 1024) }

        assertIs<Outcome.Success<String?>>(runBlocking { readImportSource { source } })
    }

    @Test
    fun `a file that is not text is refused`() {
        val source = root.resolve("bytes")
        Files.write(source, byteArrayOf(0xC3.toByte(), 0x28))

        assertIs<VaultError.Corrupt>(runBlocking { readImportSource { source } }.errorOrNull)
    }

    // The decoder's own message names the bytes it stopped on, and the file may hold every secret in
    // a vault.
    @Test
    fun `a file that is not text is refused without quoting it`() {
        val source = root.resolve("bytes")
        Files.write(source, ACCOUNTS.encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28))

        val refusal = runBlocking { readImportSource { source } }.errorOrNull as VaultError.Corrupt

        assertFalse("GitHub" in refusal.detail)
    }

    @Test
    fun `a file that could not be read reports the failure`() {
        val source = root.resolve("directory")
        Files.createDirectories(source)

        assertIs<VaultError.Io>(runBlocking { readImportSource { source } }.errorOrNull)
    }
}
