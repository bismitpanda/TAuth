package com.panda.tauth.settings

import com.panda.tauth.Outcome
import com.panda.tauth.vault.VaultPaths
import kotlinx.serialization.SerializationException
import java.io.IOError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

// Reading a hostile file must not exhaust the heap before the window exists: an OutOfMemoryError is
// an Error that no catch here converts and no Outcome carries.
private const val MAX_PREFERENCES_BYTES = 64 * 1024

private val LOGGER = System.getLogger("com.panda.tauth.settings.PreferencesStore")

// The file holds no secrets and is created with the process umask and no mode of its own.
// TRUNCATE_EXISTING is what keeps a shorter document from leaving the tail of a longer one behind,
// which would be read back as a damaged file.
private val WRITE_TEXT = arrayOf<OpenOption>(
    StandardOpenOption.CREATE,
    StandardOpenOption.WRITE,
    StandardOpenOption.TRUNCATE_EXISTING,
)

class PreferencesStore(private val paths: VaultPaths = VaultPaths()) {
    // Absent, empty, malformed, oversized or unknown-keyed input all resolve to defaults: the
    // application opens whatever this file holds, and the file is one an attacker can rewrite.
    fun load(): Preferences {
        val json = readText() ?: return Preferences()
        return try {
            preferencesJson.decodeFromString<Preferences>(json)
        } catch (e: SerializationException) {
            // The message quotes the document it stopped in, which here is a file holding no
            // secrets, so it is logged rather than dropped.
            LOGGER.log(System.Logger.Level.WARNING, "preferences are not readable; the defaults apply", e)
            Preferences()
        }
    }

    fun save(preferences: Preferences): Outcome<Unit, PreferencesError> = try {
        if (paths.isResolved) {
            writeText(preferencesJson.encodeToString(preferences))
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(
                PreferencesError.Io(IOException("the preferences location does not resolve to an absolute path")),
            )
        }
    } catch (e: IOException) {
        Outcome.Failure(PreferencesError.Io(e))
    } catch (e: InvalidPathException) {
        Outcome.Failure(PreferencesError.Io(e))
    } catch (e: IOError) {
        Outcome.Failure(PreferencesError.Io(e))
    }

    // An interrupted write leaves a partial file, which the next read answers with defaults; what is
    // lost is a preference the user can choose again.
    private fun writeText(json: String) {
        // The directory holds no vault yet on a first run. VaultStore restricts it to the owner when
        // it writes, whether it finds this directory or creates its own.
        Files.createDirectories(paths.directory)
        Files.newOutputStream(paths.preferencesFile, *WRITE_TEXT).use { it.write(json.encodeToByteArray()) }
    }

    // Reading creates nothing: a first run reaches its window without a directory on disk.
    private fun readText(): String? = try {
        if (paths.isResolved) readWithinLimit(paths.preferencesFile) else null
    } catch (e: NoSuchFileException) {
        LOGGER.log(System.Logger.Level.DEBUG, "no preferences file at ${paths.preferencesFile}", e)
        null
    } catch (e: IOException) {
        LOGGER.log(System.Logger.Level.WARNING, "preferences cannot be read; the defaults apply", e)
        null
    } catch (e: InvalidPathException) {
        LOGGER.log(System.Logger.Level.WARNING, "the preferences location is not a valid path", e)
        null
    } catch (e: IOError) {
        LOGGER.log(System.Logger.Level.WARNING, "preferences cannot be read; the defaults apply", e)
        null
    }

    private fun readWithinLimit(path: Path): String? {
        // An open on a fifo blocks until a writer appears, and startup must reach the window
        // whatever sits at this path.
        if (!Files.readAttributes(path, BasicFileAttributes::class.java).isRegularFile) {
            LOGGER.log(System.Logger.Level.WARNING, "$path is not a regular file; the defaults apply")
            return null
        }
        // The ceiling bounds the read itself rather than a size measured beforehand, so a file that
        // grows between the two is still bounded.
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_PREFERENCES_BYTES + 1) }
        if (bytes.size > MAX_PREFERENCES_BYTES) {
            LOGGER.log(System.Logger.Level.WARNING, "$path is larger than preferences can be; the defaults apply")
            return null
        }
        return bytes.decodeToString()
    }
}
