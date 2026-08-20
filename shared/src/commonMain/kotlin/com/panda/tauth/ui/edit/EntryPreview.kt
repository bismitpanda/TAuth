package com.panda.tauth.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.panda.tauth.Outcome
import com.panda.tauth.session.UnlockedEntry
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.OtpType
import com.panda.tauth.totp.Totp
import com.panda.tauth.totp.TotpCode
import com.panda.tauth.totp.previewCode
import com.panda.tauth.ui.list.AccountFace
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.vault.DraftError
import com.panda.tauth.vault.VaultError
import kotlin.time.Instant

internal const val PREVIEW_TAG = "entry-preview"
internal const val PREVIEW_CODE_TAG = "entry-preview-code"
internal const val PREVIEW_PROBLEM_TAG = "entry-preview-problem"

internal const val PREVIEW_HEADING = "Preview"
internal const val STARTING_COUNTER_PREFIX = "Starting counter "

private const val PREVIEW_ENTRY_ID = "entry-preview"

@Composable
internal fun EntryPreview(
    resolved: Outcome<OtpAuthUri, DraftError>?,
    epochSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    if (resolved == null) return
    Column(
        modifier = modifier.fillMaxWidth().testTag(PREVIEW_TAG),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        Text(PREVIEW_HEADING, style = MaterialTheme.typography.labelLarge)
        when (resolved) {
            is Outcome.Failure -> Text(
                draftProblemFor(resolved.error),
                modifier = Modifier.testTag(PREVIEW_PROBLEM_TAG),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            is Outcome.Success -> Resolved(resolved.value, epochSeconds)
        }
    }
}

@Composable
private fun Resolved(uri: OtpAuthUri, epochSeconds: Long) {
    val entry = remember(uri) { previewEntry(uri) }
    val shown = remember(uri, epochSeconds) { previewCode(uri, epochSeconds) }
    val code = remember(uri, shown, epochSeconds) { previewTotpCode(uri, shown, epochSeconds) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        AccountFace(
            entry = entry,
            code = code,
            shown = shown,
            modifier = Modifier.padding(LocalSpacing.current.medium),
            counterPrefix = STARTING_COUNTER_PREFIX,
            codeTag = PREVIEW_CODE_TAG,
        )
    }
}

// The account has no place in a vault yet, so the fields a vault would give it stand at nothing.
private fun previewEntry(uri: OtpAuthUri): UnlockedEntry = UnlockedEntry(
    id = PREVIEW_ENTRY_ID,
    type = uri.type,
    accountName = uri.accountName,
    createdAt = Instant.DISTANT_PAST,
    issuer = uri.issuer,
    algorithm = uri.algorithm,
    digits = uri.digits,
    period = uri.period,
    counter = uri.counter,
    orderIndex = 0,
)

private fun previewTotpCode(uri: OtpAuthUri, shown: String?, epochSeconds: Long): TotpCode? {
    if (uri.type != OtpType.TOTP || shown == null) return null
    val period = uri.period ?: return null
    return TotpCode(shown, Totp.secondsRemaining(epochSeconds, period), period)
}

internal fun draftProblemFor(error: DraftError): String = when (error) {
    is VaultError.MalformedUri -> "That is not an account this reads: ${error.detail}."
    is VaultError.InvalidSecret -> "The secret is not usable: ${error.detail}."
    is VaultError.InvalidEntry -> "These details do not make an account: ${error.detail}."
}
