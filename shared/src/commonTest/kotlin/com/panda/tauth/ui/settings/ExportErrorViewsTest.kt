package com.panda.tauth.ui.settings

import com.panda.tauth.vault.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals

// Each view is asserted as a whole set, so a membership added to a case fails as well as one dropped.
class ExportErrorViewsTest {
    @Test
    fun `writing a file reports the destination and nothing about a vault`() {
        assertEquals(setOf("NotRestricted", "Io"), namesIn<FileWriteError>())
    }

    @Test
    fun `a copy of the vault reports the read as well as the write`() {
        assertEquals(setOf("VaultUnreadable", "NotRestricted", "Io"), namesIn<VaultExportError>())
    }

    // A case added stops nameOf compiling; naming it there then fails this count until it joins the
    // list every view above is measured over.
    @Test
    fun `every case is accounted for`() {
        assertEquals(3, ALL_CASES.size)
        assertEquals(ALL_CASES.map(::nameOf), ALL_CASES.map { it::class.simpleName })
    }
}

private val ALL_CASES: List<ExportError> = listOf(
    ExportError.VaultUnreadable(VaultError.NoVaultFile),
    ExportError.NotRestricted,
    ExportError.Io(IllegalStateException("a destination that refused the bytes")),
)

private inline fun <reified V : ExportError> namesIn(): Set<String> =
    ALL_CASES.filterIsInstance<V>().map { it::class.simpleName.orEmpty() }.toSet()

// No else branch: a case added to the hierarchy has to be named here before this compiles again.
private fun nameOf(error: ExportError): String = when (error) {
    is ExportError.VaultUnreadable -> "VaultUnreadable"
    is ExportError.NotRestricted -> "NotRestricted"
    is ExportError.Io -> "Io"
}
