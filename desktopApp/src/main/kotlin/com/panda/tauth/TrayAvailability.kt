package com.panda.tauth

import java.awt.SystemTray

// False is an ordinary answer, not a failure: a Linux session with no StatusNotifierItem or
// notification-area host has no tray. What the application does with it is WindowLifecycle's.
//
// A proxy rather than the same question, which §10.2 states: the tray itself speaks
// StatusNotifierItem and this asks AWT about an XEmbed notification area.
fun isSystemTraySupported(): Boolean = SystemTray.isSupported()
