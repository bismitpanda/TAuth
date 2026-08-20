package com.panda.tauth.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFC8FF00)

private val AccentLight = Color(0xFF4A6100)
private val AccentContainerLight = Accent
private val AccentContainerDark = Color(0xFF364700)

private val InkDark = Color(0xFF1B1B1B)
private val InkLight = Color(0xFF1B1B17)
private val PaperLight = Color(0xFFFDFDF5)
private val PaperDark = InkDark

private val NeutralLight = Color(0xFF5C5F52)
private val NeutralContainerLight = Color(0xFFE0E4D2)
private val NeutralDark = Color(0xFFC5C8B7)
private val NeutralContainerDark = Color(0xFF44483C)

private val RedLight = Color(0xFFBA1A1A)
private val RedContainerLight = Color(0xFFFFDAD6)
private val RedDark = Color(0xFFFFB4AB)
private val RedContainerDark = Color(0xFF93000A)

val LightColorScheme: ColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = AccentContainerLight,
    onPrimaryContainer = Color(0xFF141F00),
    secondary = NeutralLight,
    onSecondary = Color.White,
    secondaryContainer = NeutralContainerLight,
    onSecondaryContainer = Color(0xFF191D13),
    tertiary = NeutralLight,
    onTertiary = Color.White,
    tertiaryContainer = NeutralContainerLight,
    onTertiaryContainer = Color(0xFF191D13),
    error = RedLight,
    onError = Color.White,
    errorContainer = RedContainerLight,
    onErrorContainer = Color(0xFF410002),
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = Color(0xFFE3E4D5),
    onSurfaceVariant = Color(0xFF46483D),
    surfaceTint = AccentLight,
    outline = Color(0xFF76786B),
    outlineVariant = Color(0xFFC6C8B9),
    inverseSurface = Color(0xFF303128),
    inverseOnSurface = Color(0xFFF2F1E7),
    inversePrimary = Accent,
)

val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = InkDark,
    primaryContainer = AccentContainerDark,
    onPrimaryContainer = Color(0xFFE3FF8A),
    secondary = NeutralDark,
    onSecondary = Color(0xFF2E3227),
    secondaryContainer = NeutralContainerDark,
    onSecondaryContainer = NeutralContainerLight,
    tertiary = NeutralDark,
    onTertiary = Color(0xFF2E3227),
    tertiaryContainer = NeutralContainerDark,
    onTertiaryContainer = NeutralContainerLight,
    error = RedDark,
    onError = Color(0xFF690005),
    errorContainer = RedContainerDark,
    onErrorContainer = RedContainerLight,
    background = PaperDark,
    onBackground = Color(0xFFE4E3DB),
    surface = PaperDark,
    onSurface = Color(0xFFE4E3DB),
    surfaceVariant = Color(0xFF2A2A26),
    onSurfaceVariant = Color(0xFFC6C8B9),
    surfaceTint = Accent,
    outline = Color(0xFF909285),
    outlineVariant = Color(0xFF46483D),
    inverseSurface = Color(0xFFE4E3DB),
    inverseOnSurface = InkDark,
    inversePrimary = AccentLight,
)
