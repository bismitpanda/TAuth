package com.panda.tauth.vault

import com.panda.tauth.Outcome

// The bytes the vault lives in. Reading and writing them is a platform primitive, so the session
// reaches them through this and stays free of the filesystem.
interface VaultFile {
    // Absence established by looking. A location that cannot be looked into holds a vault.
    fun exists(): Boolean

    fun read(): Outcome<ByteArray, VaultError>

    fun write(bytes: ByteArray): Outcome<Unit, VaultError>
}
