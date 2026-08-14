package com.panda.tauth.vault

import java.nio.file.Path

const val VAULT_FILE_NAME = "vault.tauth"
const val TEMP_FILE_NAME = "vault.tauth.tmp"
const val LOCK_FILE_NAME = "vault.lock"
const val PREFERENCES_FILE_NAME = "preferences.json"
const val INSTANCE_LOCK_FILE_NAME = "instance.lock"
const val INSTANCE_PORT_FILE_NAME = "instance.port"

enum class OperatingSystem {
    LINUX,
    MACOS,
    WINDOWS,
    ;

    companion object {
        fun detect(osName: String = System.getProperty("os.name").orEmpty()): OperatingSystem {
            val name = osName.lowercase()
            return when {
                name.startsWith("mac") || name.startsWith("darwin") -> MACOS
                name.startsWith("windows") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

// The environment is injected so the tests never depend on the real machine's XDG_DATA_HOME, APPDATA
// or home directory, and never touch the real vault.
class VaultPaths(
    private val os: OperatingSystem = OperatingSystem.detect(),
    private val environment: (String) -> String? = System::getenv,
    private val userHome: String = System.getProperty("user.home").orEmpty(),
) {
    val directory: Path
        get() = when (os) {
            OperatingSystem.LINUX -> xdgDataHome().resolve("tauth")
            OperatingSystem.MACOS -> Path.of(userHome, "Library", "Application Support", "TAuth")
            OperatingSystem.WINDOWS -> appData().resolve("TAuth")
        }

    val vaultFile: Path get() = directory.resolve(VAULT_FILE_NAME)

    val tempFile: Path get() = directory.resolve(TEMP_FILE_NAME)

    val lockFile: Path get() = directory.resolve(LOCK_FILE_NAME)

    // Plaintext, and read before the vault is opened.
    val preferencesFile: Path get() = directory.resolve(PREFERENCES_FILE_NAME)

    // Held for the life of a running instance, and distinct from the lock a write takes: a lock
    // released between writes would let a second launch believe no instance is running.
    val instanceLockFile: Path get() = directory.resolve(INSTANCE_LOCK_FILE_NAME)

    // The loopback port the running instance answers on. It names a socket every local process can
    // reach by scanning, so it carries nothing an attacker does not already have.
    val instancePortFile: Path get() = directory.resolve(INSTANCE_PORT_FILE_NAME)

    // An empty user.home leaves every branch above relative, which would put the vault under
    // whatever directory the application was launched from.
    val isResolved: Boolean get() = directory.isAbsolute

    // A relative XDG_DATA_HOME is invalid and must be ignored rather than resolved against the
    // working directory. XDG Base Directory Specification, "Basics".
    private fun xdgDataHome(): Path = absoluteEnvironmentPath("XDG_DATA_HOME")
        ?: Path.of(userHome, ".local", "share")

    private fun appData(): Path = absoluteEnvironmentPath("APPDATA")
        ?: Path.of(userHome, "AppData", "Roaming")

    private fun absoluteEnvironmentPath(name: String): Path? = environment(name)
        ?.takeIf { it.isNotBlank() }
        ?.let(Path::of)
        ?.takeIf { it.isAbsolute }
}
