package com.panda.tauth

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState

@Composable
fun ApplicationScope.TAuthTray(isShown: Boolean, onShow: () -> Unit, onLock: () -> Unit, onQuit: () -> Unit) {
    if (!isShown) return
    Tray(
        icon = TAuthIcon,
        state = rememberTrayState(),
        tooltip = APPLICATION_NAME,
        onAction = onShow,
        menu = {
            Item("Show", onClick = onShow)
            Item("Lock now", onClick = onLock)
            Separator()
            Item("Quit", onClick = onQuit)
        },
    )
}
