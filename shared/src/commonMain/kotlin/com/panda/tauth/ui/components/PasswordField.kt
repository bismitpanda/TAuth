package com.panda.tauth.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import com.panda.tauth.ui.theme.LocalSpacing

private const val MASK: Char = '•'

// The field is given the mask, so the semantics tree carries mask characters while the password is
// hidden. Revealing hands the whole password to Compose in a String on every recomposition.
@Composable
fun PasswordField(
    state: PasswordFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    revealLabel: String = "Show",
    maskLabel: String = "Hide",
    focusRequester: FocusRequester = remember { FocusRequester() },
    onSubmit: () -> Unit = {},
) {
    var isRevealed by remember { mutableStateOf(false) }

    // The password does not outlive the field. The holder belongs to the screen, so it takes
    // characters again when the field comes back; ending it is the owner's.
    DisposableEffect(state) {
        onDispose { state.clear() }
    }

    // Keyed on the revision because the characters are not snapshot state and an edit that leaves
    // the length alone changes nothing else this reads.
    val display = remember(state.revision, isRevealed) { state.renderText(isRevealed, MASK) }

    // Runs once the composition applies, which is when this text reaches the field. A base taken from
    // an abandoned composition would put the next edit's characters over the wrong positions.
    SideEffect { state.noteRendered(display) }

    val interactions = remember { MutableInteractionSource() }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // BasicTextField rather than Material's own: this one is backed by a CharArray holder, and
        // TextField is backed by a String. The container comes from Material through the decoration.
        BasicTextField(
            value = TextFieldValue(display, state.selection),
            onValueChange = { state.applyEdit(it) },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight),
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            interactionSource = interactions,
            decorationBox = { field ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = display,
                    innerTextField = field,
                    enabled = enabled,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactions,
                )
            },
        )
        Spacer(Modifier.width(LocalSpacing.current.small))
        TextButton(onClick = { isRevealed = !isRevealed }, enabled = enabled) {
            Text(if (isRevealed) maskLabel else revealLabel)
        }
    }
}
