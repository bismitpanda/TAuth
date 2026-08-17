package com.panda.tauth.ui

import com.panda.tauth.settings.Preferences
import com.panda.tauth.settings.SortOrder
import com.panda.tauth.settings.Theme
import com.panda.tauth.settings.WindowGeometry

// Named rather than defaulted: a control read against a document at its defaults would agree with a
// screen that never read the document at all.
internal fun preferences(
    theme: Theme,
    sortOrder: SortOrder,
    startMinimised: Boolean,
    minimiseToTray: Boolean,
    window: WindowGeometry = WindowGeometry(),
): Preferences = Preferences(
    theme = theme,
    sortOrder = sortOrder,
    startMinimised = startMinimised,
    minimiseToTray = minimiseToTray,
    window = window,
)
