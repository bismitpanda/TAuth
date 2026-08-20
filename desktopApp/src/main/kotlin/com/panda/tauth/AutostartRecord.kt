package com.panda.tauth

const val AUTOSTART_ENTRY_FILE_NAME = "tauth.desktop"
const val AUTOSTART_BUNDLE_ID = "com.panda.tauth"
const val AUTOSTART_REGISTRY_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
const val AUTOSTART_REGISTRY_VALUE = "TAuth"

private const val EXEC_FIELD = "Exec="
private const val PROGRAM_ARGUMENTS = "ProgramArguments"

private val JVM_LAUNCHERS = setOf("java", "javaw", "java.exe", "javaw.exe")

enum class AutostartAction {
    WRITE,
    REMOVE,
    LEAVE,
}

sealed interface AutostartError {
    data class Io(val cause: Throwable) : AutostartError

    data object NoLauncher : AutostartError
}

fun isPackagedLauncher(command: String?): Boolean {
    val trimmed = command?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    val name = trimmed.substringAfterLast('/').substringAfterLast('\\')
    return name.lowercase() !in JVM_LAUNCHERS
}

fun autostartAction(isEnabled: Boolean, current: String?, wanted: String): AutostartAction = when {
    !isEnabled -> if (current == null) AutostartAction.LEAVE else AutostartAction.REMOVE
    current == wanted -> AutostartAction.LEAVE
    else -> AutostartAction.WRITE
}

fun desktopEntry(command: String): String = """
    [Desktop Entry]
    Type=Application
    Name=$APPLICATION_NAME
    $EXEC_FIELD${quotedExec(command)}
    Terminal=false
    X-GNOME-Autostart-enabled=true

""".trimIndent()

fun commandInDesktopEntry(text: String): String? = text.lineSequence()
    .firstOrNull { it.startsWith(EXEC_FIELD) }
    ?.removePrefix(EXEC_FIELD)
    ?.let(::unquotedExec)

fun launchAgent(command: String): String = """
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
    <dict>
        <key>Label</key>
        <string>$AUTOSTART_BUNDLE_ID</string>
        <key>$PROGRAM_ARGUMENTS</key>
        <array>
            <string>${xmlEscaped(command)}</string>
        </array>
        <key>RunAtLoad</key>
        <true/>
    </dict>
    </plist>

""".trimIndent()

// The label above is a string too, so the read starts past the key naming what is launched.
fun commandInLaunchAgent(text: String): String? = text.substringAfter(PROGRAM_ARGUMENTS, "")
    .substringAfter("<string>", "")
    .substringBefore("</string>", "")
    .takeIf { it.isNotEmpty() }
    ?.let(::xmlUnescaped)

fun registryAddCommand(command: String): List<String> = listOf(
    "reg",
    "add",
    AUTOSTART_REGISTRY_KEY,
    "/v",
    AUTOSTART_REGISTRY_VALUE,
    "/t",
    "REG_SZ",
    "/d",
    command,
    "/f",
)

fun registryDeleteCommand(): List<String> = listOf(
    "reg",
    "delete",
    AUTOSTART_REGISTRY_KEY,
    "/v",
    AUTOSTART_REGISTRY_VALUE,
    "/f",
)

fun registryQueryCommand(): List<String> = listOf(
    "reg",
    "query",
    AUTOSTART_REGISTRY_KEY,
    "/v",
    AUTOSTART_REGISTRY_VALUE,
)

// reg separates name, type and data by runs of spaces, and a path may hold spaces of its own.
fun commandInRegistryOutput(output: String): String? = output.lineSequence()
    .firstOrNull { it.contains(AUTOSTART_REGISTRY_VALUE) && it.contains("REG_SZ") }
    ?.substringAfter("REG_SZ")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

// Desktop Entry Specification, "Exec": these are reserved and escaped inside the quoted argument.
private fun quotedExec(command: String): String {
    val escaped = command.map { character ->
        if (character in "\"\\$`") "\\$character" else "$character"
    }
    return "\"${escaped.joinToString("")}\""
}

private fun unquotedExec(field: String): String? {
    val trimmed = field.trim()
    if (!trimmed.startsWith('"') || !trimmed.endsWith('"') || trimmed.length < 2) {
        return trimmed.takeIf { it.isNotEmpty() }
    }
    val inner = trimmed.substring(1, trimmed.length - 1)
    val unescaped = StringBuilder()
    var isEscaped = false
    inner.forEach { character ->
        when {
            isEscaped -> {
                unescaped.append(character)
                isEscaped = false
            }

            character == '\\' -> isEscaped = true

            else -> unescaped.append(character)
        }
    }
    return unescaped.toString().takeIf { it.isNotEmpty() }
}

private fun xmlEscaped(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun xmlUnescaped(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
