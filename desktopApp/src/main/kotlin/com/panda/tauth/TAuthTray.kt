package com.panda.tauth

import androidx.compose.runtime.Composable
import com.panda.tauth.resources.Res
import com.panda.tauth.resources.tauth
import dev.nucleusframework.composenativetray.tray.api.Tray

// The desktop draws this menu, not Compose, so what it offers is the item, the divider and the
// label: §10.1's three actions and nothing a theme would have to reach.
@Composable
fun TAuthTray(isShown: Boolean, onShow: () -> Unit, onLock: () -> Unit, onQuit: () -> Unit) {
    if (!isShown) return
    Tray(
        // The drawable rather than a painter, so the tray renders the mark at the size its own
        // desktop asks for instead of at the size a painter claims.
        icon = Res.drawable.tauth,
        tooltip = APPLICATION_NAME,
        primaryAction = onShow,
    ) {
        Item(label = "Show", onClick = onShow)
        Item(label = "Lock now", onClick = onLock)
        Divider()
        Item(label = "Quit", onClick = onQuit)
    }
}
