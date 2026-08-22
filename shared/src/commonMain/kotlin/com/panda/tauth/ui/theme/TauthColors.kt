package com.panda.tauth.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Colors the Material scheme has no role for. A screen reads them through LocalTauthColors so a
// light and a dark value exist for each, as they do for every Material role.
@Immutable
data class TauthColors(
    val countdown: Color,
    // The countdown ring turns this in the final five seconds of a period.
    val countdownExpiring: Color,
)

val LightTauthColors: TauthColors = TauthColors(
    countdown = Color(0xFF4A6100),
    countdownExpiring = Color(0xFFB26A00),
)

val DarkTauthColors: TauthColors = TauthColors(
    countdown = Accent,
    countdownExpiring = Color(0xFFFFA726),
)

// No default set: a composable drawing outside TauthTheme has no Material scheme to sit these
// colors beside, and a light default would render dark-on-dark in the dark theme without a word.
val LocalTauthColors: ProvidableCompositionLocal<TauthColors> =
    staticCompositionLocalOf { error("TauthColors are provided by TauthTheme") }
