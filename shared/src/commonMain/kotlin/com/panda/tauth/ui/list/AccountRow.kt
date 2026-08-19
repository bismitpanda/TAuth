package com.panda.tauth.ui.list

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.totp.OtpType
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.totp.groupedCode
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.ui.theme.LocalTauthColors

const val GENERATE_LABEL = "Generate code"
const val HIDE_CODE_LABEL = "Hide code"
const val MENU_LABEL = "More"
const val EDIT_LABEL = "Edit"
const val COPY_CODE_LABEL = "Copy code"
const val COPY_URI_LABEL = "Copy otpauth:// URI"
const val SHOW_QR_LABEL = "Show QR code"
const val DELETE_LABEL = "Delete"
const val REORDER_LABEL = "Reorder"

const val COUNTER_PREFIX = "Counter "

// The ring says which of the two states it is in through the same choice that colours it, so a
// reading of one is a reading of the other.
const val RING_RUNNING_LABEL = "Countdown"
const val RING_EXPIRING_LABEL = "Countdown, expiring"

private const val ARC_START_DEGREES = -90f
private const val FULL_TURN_DEGREES = 360f

private val EMPTY_TO_FULL = 0f..1f

fun accountRowTag(id: String): String = "account-row-$id"

fun countdownTag(id: String): String = "countdown-$id"

fun dragHandleTag(id: String): String = "drag-handle-$id"

// A row draws what it is given and holds no code of its own, which is what keeps a redrawn row from
// spending a counter value.
@Composable
fun AccountRow(
    entry: UnlockedEntry,
    modifier: Modifier = Modifier,
    code: TotpCode? = null,
    generatedCode: String? = null,
    isGenerateEnabled: Boolean = true,
    notice: String? = null,
    dragModifier: Modifier = Modifier,
    onCopyCode: () -> Unit = {},
    onGenerate: () -> Unit = {},
    onHideCode: () -> Unit = {},
    onEdit: () -> Unit = {},
    onCopyUri: () -> Unit = {},
    onShowQr: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val shown = if (entry.type == OtpType.TOTP) code?.code else generatedCode

    Surface(
        modifier = modifier.fillMaxWidth().testTag(accountRowTag(entry.id)),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(spacing.medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                Text(
                    REORDER_LABEL,
                    modifier = dragModifier.testTag(dragHandleTag(entry.id)),
                    style = MaterialTheme.typography.labelSmall,
                )
                Identity(entry, modifier = Modifier.weight(1f))
                Readout(entry = entry, code = code, shown = shown, onCopyCode = onCopyCode)
                Trailing(
                    entry = entry,
                    hasCode = shown != null,
                    isGenerateEnabled = isGenerateEnabled,
                    onGenerate = onGenerate,
                    onHideCode = onHideCode,
                    onCopyCode = onCopyCode,
                    onEdit = onEdit,
                    onCopyUri = onCopyUri,
                    onShowQr = onShowQr,
                    onDelete = onDelete,
                )
            }
            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Identity(entry: UnlockedEntry, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        entry.issuer?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(entry.accountName, style = MaterialTheme.typography.bodyMedium)
        if (entry.type == OtpType.HOTP) {
            entry.counter?.let {
                Text(COUNTER_PREFIX + it, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// Tapping the code copies it. An hotp row with nothing generated has no code to tap, which is what
// keeps a stray press from spending a counter value.
@Composable
private fun Readout(
    entry: UnlockedEntry,
    code: TotpCode?,
    shown: String?,
    onCopyCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.small),
    ) {
        shown?.let { current ->
            // A period boundary replaces every digit at once, and a hard cut reads as a glitch
            // rather than as a new code.
            Crossfade(targetState = current) { value ->
                Text(
                    groupedCode(value),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onCopyCode),
                    style = MaterialTheme.typography.displaySmall,
                )
            }
        }
        if (entry.type == OtpType.TOTP && code != null) {
            CountdownRing(entryId = entry.id, secondsRemaining = code.secondsRemaining, period = code.period)
        }
    }
}

@Composable
private fun CountdownRing(entryId: String, secondsRemaining: Int, period: Int, modifier: Modifier = Modifier) {
    val colors = LocalTauthColors.current
    val ringColor = countdownColor(secondsRemaining, colors)
    val label = if (ringColor == colors.countdownExpiring) RING_EXPIRING_LABEL else RING_RUNNING_LABEL
    val spacing = LocalSpacing.current
    val fraction = countdownFraction(secondsRemaining, period)

    Canvas(
        modifier = modifier
            .size(spacing.extraLarge)
            .testTag(countdownTag(entryId))
            // The arc is drawn and unreadable; the same fraction reported here is what a screen
            // reader announces and what a test can hold the period to.
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, EMPTY_TO_FULL)
            },
    ) {
        val width = spacing.extraSmall.toPx()
        val inset = width / 2
        drawArc(
            color = ringColor,
            startAngle = ARC_START_DEGREES,
            sweepAngle = FULL_TURN_DEGREES * fraction,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - width, size.height - width),
            style = Stroke(width = width),
        )
    }
}

@Composable
private fun Trailing(
    entry: UnlockedEntry,
    hasCode: Boolean,
    isGenerateEnabled: Boolean,
    onGenerate: () -> Unit,
    onHideCode: () -> Unit,
    onCopyCode: () -> Unit,
    onEdit: () -> Unit,
    onCopyUri: () -> Unit,
    onShowQr: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (entry.type == OtpType.HOTP) {
            // Disabled for a moment after a generation, so a second press cannot spend a counter
            // value the user never meant to ask for.
            TextButton(onClick = onGenerate, enabled = isGenerateEnabled) { Text(GENERATE_LABEL) }
            if (hasCode) {
                TextButton(onClick = onHideCode) { Text(HIDE_CODE_LABEL) }
            }
        }
        OverflowMenu(
            hasCode = hasCode,
            onCopyCode = onCopyCode,
            onEdit = onEdit,
            onCopyUri = onCopyUri,
            onShowQr = onShowQr,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun OverflowMenu(
    hasCode: Boolean,
    onCopyCode: () -> Unit,
    onEdit: () -> Unit,
    onCopyUri: () -> Unit,
    onShowQr: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        TextButton(onClick = { isOpen = true }) { Text(MENU_LABEL) }
        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            DropdownMenuItem(text = { Text(EDIT_LABEL) }, onClick = {
                isOpen = false
                onEdit()
            })
            DropdownMenuItem(text = { Text(COPY_CODE_LABEL) }, enabled = hasCode, onClick = {
                isOpen = false
                onCopyCode()
            })
            // A complete credential, unlike the code above it: the caller puts the password gate in
            // front of this one.
            DropdownMenuItem(text = { Text(COPY_URI_LABEL) }, onClick = {
                isOpen = false
                onCopyUri()
            })
            // The same credential as the item above, put on the screen rather than the clipboard,
            // and behind the same gate.
            DropdownMenuItem(text = { Text(SHOW_QR_LABEL) }, onClick = {
                isOpen = false
                onShowQr()
            })
            DropdownMenuItem(text = { Text(DELETE_LABEL) }, onClick = {
                isOpen = false
                onDelete()
            })
        }
    }
}
