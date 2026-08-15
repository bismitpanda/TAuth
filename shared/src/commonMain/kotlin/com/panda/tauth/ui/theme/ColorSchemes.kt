package com.panda.tauth.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val BluePrimaryLight = Color(0xFF2F5DA8)
private val BluePrimaryContainerLight = Color(0xFFD8E2FF)
private val BluePrimaryDark = Color(0xFFADC6FF)
private val BluePrimaryContainerDark = Color(0xFF004494)

private val SlateLight = Color(0xFF565E71)
private val SlateContainerLight = Color(0xFFDAE2F9)
private val SlateDark = Color(0xFFBEC6DC)
private val SlateContainerDark = Color(0xFF3E4759)

private val PlumLight = Color(0xFF715573)
private val PlumContainerLight = Color(0xFFFBD7FC)
private val PlumDark = Color(0xFFDEBCDF)
private val PlumContainerDark = Color(0xFF573E5A)

private val RedLight = Color(0xFFBA1A1A)
private val RedContainerLight = Color(0xFFFFDAD6)
private val RedDark = Color(0xFFFFB4AB)
private val RedContainerDark = Color(0xFF93000A)

private val InkLight = Color(0xFF1A1B1F)
private val PaperLight = Color(0xFFFDFBFF)
private val InkDark = Color(0xFFE3E2E6)
private val PaperDark = Color(0xFF1A1B1F)

val LightColorScheme: ColorScheme = lightColorScheme(
    primary = BluePrimaryLight,
    onPrimary = Color.White,
    primaryContainer = BluePrimaryContainerLight,
    onPrimaryContainer = Color(0xFF001A41),
    secondary = SlateLight,
    onSecondary = Color.White,
    secondaryContainer = SlateContainerLight,
    onSecondaryContainer = Color(0xFF131C2C),
    tertiary = PlumLight,
    onTertiary = Color.White,
    tertiaryContainer = PlumContainerLight,
    onTertiaryContainer = Color(0xFF2A132E),
    error = RedLight,
    onError = Color.White,
    errorContainer = RedContainerLight,
    onErrorContainer = Color(0xFF410002),
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceTint = BluePrimaryLight,
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = BluePrimaryDark,
)

val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = BluePrimaryDark,
    onPrimary = Color(0xFF002E69),
    primaryContainer = BluePrimaryContainerDark,
    onPrimaryContainer = BluePrimaryContainerLight,
    secondary = SlateDark,
    onSecondary = Color(0xFF283141),
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = SlateContainerLight,
    tertiary = PlumDark,
    onTertiary = Color(0xFF402843),
    tertiaryContainer = PlumContainerDark,
    onTertiaryContainer = PlumContainerLight,
    error = RedDark,
    onError = Color(0xFF690005),
    errorContainer = RedContainerDark,
    onErrorContainer = RedContainerLight,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = BluePrimaryDark,
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    inverseSurface = InkDark,
    inverseOnSurface = InkLight,
    inversePrimary = BluePrimaryLight,
)
