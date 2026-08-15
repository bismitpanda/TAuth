package com.panda.tauth.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// MaterialTheme carries no spacing scale, so the steps screens lay out with live here and are read
// through LocalSpacing rather than written as raw dp in screen code.
@Immutable
data class Spacing(val extraSmall: Dp, val small: Dp, val medium: Dp, val large: Dp, val extraLarge: Dp)

val TauthSpacing: Spacing = Spacing(
    extraSmall = 4.dp,
    small = 8.dp,
    medium = 16.dp,
    large = 24.dp,
    extraLarge = 32.dp,
)

// No default scale: a composable drawing outside TauthTheme has no theme to take its colours and
// typography from either, and saying so here names the missing wrapper.
val LocalSpacing: ProvidableCompositionLocal<Spacing> =
    staticCompositionLocalOf { error("Spacing is provided by TauthTheme") }
