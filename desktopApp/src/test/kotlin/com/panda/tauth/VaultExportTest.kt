package com.panda.tauth

import com.panda.tauth.ui.settings.ExportError
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

// Stand-in ciphertext: the export path never reads what it copies.
private val EXPORTED = byteArrayOf(1, 2, 3, 4, 5)
private val SHORTER = byteArrayOf(9)

// What a mount that takes the mode passed to open(2) and discards it reports back: the creation
// attribute was accepted and the file is world-readable anyway.
private class DiscardingMount(private val reported: String) : ExportAccess {
    override fun openChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>) =
        SystemExportAccess.openChannel(path, options, *attributes)

    override fun permissionsOf(path: Path): Set<PosixFilePermission> = PosixFilePermissions.fromString(reported)
}

// A destination that creates the file and then refuses the bytes: a disk that fills, or a removable
// one unplugged between the two. The file is created for real, so what is left behind is observable.
private class RefusedWrite : ExportAccess {
    override fun openChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): FileChannel {
        SystemExportAccess.openChannel(path, options, *attributes).close()
        return RefusingChannel()
    }

    override fun permissionsOf(path: Path): Set<PosixFilePermission> = SystemExportAccess.permissionsOf(path)
}

private class RefusingChannel : FileChannel() {
    override fun write(src: ByteBuffer): Int = throw IOException("no space left on device")

    // Closing is what `use` does on the way out of the failure above, so it is the one other member
    // this answers.
    override fun implCloseChannel() = Unit

    override fun read(dst: ByteBuffer): Int = unreached()

    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = unreached()

    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = unreached()

    override fun position(): Long = unreached()

    override fun position(newPosition: Long): FileChannel = unreached()

    override fun size(): Long = unreached()

    override fun truncate(size: Long): FileChannel = unreached()

    override fun force(metaData: Boolean) = unreached()

    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = unreached()

    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = unreached()

    override fun read(dst: ByteBuffer, position: Long): Int = unreached()

    override fun write(src: ByteBuffer, position: Long): Int = unreached()

    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = unreached()

    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = unreached()

    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = unreached()

    private fun unreached(): Nothing = throw UnsupportedOperationException("the export calls write and close alone")
}

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

    private fun modeOf(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun `an export writes the bytes it was given`() {
        writeExport(destination(), EXPORTED)

        assertContentEquals(EXPORTED, Files.readAllBytes(destination()))
    }

    @Test
    fun `an export reports success`() {
        assertIs<Outcome.Success<Unit>>(writeExport(destination(), EXPORTED))
    }

    // A copy of the vault is worth what the vault is worth to anyone who can guess the password. This
    // and the pair below read a real mode, so they distinguish a mode this set from one the umask
    // happened to give only where that umask is looser than 0077.
    @Test
    fun `an export is readable by its owner alone`() {
        writeExport(destination(), EXPORTED)

        assertEquals("rw-------", modeOf(destination()))
    }

    // The end state above says nothing about the window between the two: a file created at the umask
    // and restricted after the ciphertext is in it is readable for the length of the write, and a
    // descriptor opened in that window survives the restriction.
    @Test
    fun `an export is restricted before any ciphertext reaches it`() {
        var modeAtWrite: String? = null

        writeExport(destination(), EXPORTED) { path -> modeAtWrite = modeOf(path) }

        assertEquals("rw-------", modeAtWrite)
    }

    // A mount that ignores the mode it was handed leaves the copy readable, and the creation
    // attribute alone cannot tell: only reading it back can.
    @Test
    fun `an export to a mount that discards the mode reports the destination refusing it`() {
        val outcome = writeExport(destination(), EXPORTED, DiscardingMount("rw-r--r--"))

        assertEquals(ExportError.NotRestricted, (outcome as Outcome.Failure).error)
    }

    @Test
    fun `an export to a mount that discards the mode writes no ciphertext`() {
        writeExport(destination(), EXPORTED, DiscardingMount("rw-r--r--"))

        assertFalse(Files.exists(destination()))
    }

    @Test
    fun `an export to a mount that reports the mode it was given is written`() {
        // The pair holds everything but the mode fixed, so the case above turns on that alone.
        writeExport(destination(), EXPORTED, DiscardingMount("rw-------"))

        assertContentEquals(EXPORTED, Files.readAllBytes(destination()))
    }

    // The moment between the copy being created and the ciphertext reaching it belongs to whoever can
    // write to the destination directory. Reopening by name would put the vault wherever a link left
    // in that window points.
    @Test
    fun `an export writes into the file it created rather than into the name`() {
        val decoy = root.resolve("decoy")
        Files.write(decoy, SHORTER)

        writeExport(destination(), EXPORTED) { path ->
            Files.delete(path)
            Files.createSymbolicLink(path, decoy)
        }

        assertContentEquals(SHORTER, Files.readAllBytes(decoy))
    }

    // An existing file keeps whatever mode it was given, so the copy replaces it rather than opening
    // it.
    @Test
    fun `an export over a readable file leaves it readable by its owner alone`() {
        Files.write(destination(), SHORTER)
        Files.setPosixFilePermissions(destination(), PosixFilePermissions.fromString("rw-rw-rw-"))

        writeExport(destination(), EXPORTED)

        assertEquals("rw-------", modeOf(destination()))
    }

    @Test
    fun `an export over a longer file leaves no tail of it behind`() {
        writeExport(destination(), EXPORTED)

        writeExport(destination(), SHORTER)

        assertContentEquals(SHORTER, Files.readAllBytes(destination()))
    }

    @Test
    fun `an export onto a directory reports the failure`() {
        Files.createDirectories(destination())

        val outcome = writeExport(destination(), EXPORTED)

        assertIs<ExportError.Io>((outcome as Outcome.Failure).error)
    }

    @Test
    fun `an export into a directory that refuses a new file reports the failure`() {
        val unwritable = root.resolve("locked")
        Files.createDirectories(unwritable)
        Files.setPosixFilePermissions(unwritable, PosixFilePermissions.fromString("r-x------"))

        val outcome = writeExport(unwritable.resolve("vault-export.tauth"), EXPORTED)

        assertIs<ExportError.Io>((outcome as Outcome.Failure).error)
    }

    // The file exists by the time the write fails, and an empty one under the name of a vault export
    // reads as a backup while being none.
    @Test
    fun `an export whose write fails leaves no file behind`() {
        writeExport(destination(), EXPORTED, RefusedWrite())

        assertFalse(Files.exists(destination()))
    }

    @Test
    fun `an export whose write fails reports the failure`() {
        val outcome = writeExport(destination(), EXPORTED, RefusedWrite())

        assertIs<ExportError.Io>((outcome as Outcome.Failure).error)
    }

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
}
