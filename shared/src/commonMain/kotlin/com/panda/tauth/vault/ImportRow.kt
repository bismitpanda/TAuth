package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.totp.OtpAuthUri
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.time.Instant

// What an import offers, one entry per line of a URI list or per element of a document. The position
// is where it sat in the file, counting from one, so a refusal names a place the user can look at.
sealed interface ImportRow {
    val position: Int

    // The vault already holding this account is not a refusal; it is offered anyway.
    data class Account(override val position: Int, val entry: VaultEntry, val isDuplicate: Boolean) : ImportRow

    // The detail states the rule rather than the value, since the value is a credential and this
    // reaches a screen.
    data class Refused(override val position: Int, val detail: String) : ImportRow
}

// A duplicate is left out unless its position was chosen; a row the reader refused has no account to
// add whatever is chosen.
fun List<ImportRow>.accepted(addAnyway: Set<Int>): List<VaultEntry> = filterIsInstance<ImportRow.Account>()
    .filter { !it.isDuplicate || it.position in addAnyway }
    .map { it.entry }

private const val DOCUMENT_OPENING = '{'

private const val UNREADABLE_ENTRY = "this account is not in a shape TAuth reads"

// Two spellings of one key are one account, and nothing here decodes a secret to establish that.
private fun normalisedSecret(secret: String): String = secret.uppercase().filterNot { it == '=' || it.isWhitespace() }

private data class AccountKey(val issuer: String?, val accountName: String, val secret: String) {
    override fun toString(): String = "AccountKey(issuer=$issuer, accountName=$accountName, secret=<redacted>)"
}

private fun VaultEntry.key(): AccountKey = AccountKey(issuer, accountName, normalisedSecret(secret))

// A document if it opens as one, and a list of URIs otherwise, so a broken document reports itself
// rather than reading as a great many broken URIs.
fun readAccounts(
    text: String,
    existing: List<VaultEntry>,
    now: Instant,
    newId: () -> String,
): Outcome<List<ImportRow>, VaultError.Corrupt> {
    val rows = if (text.trimStart().startsWith(DOCUMENT_OPENING)) {
        when (val document = documentRows(text, newId)) {
            is Outcome.Failure -> return document
            is Outcome.Success -> document.value
        }
    } else {
        uriRows(text, now, newId)
    }
    return Outcome.Success(marked(rows, existing))
}

// Against the vault and against the rows already read, so a file carrying one account twice offers
// the second as the duplicate it is.
private fun marked(rows: List<ImportRow>, existing: List<VaultEntry>): List<ImportRow> {
    val seen = existing.map { it.key() }.toMutableSet()
    return rows.map { row ->
        when (row) {
            is ImportRow.Refused -> row
            is ImportRow.Account -> row.copy(isDuplicate = !seen.add(row.entry.key()))
        }
    }
}

private fun documentRows(text: String, newId: () -> String): Outcome<List<ImportRow>, VaultError.Corrupt> {
    val entries = try {
        val document = plaintextExportJson.decodeFromString<JsonObject>(text)
        document[PLAINTEXT_ENTRIES]?.jsonArray
            ?: return Outcome.Failure(VaultError.Corrupt("this file carries no accounts"))
    } catch (_: SerializationException) {
        // The message quotes the document it stopped in, and this one holds every secret.
        return Outcome.Failure(VaultError.Corrupt("this file is not an export TAuth wrote"))
    } catch (_: IllegalArgumentException) {
        return Outcome.Failure(VaultError.Corrupt("this file is not an export TAuth wrote"))
    }
    return Outcome.Success(entries.mapIndexed { index, element -> documentRow(index + 1, element, newId) })
}

private fun documentRow(position: Int, element: JsonElement, newId: () -> String): ImportRow = try {
    // The id is this vault's to give: one carried in from elsewhere may already name an entry
    // here, and an entry arriving is a new one whatever it was called where it came from.
    val entry = plaintextExportJson.decodeFromJsonElement(VaultEntry.serializer(), element).copy(id = newId())
    ImportRow.Account(position, entry, isDuplicate = false)
} catch (e: IllegalArgumentException) {
    // The entry model's own refusal, which states the rule and never the value.
    ImportRow.Refused(position, e.message ?: UNREADABLE_ENTRY)
} catch (_: SerializationException) {
    // Discarded: a parser quotes the document it stopped in, and this one holds every secret.
    ImportRow.Refused(position, UNREADABLE_ENTRY)
}

// Blank lines are skipped rather than refused: a file ends its last line, and an editor may leave
// more than one.
private fun uriRows(text: String, now: Instant, newId: () -> String): List<ImportRow> = text.lines()
    .mapIndexed { index, line -> index + 1 to line.trim() }
    .filter { (_, line) -> line.isNotEmpty() }
    .map { (position, line) -> uriRow(position, line, now, newId) }

private fun uriRow(position: Int, line: String, now: Instant, newId: () -> String): ImportRow =
    when (val parsed = OtpAuthUri.parse(line)) {
        is Outcome.Failure -> ImportRow.Refused(position, detailOf(parsed.error))

        is Outcome.Success -> ImportRow.Account(
            position = position,
            // A URI carries no creation time, so the account is as old as the import that made it.
            entry = parsed.value.toEntry(newId(), now),
            isDuplicate = false,
        )
    }

private fun detailOf(error: UriParseError): String = when (error) {
    is VaultError.MalformedUri -> error.detail
    is VaultError.InvalidSecret -> error.detail
}
