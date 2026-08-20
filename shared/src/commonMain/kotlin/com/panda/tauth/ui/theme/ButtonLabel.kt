package com.panda.tauth.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter

@Composable
fun ButtonLabel(icon: Painter, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ControlIcon))
    Spacer(Modifier.width(LocalSpacing.current.small))
    Text(label)
}
