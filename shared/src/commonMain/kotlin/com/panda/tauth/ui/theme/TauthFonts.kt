package com.panda.tauth.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import tauth.shared.generated.resources.Res
import tauth.shared.generated.resources.noto_sans
import tauth.shared.generated.resources.noto_sans_mono

// A weight not named on the axis draws at the variable font's default instance.
@Composable
fun notoSans(): FontFamily = FontFamily(
    Font(Res.font.noto_sans, FontWeight.Normal, variationSettings = weightAxis(NORMAL)),
    Font(Res.font.noto_sans, FontWeight.Medium, variationSettings = weightAxis(MEDIUM)),
    Font(Res.font.noto_sans, FontWeight.SemiBold, variationSettings = weightAxis(SEMI_BOLD)),
    Font(Res.font.noto_sans, FontWeight.Bold, variationSettings = weightAxis(BOLD)),
)

@Composable
fun notoSansMono(): FontFamily = FontFamily(
    Font(Res.font.noto_sans_mono, FontWeight.Normal, variationSettings = weightAxis(NORMAL)),
    Font(Res.font.noto_sans_mono, FontWeight.Medium, variationSettings = weightAxis(MEDIUM)),
)

private fun weightAxis(weight: Int) = FontVariation.Settings(FontVariation.weight(weight))

private const val NORMAL = 400
private const val MEDIUM = 500
private const val SEMI_BOLD = 600
private const val BOLD = 700
