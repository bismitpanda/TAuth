package com.panda.tauth.settings

import com.panda.tauth.Outcome
import com.panda.tauth.errorOrNull
import com.panda.tauth.vault.OperatingSystem
import com.panda.tauth.vault.VaultPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The file is plaintext and anything running as the user can rewrite it, so every shape below has to
// end in a usable set of preferences rather than a startup failure.
class PreferencesStoreTest {
    private lateinit var root: Path
    private lateinit var paths: VaultPaths
    private lateinit var store: PreferencesStore

    @BeforeTest
    fun setUp() {
        // Tests never touch the real preferences path.
        root = Files.createTempDirectory("tauth-preferences")
        paths = VaultPaths(OperatingSystem.LINUX, { root.toString() }, root.toString())
        store = PreferencesStore(paths)
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun writeFile(text: String) {
        Files.createDirectories(paths.directory)
        Files.writeString(paths.preferencesFile, text)
    }

    private fun fileText(): String = Files.readString(paths.preferencesFile)

    @Test
    fun `an absent directory yields the defaults`() {
        assertEquals(Preferences(), store.load())
    }

    @Test
    fun `an absent file yields the defaults`() {
        Files.createDirectories(paths.directory)
        assertEquals(Preferences(), store.load())
    }

    @Test
    fun `reading creates nothing`() {
        store.load()
        assertFalse(Files.exists(paths.directory))
    }

    @Test
    fun `a file of zero bytes yields the defaults`() {
        writeFile("")
        assertEquals(Preferences(), store.load())
    }

    @Test
    fun `a truncated file yields the defaults`() {
        writeFile("""{"theme":"da""")
        assertEquals(Preferences(), store.load())
    }

    @Test
    fun `an array where the object belongs yields the defaults`() {
        writeFile("[]")
        assertEquals(Preferences(), store.load())
    }

    @Test
    fun `a file of unknown keys yields the defaults`() {
        writeFile("""{"lockOnFullMoon":true}""")
        assertEquals(Preferences(), store.load())
    }

    @Test
    fun `a theme naming nothing yields the default theme`() {
        writeFile("""{"theme":"mauve"}""")
        assertEquals(Theme.SYSTEM, store.load().theme)
    }

    @Test
    fun `a sort order naming nothing yields the default sort order`() {
        writeFile("""{"sortOrder":"byColour"}""")
        assertEquals(SortOrder.MANUAL, store.load().sortOrder)
    }

    @Test
    fun `a negative window width yields the smallest usable width`() {
        writeFile("""{"window":{"width":-4000}}""")
        assertEquals(480, store.load().window.width)
    }

    @Test
    fun `a window width past any display yields the ceiling`() {
        writeFile("""{"window":{"width":2147483647}}""")
        assertEquals(16384, store.load().window.width)
    }

    @Test
    fun `a directory where the file belongs yields the defaults`() {
        Files.createDirectories(paths.preferencesFile)
        assertEquals(Preferences(), store.load())
    }

    @Test
    fun `a file past the size ceiling yields the defaults`() {
        writeFile("""{"theme":"dark","note":"${"a".repeat(70_000)}"}""")
        assertEquals(Theme.SYSTEM, store.load().theme)
    }

    @Test
    fun `a file of the same shape below the ceiling is read`() {
        // The pair holds the document's shape fixed, so the case above turns on its size alone.
        writeFile("""{"theme":"dark","note":"${"a".repeat(1_000)}"}""")
        assertEquals(Theme.DARK, store.load().theme)
    }

    @Test
    fun `saved preferences load back`() {
        val preferences = Preferences(
            theme = Theme.DARK,
            sortOrder = SortOrder.RECENTLY_ADDED,
            startMinimised = true,
            minimiseToTray = false,
            window = WindowGeometry(width = 1024, height = 800, x = 12, y = -34),
        )
        store.save(preferences)
        assertEquals(preferences, store.load())
    }

    @Test
    fun `a save reports success`() {
        assertEquals(Outcome.Success(Unit), store.save(Preferences()))
    }

    @Test
    fun `a save creates the directory it needs`() {
        store.save(Preferences())
        assertTrue(Files.isDirectory(paths.directory))
    }

    @Test
    fun `a save leaves no tail of a longer file behind`() {
        // The padding sits past the end of the document a save writes, so only truncation removes
        // it. A residual tail is what the next read sees as a damaged file.
        writeFile("""{"theme":"dark","note":"${"a".repeat(1_000)}"}""")
        store.save(Preferences())
        assertFalse("aaa" in fileText())
    }

    @Test
    fun `a save over a longer file loads back as it was saved`() {
        writeFile("""{"theme":"dark","note":"${"a".repeat(1_000)}"}""")
        store.save(Preferences(theme = Theme.LIGHT))
        assertEquals(Theme.LIGHT, store.load().theme)
    }

    @Test
    fun `a save onto a directory reports the failure`() {
        Files.createDirectories(paths.preferencesFile)
        assertIs<PreferencesError.Io>(store.save(Preferences()).errorOrNull)
    }

    @Test
    fun `a save to a location that does not resolve reports the failure`() {
        // An empty home leaves every branch relative, which would put the file under whatever
        // directory the application was launched from.
        val unresolved = PreferencesStore(VaultPaths(OperatingSystem.LINUX, { null }, ""))
        assertIs<PreferencesError.Io>(unresolved.save(Preferences()).errorOrNull)
    }

    @Test
    fun `a location that does not resolve yields the defaults`() {
        assertEquals(Preferences(), PreferencesStore(VaultPaths(OperatingSystem.LINUX, { null }, "")).load())
    }
}
