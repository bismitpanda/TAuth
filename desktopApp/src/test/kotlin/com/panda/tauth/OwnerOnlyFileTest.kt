package com.panda.tauth

import com.panda.tauth.ui.qr.QrSymbol
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

// Stand-in content: the write path never reads what it puts down.
private val WRITTEN = byteArrayOf(1, 2, 3, 4, 5)
private val SHORTER = byteArrayOf(9)

// Three modules on a side, which is no real symbol and is all a rendering needs to produce a file.
private val SYMBOL = QrSymbol(3, BooleanArray(9) { it % 2 == 0 })

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

    private fun unreached(): Nothing = throw UnsupportedOperationException("the write calls write and close alone")
}

class OwnerOnlyFileTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        // Tests never write outside a temp directory and never touch the real vault path.
        root = Files.createTempDirectory("tauth-owner-only")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun destination(): Path = root.resolve("restricted")

    private fun modeOf(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun `a restricted write puts down the bytes it was given`() {
        writeOwnerOnly(destination(), WRITTEN)

        assertContentEquals(WRITTEN, Files.readAllBytes(destination()))
    }

    @Test
    fun `a restricted write reports success`() {
        assertIs<Outcome.Success<Unit>>(writeOwnerOnly(destination(), WRITTEN))
    }

    // This and the pair below read a real mode, so they tell a mode this set from one the umask
    // happened to give only where that umask is looser than 0077.
    @Test
    fun `a restricted write is readable by its owner alone`() {
        writeOwnerOnly(destination(), WRITTEN)

        assertEquals("rw-------", modeOf(destination()))
    }

    // The end state above says nothing about the window between the two: a file restricted after the
    // content is in it is readable for the length of the write, and a descriptor opened then survives.
    @Test
    fun `a restricted write is restricted before any content reaches it`() {
        var modeAtWrite: String? = null

        writeOwnerOnly(destination(), WRITTEN) { path -> modeAtWrite = modeOf(path) }

        assertEquals("rw-------", modeAtWrite)
    }

    // A mount that ignores the mode it was handed leaves the file readable, and the creation attribute
    // alone cannot tell: only reading it back can.
    @Test
    fun `a write to a mount that discards the mode reports the destination refusing it`() {
        val outcome = writeOwnerOnly(destination(), WRITTEN, DiscardingMount("rw-r--r--"))

        assertEquals(ExportError.NotRestricted, (outcome as Outcome.Failure).error)
    }

    @Test
    fun `a write to a mount that discards the mode puts down no content`() {
        writeOwnerOnly(destination(), WRITTEN, DiscardingMount("rw-r--r--"))

        assertFalse(Files.exists(destination()))
    }

    @Test
    fun `a write to a mount that reports the mode it was given is written`() {
        // The pair holds everything but the mode fixed, so the case above turns on that alone.
        writeOwnerOnly(destination(), WRITTEN, DiscardingMount("rw-------"))

        assertContentEquals(WRITTEN, Files.readAllBytes(destination()))
    }

    // The moment between the file being created and the content reaching it belongs to whoever can
    // write to that directory, so reopening by name would follow a link left there in the meantime.
    @Test
    fun `a restricted write goes into the file it created rather than into the name`() {
        val decoy = root.resolve("decoy")
        Files.write(decoy, SHORTER)

        writeOwnerOnly(destination(), WRITTEN) { path ->
            Files.delete(path)
            Files.createSymbolicLink(path, decoy)
        }

        assertContentEquals(SHORTER, Files.readAllBytes(decoy))
    }

    // An existing file keeps whatever mode it was given, so the write replaces it rather than opening
    // it.
    @Test
    fun `a restricted write over a readable file leaves it readable by its owner alone`() {
        Files.write(destination(), SHORTER)
        Files.setPosixFilePermissions(destination(), PosixFilePermissions.fromString("rw-rw-rw-"))

        writeOwnerOnly(destination(), WRITTEN)

        assertEquals("rw-------", modeOf(destination()))
    }

    @Test
    fun `a restricted write over a longer file leaves no tail of it behind`() {
        writeOwnerOnly(destination(), WRITTEN)

        writeOwnerOnly(destination(), SHORTER)

        assertContentEquals(SHORTER, Files.readAllBytes(destination()))
    }

    @Test
    fun `a restricted write onto a directory reports the failure`() {
        Files.createDirectories(destination())

        val outcome = writeOwnerOnly(destination(), WRITTEN)

        assertIs<ExportError.Io>((outcome as Outcome.Failure).error)
    }

    @Test
    fun `a restricted write into a directory that refuses a new file reports the failure`() {
        val unwritable = root.resolve("locked")
        Files.createDirectories(unwritable)
        Files.setPosixFilePermissions(unwritable, PosixFilePermissions.fromString("r-x------"))

        val outcome = writeOwnerOnly(unwritable.resolve("restricted"), WRITTEN)

        assertIs<ExportError.Io>((outcome as Outcome.Failure).error)
    }

    // The file exists by the time the write fails, and an empty one under the name of an export reads
    // as a backup while being none.
    @Test
    fun `a restricted write that fails leaves no file behind`() {
        writeOwnerOnly(destination(), WRITTEN, RefusedWrite())

        assertFalse(Files.exists(destination()))
    }

    @Test
    fun `a restricted write that fails reports the failure`() {
        val outcome = writeOwnerOnly(destination(), WRITTEN, RefusedWrite())

        assertIs<ExportError.Io>((outcome as Outcome.Failure).error)
    }

    // A QR image is a complete credential in the one form a camera reads, so it goes down the same
    // restricted path a copy of the vault does rather than out through a plain write.
    @Test
    fun `a saved qr image is readable by its owner alone`() {
        runBlocking { saveQrImage(SYMBOL) { destination() } }

        assertEquals("rw-------", modeOf(destination()))
    }

    @Test
    fun `a saved qr image is the image the symbol renders to`() {
        runBlocking { saveQrImage(SYMBOL) { destination() } }

        assertContentEquals(qrPngBytes(SYMBOL), Files.readAllBytes(destination()))
    }

    @Test
    fun `a declined destination saves no qr image`() {
        runBlocking { saveQrImage(SYMBOL) { null } }

        assertEquals(emptyList(), Files.list(root).use { it.toList() })
    }

    @Test
    fun `a declined destination reports no failure from a qr image`() {
        val outcome = runBlocking { saveQrImage(SYMBOL) { null } }

        assertIs<Outcome.Success<Unit>>(outcome)
    }
}
