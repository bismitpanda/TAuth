package com.panda.tauth.ui.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.totp.OtpType
import com.panda.tauth.ui.list.describe
import com.panda.tauth.ui.settings.ExportError
import com.panda.tauth.ui.settings.FileWriteError
import com.panda.tauth.ui.theme.ButtonLabel
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.ui.theme.TauthIcons
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

const val QR_DIALOG_TITLE = "Scan to enrol this account elsewhere"
const val QR_COPY_URI_LABEL = "Copy URI"
const val QR_SAVE_LABEL = "Save as PNG"
const val QR_CLOSE_LABEL = "Close"

const val QR_COUNTER_NOTE =
    "Scanning clones the account at this counter, not at the value the other authenticator will next need."

const val QR_UNAVAILABLE =
    "This account does not fit in a QR code, so its URI has to be copied instead."

// Names the drawing, which is the only thing about it a test or a screen reader can reach.
const val QR_SYMBOL_LABEL = "QR code"

const val QR_SYMBOL_TAG = "qr-symbol"
const val QR_IDENTITY_TAG = "qr-identity"
const val QR_COUNTER_TAG = "qr-counter"
const val QR_SAVE_PROBLEM_TAG = "qr-save-problem"

private fun messageFor(error: FileWriteError): String = when (error) {
    is ExportError.NotRestricted ->
        "That location cannot keep the image to you alone, so nothing was written there."

    is ExportError.Io -> "The image could not be written to that location."
}

// Outside the theme on purpose: inverting the modules for a dark theme breaks a large fraction of
// scanners.
private val QR_LIGHT = Color.White
private val QR_DARK = Color.Black

// Beside the symbol rather than in the theme, because a theme free to shrink it is free to make it
// unscannable.
private val QR_MINIMUM_SIZE = 240.dp

private const val QR_IDLE_SECONDS = 60

// A complete credential in machine-readable form, so it stands only while someone is at the window:
// anything that can see the screen can read it, a camera and a screen share included.
@Composable
fun ShowQrDialog(
    entry: UnlockedEntry,
    symbol: QrSymbol?,
    onCopyUri: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Absent where there is no symbol to write or nowhere to write it, rather than a control that
    // reports a failure the caller already knew about.
    onSaveImage: (() -> Unit)? = null,
    isSaving: Boolean = false,
    saveError: FileWriteError? = null,
) {
    val spacing = LocalSpacing.current
    var interactions by remember { mutableIntStateOf(0) }

    // A save is the user waiting on a file dialog, so the interval does not run underneath one and
    // cancel the write it is waiting for.
    LaunchedEffect(interactions, isSaving) {
        if (isSaving) return@LaunchedEffect
        delay(QR_IDLE_SECONDS.seconds)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Observed on the initial pass so that a press a button goes on to consume is still counted,
        // which is what keeps the dialog up while it is being used.
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    interactions++
                }
            }
        },
        title = { Text(QR_DIALOG_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                if (symbol == null) {
                    Text(QR_UNAVAILABLE, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Symbol(symbol)
                }
                Text(
                    entry.describe(),
                    modifier = Modifier.testTag(QR_IDENTITY_TAG),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (entry.type == OtpType.HOTP) {
                    Text(
                        "${entry.counter} — $QR_COUNTER_NOTE",
                        modifier = Modifier.testTag(QR_COUNTER_TAG),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                saveError?.let { failure ->
                    Text(
                        messageFor(failure),
                        modifier = Modifier.testTag(QR_SAVE_PROBLEM_TAG),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                onSaveImage?.let { save ->
                    TextButton(onClick = save, enabled = !isSaving) { ButtonLabel(TauthIcons.save, QR_SAVE_LABEL) }
                }
                TextButton(onClick = onCopyUri) { ButtonLabel(TauthIcons.copy, QR_COPY_URI_LABEL) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(QR_CLOSE_LABEL) } },
    )
}

@Composable
private fun Symbol(symbol: QrSymbol, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minWidth = QR_MINIMUM_SIZE, minHeight = QR_MINIMUM_SIZE)
            .aspectRatio(1f)
            .testTag(QR_SYMBOL_TAG)
            .semantics { contentDescription = QR_SYMBOL_LABEL },
    ) {
        val layout = qrLayout(size.minDimension, symbol.width)
        // The light surface is the dialog's own and reaches past the encoded quiet zone, so the
        // symbol keeps its margin whatever the theme puts behind the dialog.
        drawRect(color = QR_LIGHT, size = size)
        for (y in 0 until symbol.width) {
            for (x in 0 until symbol.width) {
                if (symbol.isDark(x, y)) {
                    drawRect(
                        color = QR_DARK,
                        topLeft = Offset(
                            layout.originPx + x * layout.modulePx,
                            layout.originPx + y * layout.modulePx,
                        ),
                        size = Size(layout.modulePx, layout.modulePx),
                    )
                }
            }
        }
    }
}
