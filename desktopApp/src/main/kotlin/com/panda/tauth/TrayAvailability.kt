package com.panda.tauth

import java.awt.SystemTray

// A proxy rather than the same question, which §10.2 states: the tray speaks StatusNotifierItem and
// this asks AWT about an XEmbed notification area. False is an ordinary answer, not a failure.
fun isSystemTraySupported(): Boolean = SystemTray.isSupported()
