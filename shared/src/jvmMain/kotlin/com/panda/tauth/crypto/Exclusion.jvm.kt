package com.panda.tauth.crypto

internal actual fun <T> exclusively(token: Any, block: () -> T): T = synchronized(token, block)
