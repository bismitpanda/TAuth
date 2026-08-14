package com.panda.tauth

import java.awt.SystemTray

// False is an ordinary answer, not a failure: a Linux session with no StatusNotifierItem or
// notification-area host has no tray. What the application does with it is WindowLifecycle's.
fun isSystemTraySupported(): Boolean = SystemTray.isSupported()
