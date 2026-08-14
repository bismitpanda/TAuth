package com.panda.tauth.vault

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission

// The store's permission and size guards go through these calls.
internal interface FileAccess {
    fun permissionsOf(path: Path, vararg options: LinkOption): Set<PosixFilePermission>

    fun setPermissions(path: Path, permissions: Set<PosixFilePermission>)

    fun openChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): FileChannel
}

internal object SystemFileAccess : FileAccess {
    override fun permissionsOf(path: Path, vararg options: LinkOption): Set<PosixFilePermission> =
        Files.getPosixFilePermissions(path, *options)

    override fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        Files.setPosixFilePermissions(path, permissions)
    }

    override fun openChannel(path: Path, options: Set<OpenOption>, vararg attributes: FileAttribute<*>): FileChannel =
        FileChannel.open(path, options, *attributes)
}
