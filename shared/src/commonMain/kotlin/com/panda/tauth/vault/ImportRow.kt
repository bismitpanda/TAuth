package com.panda.tauth.vault

import com.panda.tauth.Outcome
import com.panda.tauth.totp.MIGRATION_PERIOD_SECONDS
import com.panda.tauth.totp.MigrationAccount
import com.panda.tauth.totp.MigrationBatch
import com.panda.tauth.totp.OtpAuthUri
import com.panda.tauth.totp.OtpType
import com.panda.tauth.totp.isMigrationUri
import com.panda.tauth.totp.readMigration
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

enum class ImportSource {
    URI_LIST,
    DOCUMENT,
    EXPORT_CODE,
}

// The note carries what the source said about itself that no row can.
data class ImportOffer(
    val rows: List<ImportRow>,
    val source: ImportSource = ImportSource.URI_LIST,
    val note: String? = null,
)

// A duplicate is left out unless its position was chosen; a row the reader refused has no account to
// add whatever is chosen.
fun List<ImportRow>.accepted(addAnyway: Set<Int>): List<VaultEntry> = filterIsInstance<ImportRow.Account>()
    .filter { !it.isDuplicate || it.position in addAnyway }
    .map { it.entry }

private const val DOCUMENT_OPENING = '{'

private const val UNREADABLE_ENTRY = "this account is not in a shape TAuth reads"

private const val UNSUPPORTED_DIGEST = "TAuth does not generate codes under this account's digest"

// A document if it opens as one, and a list of URIs otherwise, so a broken document reports itself
// rather than reading as a great many broken URIs.
fun readAccounts(
    text: String,
    existing: List<VaultEntry>,
    now: Instant,
    newId: () -> String,
): Outcome<ImportOffer, VaultError.Corrupt> {
    if (isMigrationUri(text)) return migrationOffer(text, existing, now, newId)
    val isDocument = text.trimStart().startsWith(DOCUMENT_OPENING)
    val rows = if (isDocument) {
        when (val document = documentRows(text, newId)) {
            is Outcome.Failure -> return document
            is Outcome.Success -> document.value
        }
    } else {
        uriRows(text, now, newId)
    }
    val source = if (isDocument) ImportSource.DOCUMENT else ImportSource.URI_LIST
    return Outcome.Success(ImportOffer(marked(rows, existing), source))
}

private fun migrationOffer(
    text: String,
    existing: List<VaultEntry>,
    now: Instant,
    newId: () -> String,
): Outcome<ImportOffer, VaultError.Corrupt> {
    val batch = when (val parsed = readMigration(text)) {
        is Outcome.Failure -> return parsed
        is Outcome.Success -> parsed.value
    }
    val rows = batch.accounts.mapIndexed { index, account -> migrationRow(index + 1, account, now, newId) }
    return Outcome.Success(ImportOffer(marked(rows, existing), ImportSource.EXPORT_CODE, batchNote(batch)))
}

// A user handed one code of several would otherwise take half their accounts for all of them.
private fun batchNote(batch: MigrationBatch): String? = batch.parts
    .takeIf { it > 1 }
    ?.let { "This export is split across $it codes. This is part ${batch.part}: scan the others too." }

private fun migrationRow(position: Int, account: MigrationAccount, now: Instant, newId: () -> String): ImportRow {
    val algorithm = account.algorithm ?: return ImportRow.Refused(position, UNSUPPORTED_DIGEST)
    return try {
        ImportRow.Account(
            position = position,
            entry = VaultEntry(
                id = newId(),
                type = account.type,
                accountName = account.accountName,
                secret = account.secret,
                createdAt = now,
                issuer = account.issuer,
                algorithm = algorithm,
                digits = account.digits,
                period = if (account.type == OtpType.TOTP) MIGRATION_PERIOD_SECONDS else null,
                counter = if (account.type == OtpType.HOTP) account.counter else null,
                orderIndex = position - 1,
            ),
            isDuplicate = false,
        )
    } catch (e: IllegalArgumentException) {
        ImportRow.Refused(position, e.message ?: UNREADABLE_ENTRY)
    }
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
