package com.panda.tauth

import java.awt.Desktop
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val LOGGER = System.getLogger("com.panda.tauth.FileManagerReveal")

// What a desktop can be asked to do with a path. Several Linux desktops answer neither call, and the
// settings screen states the location in text beside the control for exactly that case.
enum class RevealAction {
    SELECT_FILE,
    OPEN_DIRECTORY,
    NONE,
}

fun revealActionFor(isSupported: (Desktop.Action) -> Boolean): RevealAction = when {
    isSupported(Desktop.Action.BROWSE_FILE_DIR) -> RevealAction.SELECT_FILE
    isSupported(Desktop.Action.OPEN) -> RevealAction.OPEN_DIRECTORY
    else -> RevealAction.NONE
}

fun revealInFileManager(vaultFile: Path) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    if (desktop == null) {
        LOGGER.log(System.Logger.Level.INFO, "this desktop has no file manager integration")
        return
    }
    // A vault that has never been written cannot be selected, and the directory it will be written
    // into is what stands in for it.
    val target = if (Files.exists(vaultFile)) vaultFile else vaultFile.parent
    if (target == null) return
    try {
        when (revealActionFor(desktop::isSupported)) {
            RevealAction.SELECT_FILE -> desktop.browseFileDirectory(target.toFile())
            RevealAction.OPEN_DIRECTORY -> desktop.open(directoryOf(target).toFile())
            RevealAction.NONE -> LOGGER.log(System.Logger.Level.INFO, "this desktop opens no file manager")
        }
    } catch (e: IOException) {
        LOGGER.log(System.Logger.Level.WARNING, "the file manager did not open", e)
    } catch (e: IllegalArgumentException) {
        LOGGER.log(System.Logger.Level.WARNING, "the file manager did not open", e)
    } catch (e: UnsupportedOperationException) {
        LOGGER.log(System.Logger.Level.WARNING, "the file manager did not open", e)
    } catch (e: SecurityException) {
        LOGGER.log(System.Logger.Level.WARNING, "the file manager did not open", e)
    }
}

private fun directoryOf(path: Path): Path = if (Files.isDirectory(path)) path else path.parent ?: path
