package com.panda.tauth.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val PLAINTEXT_EXPORT_VERSION = 1

// The one key a reader looks for. The writer takes its name from here too, so renaming the property
// below cannot leave the two disagreeing.
internal const val PLAINTEXT_ENTRIES = "entries"

// What a plaintext export is written as. Both carry every secret in the clear and differ in what
// survives being read back: a URI carries what another authenticator enrols from, and no more.
enum class ExportFormat {
    JSON,
    URI_LIST,
}

// The order the vault holds, the creation time and the entry ids, none of which a URI has a field
// for. The policy is left out: it governs this application and enrols nothing.
@Serializable
data class PlaintextExport(
    val v: Int = PLAINTEXT_EXPORT_VERSION,
    @SerialName(PLAINTEXT_ENTRIES) val entries: List<VaultEntry> = emptyList(),
)

// The secrets are already base32 text in the body this reads, which §16.8 records as the reason they
// outlive their decode; nothing here converts a key to a String that was not one already.
internal fun VaultBody.exported(format: ExportFormat): String {
    val ordered = entries.sortedBy { it.orderIndex }
    return when (format) {
        ExportFormat.JSON -> plaintextExportJson.encodeToString(PlaintextExport(entries = ordered))

        // Each account ends its own line, so the last is a whole line like the rest and an empty
        // vault is an empty file rather than a blank line.
        ExportFormat.URI_LIST -> ordered.joinToString("") { "${it.toOtpAuthUri().build()}\n" }
    }
}
