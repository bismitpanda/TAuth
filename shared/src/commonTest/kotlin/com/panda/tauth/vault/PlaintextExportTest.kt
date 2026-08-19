package com.panda.tauth.vault

import com.panda.tauth.settings.SecurityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

// Two entries whose order indices run backwards against the list they sit in, so an export that
// takes the list as it finds it disagrees with one that reads the order the vault stores. Their
// creation times differ too, since one shared by both would survive an export that dropped it.
private val LATER = totpEntry(orderIndex = 1).copy(createdAt = Instant.parse("2026-09-01T00:00:00Z"))
private val FIRST = hotpEntry(orderIndex = 0)

private val BODY = VaultBody(
    policy = SecurityPolicy(idleTimeoutMinutes = 9),
    entries = listOf(LATER, FIRST),
)

class PlaintextExportTest {
    @Test
    fun `a uri list carries one line an account`() {
        assertEquals(2, BODY.exported(ExportFormat.URI_LIST).trim().lines().size)
    }

    @Test
    fun `a uri list is in the order the vault stores`() {
        val lines = BODY.exported(ExportFormat.URI_LIST).trim().lines()

        assertEquals(FIRST.toOtpAuthUri().build(), lines.first())
    }

    // A line the reader splits on has to be whole, and the last account is a line like the others.
    @Test
    fun `a uri list ends its last line`() {
        assertTrue(BODY.exported(ExportFormat.URI_LIST).endsWith("\n"))
    }

    @Test
    fun `an empty vault exports an empty uri list`() {
        assertEquals("", VaultBody().exported(ExportFormat.URI_LIST))
    }

    @Test
    fun `json carries every account`() {
        val document = plaintextExportJson.decodeFromString<PlaintextExport>(BODY.exported(ExportFormat.JSON))

        assertEquals(2, document.entries.size)
    }

    @Test
    fun `json is in the order the vault stores`() {
        val document = plaintextExportJson.decodeFromString<PlaintextExport>(BODY.exported(ExportFormat.JSON))

        assertEquals(FIRST.id, document.entries.first().id)
    }

    // The order index, the creation time and the id have no field in a URI, and carrying them is the
    // whole reason the two formats are not one. Read off the entry holding neither default, so a
    // field dropped on the way through decodes to something this disagrees with.
    @Test
    fun `json carries what a uri has no field for`() {
        val document = plaintextExportJson.decodeFromString<PlaintextExport>(BODY.exported(ExportFormat.JSON))
        val restored = document.entries.last()

        assertEquals(
            listOf(LATER.id, LATER.createdAt, LATER.orderIndex),
            listOf(restored.id, restored.createdAt, restored.orderIndex),
        )
    }

    @Test
    fun `json states the version it was written at`() {
        val document = plaintextExportJson.decodeFromString<PlaintextExport>(BODY.exported(ExportFormat.JSON))

        assertEquals(PLAINTEXT_EXPORT_VERSION, document.v)
    }

    // The policy governs this application and enrols nothing, so it is not what a migration carries.
    @Test
    fun `json carries no policy`() {
        assertFalse("idleTimeoutMinutes" in BODY.exported(ExportFormat.JSON))
    }

    @Test
    fun `a counter-based account exports at the counter it stands at`() {
        val exported = VaultBody(entries = listOf(hotpEntry(counter = 41uL))).exported(ExportFormat.URI_LIST)

        assertTrue("counter=41" in exported)
    }
}
