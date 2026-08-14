package com.panda.tauth.crypto

import java.util.zip.CRC32

actual fun crc32(bytes: ByteArray): UInt {
    val digest = CRC32()
    digest.update(bytes)
    return digest.value.toUInt()
}
