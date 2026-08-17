package com.panda.tauth.ui.theme

import com.panda.tauth.settings.Theme

fun Theme.isDark(isSystemDark: Boolean): Boolean = when (this) {
    Theme.SYSTEM -> isSystemDark
    Theme.LIGHT -> false
    Theme.DARK -> true
}
