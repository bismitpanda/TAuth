package com.panda.tauth.vault

import com.panda.tauth.Outcome

interface VaultFile {
    // Absence established by looking. A location that cannot be looked into holds a vault.
    fun exists(): Boolean

    fun read(): Outcome<ByteArray, VaultReadError>

    fun write(bytes: ByteArray): Outcome<Unit, VaultWriteError>
}
