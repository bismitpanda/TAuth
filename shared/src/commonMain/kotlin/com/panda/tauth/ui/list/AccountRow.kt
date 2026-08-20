package com.panda.tauth.ui.list

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.totp.OtpType
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.totp.groupedCode
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.ui.theme.LocalTauthColors
import com.panda.tauth.ui.theme.TauthIcons

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

private const val SWEEP_MILLIS = 1_000

private val ICON_SIZE = 18.dp

private const val MARK_SATURATION = 0.45f
private const val MARK_LIGHTNESS = 0.28f
private const val MARK_INK_LIGHTNESS = 0.88f

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
    isSelected: Boolean = false,
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
        modifier = modifier
            .fillMaxWidth()
            .testTag(accountRowTag(entry.id))
            .semantics { selected = isSelected },
        color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(spacing.medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                Icon(
                    TauthIcons.reorder,
                    contentDescription = REORDER_LABEL,
                    modifier = dragModifier.testTag(dragHandleTag(entry.id)),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AccountFace(
                    entry = entry,
                    code = code,
                    shown = shown,
                    modifier = Modifier.weight(1f),
                    onCopyCode = onCopyCode,
                )
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
internal fun AccountFace(
    entry: UnlockedEntry,
    code: TotpCode?,
    shown: String?,
    modifier: Modifier = Modifier,
    // The list shows where a counter stands; an account being added shows where it will start.
    counterPrefix: String = COUNTER_PREFIX,
    codeTag: String? = null,
    onCopyCode: () -> Unit = {},
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.medium),
    ) {
        AccountMark(entry)
        Identity(entry, counterPrefix = counterPrefix, modifier = Modifier.weight(1f))
        Readout(entry = entry, code = code, shown = shown, codeTag = codeTag, onCopyCode = onCopyCode)
    }
}

@Composable
private fun AccountMark(entry: UnlockedEntry, modifier: Modifier = Modifier) {
    val hue = markHue(entry) * FULL_TURN_DEGREES
    Box(
        modifier = modifier
            .size(LocalSpacing.current.extraLarge)
            .clip(CircleShape)
            .background(Color.hsl(hue, MARK_SATURATION, MARK_LIGHTNESS)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            markInitial(entry),
            style = MaterialTheme.typography.titleMedium,
            color = Color.hsl(hue, MARK_SATURATION, MARK_INK_LIGHTNESS),
        )
    }
}

@Composable
private fun Identity(entry: UnlockedEntry, counterPrefix: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        entry.issuer?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(entry.accountName, style = MaterialTheme.typography.bodyMedium)
        if (entry.type == OtpType.HOTP) {
            entry.counter?.let {
                Text(counterPrefix + it, style = MaterialTheme.typography.labelMedium)
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
    codeTag: String? = null,
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
                        .clickable(onClick = onCopyCode)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .then(codeTag?.let { Modifier.testTag(it) } ?: Modifier),
                    style = MaterialTheme.typography.displaySmall,
                )
            }
        }
        if (entry.type == OtpType.TOTP && code != null) {
            Countdown(entryId = entry.id, code = code)
        }
    }
}

@Composable
private fun Countdown(entryId: String, code: TotpCode, modifier: Modifier = Modifier) {
    val colors = LocalTauthColors.current
    val spacing = LocalSpacing.current
    val seconds = code.secondsRemaining
    val ringColor = countdownColor(seconds, colors)
    val label = if (isExpiring(seconds)) RING_EXPIRING_LABEL else RING_RUNNING_LABEL
    val target = countdownFraction(seconds, code.period)
    val sweep = remember { Animatable(target) }
    LaunchedEffect(target) {
        if (target > sweep.value) {
            sweep.snapTo(target)
        } else {
            sweep.animateTo(target, tween(durationMillis = SWEEP_MILLIS, easing = LinearEasing))
        }
    }
    val fraction = sweep.value

    Canvas(
        modifier = modifier
            .size(spacing.extraLarge)
            .testTag(countdownTag(entryId))
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, EMPTY_TO_FULL)
            },
    ) {
        drawArc(
            color = ringColor,
            startAngle = ARC_START_DEGREES,
            sweepAngle = FULL_TURN_DEGREES * fraction,
            useCenter = true,
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
            IconButton(onClick = onGenerate, enabled = isGenerateEnabled) {
                Icon(TauthIcons.generate, contentDescription = GENERATE_LABEL)
            }
            if (hasCode) {
                IconButton(onClick = onHideCode) {
                    Icon(TauthIcons.hide, contentDescription = HIDE_CODE_LABEL)
                }
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
        IconButton(onClick = { isOpen = true }) {
            Icon(TauthIcons.more, contentDescription = MENU_LABEL)
        }
        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            DropdownMenuItem(text = { Text(EDIT_LABEL) }, leadingIcon = { Icon(TauthIcons.edit, null) }, onClick = {
                isOpen = false
                onEdit()
            })
            DropdownMenuItem(
                text = { Text(COPY_CODE_LABEL) },
                leadingIcon = { Icon(TauthIcons.copy, null) },
                enabled = hasCode,
                onClick = {
                    isOpen = false
                    onCopyCode()
                },
            )
            // A complete credential, unlike the code above it: the caller puts the password gate in
            // front of this one.
            DropdownMenuItem(text = { Text(COPY_URI_LABEL) }, leadingIcon = { Icon(TauthIcons.copy, null) }, onClick = {
                isOpen = false
                onCopyUri()
            })
            // The same credential as the item above, put on the screen rather than the clipboard,
            // and behind the same gate.
            DropdownMenuItem(text = { Text(SHOW_QR_LABEL) }, leadingIcon = { Icon(TauthIcons.qr, null) }, onClick = {
                isOpen = false
                onShowQr()
            })
            DropdownMenuItem(text = { Text(DELETE_LABEL) }, leadingIcon = { Icon(TauthIcons.delete, null) }, onClick = {
                isOpen = false
                onDelete()
            })
        }
    }
}
