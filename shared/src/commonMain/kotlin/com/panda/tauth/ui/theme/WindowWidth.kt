package com.panda.tauth.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val CompactWidth: Dp = 600.dp

fun isCompact(width: Dp): Boolean = width < CompactWidth
