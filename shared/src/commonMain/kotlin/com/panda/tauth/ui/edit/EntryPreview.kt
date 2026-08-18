package com.panda.tauth.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.panda.tauth.Outcome
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.OtpType
import com.panda.tauth.totp.groupedCode
import com.panda.tauth.totp.previewCode
import com.panda.tauth.ui.theme.LocalSpacing
import com.panda.tauth.vault.DraftError
import com.panda.tauth.vault.VaultError

internal const val PREVIEW_TAG = "entry-preview"
internal const val PREVIEW_CODE_TAG = "entry-preview-code"
internal const val PREVIEW_PROBLEM_TAG = "entry-preview-problem"

internal const val PREVIEW_HEADING = "Preview"
internal const val COUNTER_PREFIX = "Starting counter "
internal const val PERIOD_SUFFIX = "-second period"

// A pasted URI and a typed form arrive as the same resolved account. The sample code is worked out
// without writing anything, so confirming an hotp account spends no counter value.
@Composable
internal fun EntryPreview(
    resolved: Outcome<OtpAuthUri, DraftError>?,
    epochSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier.fillMaxWidth().testTag(PREVIEW_TAG),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
        ) {
            Text(PREVIEW_HEADING, style = MaterialTheme.typography.titleMedium)
            when (resolved) {
                null -> Unit

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
}

// Emits siblings into the caller's Column rather than a node of its own, so it takes no modifier.
@Composable
private fun Resolved(uri: OtpAuthUri, epochSeconds: Long) {
    Text(uri.accountName, style = MaterialTheme.typography.bodyMedium)
    uri.issuer?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Text(uri.type.uriAuthority.uppercase(), style = MaterialTheme.typography.labelMedium)
    Text("${uri.algorithm.name}, ${uri.digits} digits", style = MaterialTheme.typography.labelMedium)
    when (uri.type) {
        OtpType.TOTP -> uri.period?.let { Text("$it$PERIOD_SUFFIX", style = MaterialTheme.typography.labelMedium) }
        OtpType.HOTP -> uri.counter?.let { Text("$COUNTER_PREFIX$it", style = MaterialTheme.typography.labelMedium) }
    }
    previewCode(uri, epochSeconds)?.let {
        Text(
            groupedCode(it),
            modifier = Modifier.testTag(PREVIEW_CODE_TAG),
            style = MaterialTheme.typography.displaySmall,
        )
    }
}

// No else branch, over the cases resolving a draft reports: a case joining that view has to be given a
// sentence here before this compiles again.
internal fun draftProblemFor(error: DraftError): String = when (error) {
    is VaultError.MalformedUri -> "That is not an account this reads: ${error.detail}."
    is VaultError.InvalidSecret -> "The secret is not usable: ${error.detail}."
    is VaultError.InvalidEntry -> "These details do not make an account: ${error.detail}."
}
