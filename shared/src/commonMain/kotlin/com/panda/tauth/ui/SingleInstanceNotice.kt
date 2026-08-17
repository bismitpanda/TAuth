package com.panda.tauth.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.panda.tauth.ui.theme.LocalSpacing

// The reason the service is missing is a diagnostic and stays in the log; what the person in front of
// the window needs is what it costs them.
private const val NOTICE_TEXT =
    "TAuth cannot tell whether another copy is running. If one is, the last save replaces the " +
        "other's changes."

// A launch with no single-instance service opens its window anyway, and this is how that window says
// the vault behind it can be open in another process at the same time.
@Composable
fun WithSingleInstanceNotice(
    isShown: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    if (!isShown) {
        content(modifier)
        return
    }
    Column(modifier) {
        SingleInstanceNotice(Modifier.fillMaxWidth())
        content(Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun SingleInstanceNotice(modifier: Modifier) {
    val spacing = LocalSpacing.current
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            NOTICE_TEXT,
            modifier = Modifier.padding(spacing.medium),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
