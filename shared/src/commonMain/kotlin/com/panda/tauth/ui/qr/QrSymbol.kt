package com.panda.tauth.ui.qr

import androidx.compose.runtime.Immutable

// A square grid of modules with its quiet zone already in it, held at module resolution so what
// draws it lays down whole modules rather than resampling a bitmap someone else scaled.
@Immutable
class QrSymbol(val width: Int, modules: BooleanArray) {
    private val modules = modules.copyOf()

    init {
        require(width > 0) { "a symbol carries at least one module" }
        require(this.modules.size == width * width) { "a symbol is square" }
    }

    fun isDark(x: Int, y: Int): Boolean = modules[y * width + x]
}

// A screen reaching for the encoder directly would put a JVM dependency in shared code.
fun interface QrEncoding {
    // Null where the text is longer than the format carries, which has no symbol rather than a
    // partial one.
    fun encode(text: String): QrSymbol?
}
