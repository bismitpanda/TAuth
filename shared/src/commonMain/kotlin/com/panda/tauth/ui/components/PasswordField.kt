package com.panda.tauth.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import com.panda.tauth.ui.theme.TauthIcons

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

    // Material's TextField is backed by a String; this one is backed by a CharArray holder.
    BasicTextField(
        value = TextFieldValue(display, state.selection),
        onValueChange = { state.applyEdit(it) },
        modifier = modifier
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
                trailingIcon = {
                    IconButton(onClick = { isRevealed = !isRevealed }, enabled = enabled) {
                        Icon(
                            if (isRevealed) TauthIcons.hide else TauthIcons.show,
                            contentDescription = if (isRevealed) maskLabel else revealLabel,
                        )
                    }
                },
            )
        },
    )
}
