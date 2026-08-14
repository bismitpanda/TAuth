package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.valueOrNull
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val OWNER_ONLY_MODE = PosixFilePermissions.fromString("rw-------")
private val DIRECTORY_OWNER_ONLY_MODE = PosixFilePermissions.fromString("rwx------")

class VaultStoreTest {
    private lateinit var directory: Path
    private lateinit var paths: VaultPaths
    private lateinit var store: VaultStore

    @BeforeTest
    fun setUp() {
        // Tests never touch the real vault path.
        directory = Files.createTempDirectory("tauth-store")
        paths = VaultPaths(OperatingSystem.LINUX, { directory.toString() }, directory.toString())
        store = VaultStore(paths)
    }

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    private fun vaultBytes(body: VaultBody = VaultBody()) = VaultCodec.create("pw".toCharArray(), body)

    @Test
    fun `a written vault reads back byte for byte`() {
        val bytes = vaultBytes()
        store.write(bytes)
        assertContentEquals(bytes, store.read().valueOrNull)
    }

    @Test
    fun `a second write replaces the first`() {
        store.write(vaultBytes())
        val second = vaultBytes(VaultBody(entries = listOf(totpEntry())))
        store.write(second)
        assertContentEquals(second, store.read().valueOrNull)
    }

    @Test
    fun `reading a path with no vault reports no vault file`() {
        assertEquals(VaultError.NoVaultFile, store.read().errorOrNull)
    }

    @Test
    fun `exists is false before the first write`() {
        assertFalse(store.exists())
    }

    @Test
    fun `exists is true after a write`() {
        store.write(vaultBytes())
        assertTrue(store.exists())
    }

    @Test
    fun `a successful write leaves no temp file`() {
        store.write(vaultBytes())
        assertFalse(Files.exists(paths.tempFile))
    }

    @Test
    fun `the written file is readable and writable by the owner alone`() {
        store.write(vaultBytes())
        assertEquals(OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.vaultFile))
    }

    @Test
    fun `the vault directory is reachable by the owner alone`() {
        // A traversable directory leaks every account name and lets another local user replace the
        // vault however tight the file's own mode is.
        store.write(vaultBytes())
        assertEquals(DIRECTORY_OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.directory))
    }

    @Test
    fun `the lock file is readable and writable by the owner alone`() {
        store.write(vaultBytes())
        assertEquals(OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.lockFile))
    }

    @Test
    fun `a directory that already exists too widely is tightened`() {
        Files.createDirectories(paths.directory)
        Files.setPosixFilePermissions(paths.directory, PosixFilePermissions.fromString("rwxrwxrwx"))
        store.write(vaultBytes())
        assertEquals(DIRECTORY_OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.directory))
    }

    @Test
    fun `a lock file left too widely by anyone else is tightened`() {
        Files.createDirectories(paths.directory)
        Files.createFile(paths.lockFile)
        Files.setPosixFilePermissions(paths.lockFile, PosixFilePermissions.fromString("rw-rw-rw-"))
        store.write(vaultBytes())
        assertEquals(OWNER_ONLY_MODE, Files.getPosixFilePermissions(paths.lockFile))
    }

    @Test
    fun `the write creates the vault directory when it is missing`() {
        val nested = directory.resolve("missing")
        val store = VaultStore(VaultPaths(OperatingSystem.LINUX, { nested.toString() }, nested.toString()))
        assertIs<Outcome.Success<Unit>>(store.write(vaultBytes()))
    }

    @Test
    fun `a failed write leaves the previous vault intact`() {
        val original = vaultBytes()
        store.write(original)
        // A non-empty directory where the temp file belongs cannot be deleted or created over, so
        // the write fails at its first step with the original still in place.
        Files.createDirectory(paths.tempFile)
        Files.createFile(paths.tempFile.resolve("blocker"))
        val failed = store.write(vaultBytes(VaultBody(entries = listOf(totpEntry()))))
        assertIs<VaultError.Io>(failed.errorOrNull)
        paths.tempFile.toFile().deleteRecursively()
        assertContentEquals(original, store.read().valueOrNull)
    }

    // A NUL is the one byte no supported platform allows in a path, so Path.of raises
    // InvalidPathException — unchecked, and raised while resolving, before any try block the
    // callers own. Built rather than written as a literal, which no editor renders.
    private fun unnameable(): VaultStore {
        val location = "/tmp/ta" + Char(0) + "uth"
        return VaultStore(VaultPaths(OperatingSystem.LINUX, { location }, "/tmp"))
    }

    @Test
    fun `writing to a location that cannot be named reports an error rather than throwing`() {
        assertIs<VaultError.Io>(unnameable().write(vaultBytes()).errorOrNull)
    }

    @Test
    fun `reading a location that cannot be named reports an error rather than throwing`() {
        assertIs<VaultError.Io>(unnameable().read().errorOrNull)
    }

    @Test
    fun `a location that does not resolve is refused rather than written to the working directory`() {
        val unresolved = VaultStore(VaultPaths(OperatingSystem.LINUX, { null }, ""))
        assertIs<VaultError.Io>(unresolved.write(vaultBytes()).errorOrNull)
    }

    @Test
    fun `a location that does not resolve holds no vault`() {
        assertFalse(VaultStore(VaultPaths(OperatingSystem.LINUX, { null }, "")).exists())
    }

    @Test
    fun `a location that cannot be named holds no vault`() {
        assertFalse(unnameable().exists())
    }

    @Test
    fun `a file larger than a vault can be is corrupt rather than an allocation`() {
        // Sparse, so the test costs no disk: only the reported size matters.
        store.write(vaultBytes())
        RandomAccessFile(paths.vaultFile.toFile(), "rw").use { it.setLength(MAX_VAULT_BYTES + 1L) }
        assertIs<VaultError.Corrupt>(store.read().errorOrNull)
    }

    @Test
    fun `every concurrent write from two stores succeeds`() {
        assertTrue(concurrentWrites().all { it.second is Outcome.Success })
    }

    @Test
    fun `the file left by concurrent writes is one whole payload, not a mixture`() {
        // What "serialise" has to mean observably: no interleaving, so the survivor is byte-for-byte
        // one of the payloads written rather than a blend of two.
        val payloads = concurrentWrites().map { it.first }
        val survivor = checkNotNull(store.read().valueOrNull)
        assertTrue(payloads.any { it.contentEquals(survivor) })
    }

    @Test
    fun `concurrent writes leave no temp file behind`() {
        concurrentWrites()
        assertFalse(Files.exists(paths.tempFile))
    }

    // Eight writes across two stores, every result collected so a failure cannot pass unnoticed.
    private fun concurrentWrites(): List<Pair<ByteArray, Outcome<Unit, VaultError>>> {
        val other = VaultStore(paths)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val submitted = (0 until 8).map { round ->
            val target = if (round % 2 == 0) store else other
            val payload = vaultBytes(VaultBody(entries = listOf(totpEntry(id = "id-$round"))))
            payload to pool.submit<Outcome<Unit, VaultError>> {
                start.await()
                target.write(payload)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        return submitted.map { (payload, future) -> payload to future.get() }
    }
}
