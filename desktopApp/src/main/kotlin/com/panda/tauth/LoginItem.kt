package com.panda.tauth

import com.panda.tauth.vault.OperatingSystem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val LOGGER = System.getLogger("com.panda.tauth.LoginItem")

private const val REGISTRY_WAIT_SECONDS = 10L

interface LoginItem {
    fun read(): Outcome<String?, AutostartError>

    fun write(command: String): Outcome<Unit, AutostartError>

    fun remove(): Outcome<Unit, AutostartError>
}

fun loginItemFor(
    os: OperatingSystem = OperatingSystem.detect(),
    environment: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home").orEmpty(),
): LoginItem = when (os) {
    OperatingSystem.LINUX -> FileLoginItem(
        path = xdgConfigHome(environment, userHome).resolve("autostart").resolve(AUTOSTART_ENTRY_FILE_NAME),
        render = ::desktopEntry,
        parse = ::commandInDesktopEntry,
    )

    OperatingSystem.MACOS -> FileLoginItem(
        path = Path.of(userHome, "Library", "LaunchAgents", "$AUTOSTART_BUNDLE_ID.plist"),
        render = ::launchAgent,
        parse = ::commandInLaunchAgent,
    )

    OperatingSystem.WINDOWS -> RegistryLoginItem()
}

// XDG Base Directory Specification, "Basics": a relative XDG_CONFIG_HOME is invalid and ignored
// rather than resolved against the working directory.
private fun xdgConfigHome(environment: (String) -> String?, userHome: String): Path {
    val configured = environment("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
    return configured?.takeIf { it.isAbsolute } ?: Path.of(userHome, ".config")
}

internal class FileLoginItem(
    val path: Path,
    private val render: (String) -> String,
    private val parse: (String) -> String?,
) : LoginItem {
    override fun read(): Outcome<String?, AutostartError> = try {
        Outcome.Success(if (Files.exists(path)) parse(Files.readString(path)) else null)
    } catch (e: IOException) {
        Outcome.Failure(AutostartError.Io(e))
    }

    override fun write(command: String): Outcome<Unit, AutostartError> = try {
        Files.createDirectories(path.parent)
        Files.writeString(path, render(command))
        Outcome.Success(Unit)
    } catch (e: IOException) {
        Outcome.Failure(AutostartError.Io(e))
    }

    override fun remove(): Outcome<Unit, AutostartError> = try {
        Files.deleteIfExists(path)
        Outcome.Success(Unit)
    } catch (e: IOException) {
        Outcome.Failure(AutostartError.Io(e))
    }
}

internal class RegistryLoginItem : LoginItem {
    // A value that is not there exits non-zero, which is an absent record rather than a failure.
    override fun read(): Outcome<String?, AutostartError> = run(registryQueryCommand()) { code, output ->
        if (code == 0) commandInRegistryOutput(output) else null
    }

    override fun write(command: String): Outcome<Unit, AutostartError> = run(registryAddCommand(command)) { _, _ -> }

    override fun remove(): Outcome<Unit, AutostartError> = run(registryDeleteCommand()) { _, _ -> }

    private fun <T> run(command: List<String>, read: (Int, String) -> T): Outcome<T, AutostartError> = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(REGISTRY_WAIT_SECONDS, TimeUnit.SECONDS)
        Outcome.Success(read(process.exitValue(), output))
    } catch (e: IOException) {
        Outcome.Failure(AutostartError.Io(e))
    } catch (e: IllegalThreadStateException) {
        Outcome.Failure(AutostartError.Io(e))
    }
}

fun reconcileLoginItem(isEnabled: Boolean, launcher: String?, item: LoginItem): Outcome<Unit, AutostartError> {
    if (!isPackagedLauncher(launcher)) {
        return if (isEnabled) Outcome.Failure(AutostartError.NoLauncher) else item.remove()
    }
    val wanted = checkNotNull(launcher)
    val current = when (val outcome = item.read()) {
        is Outcome.Failure -> return Outcome.Failure(outcome.error)
        is Outcome.Success -> outcome.value
    }
    return when (autostartAction(isEnabled, current, wanted)) {
        AutostartAction.WRITE -> item.write(wanted)
        AutostartAction.REMOVE -> item.remove()
        AutostartAction.LEAVE -> Outcome.Success(Unit)
    }
}

fun currentLauncher(): String? = ProcessHandle.current().info().command().orElse(null)

fun applyLoginItem(isEnabled: Boolean, item: LoginItem, launcher: String? = currentLauncher()) {
    val outcome = reconcileLoginItem(isEnabled, launcher, item)
    if (outcome is Outcome.Failure) {
        LOGGER.log(System.Logger.Level.WARNING, "the login item was not written: ${outcome.error::class.simpleName}")
    }
}
