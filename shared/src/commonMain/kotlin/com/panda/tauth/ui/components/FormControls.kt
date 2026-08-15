package com.panda.tauth.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.panda.tauth.ui.theme.LocalSpacing

// A labelled line of text. Nothing here masks what it holds: the one field that must is the master
// password, which has a holder of its own that keeps its characters out of a String.
@Composable
fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
        // The label stays above the field rather than inside it, so the tagged node carries the value
        // alone and a reading of it is a reading of what was typed.
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().testTag(tag),
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
        )
    }
}

// One button per member, the chosen one filled: a disabled control reads as the one option that
// cannot be had.
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            options.forEach { option ->
                Choice(
                    label = optionLabel(option),
                    isSelected = option == selected,
                    enabled = enabled,
                    onSelect = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun Choice(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val marked = modifier.semantics { selected = isSelected }
    if (isSelected) {
        Button(onClick = onSelect, modifier = marked, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onSelect, modifier = marked, enabled = enabled) { Text(label) }
    }
}
