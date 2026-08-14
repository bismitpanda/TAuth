package com.panda.tauth.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultBodyTest {
    @Test
    fun `renumbering closes the gap left by a deletion`() {
        val body = VaultBody(
            entries = listOf(
                totpEntry(id = "a", orderIndex = 0),
                totpEntry(id = "c", orderIndex = 2),
                totpEntry(id = "d", orderIndex = 3),
            ),
        )
        assertEquals(listOf(0, 1, 2), body.renumbered().entries.map { it.orderIndex })
    }

    @Test
    fun `renumbering after a deletion keeps the surviving order`() {
        val body = VaultBody(
            entries = listOf(
                totpEntry(id = "a", orderIndex = 0),
                totpEntry(id = "c", orderIndex = 2),
                totpEntry(id = "d", orderIndex = 3),
            ),
        )
        assertEquals(listOf("a", "c", "d"), body.renumbered().entries.map { it.id })
    }

    @Test
    fun `an entry inserted at the end takes the next index`() {
        val body = VaultBody(
            entries = listOf(
                totpEntry(id = "a", orderIndex = 0),
                totpEntry(id = "b", orderIndex = 1),
                totpEntry(id = "new", orderIndex = Int.MAX_VALUE),
            ),
        )
        val renumbered = body.renumbered().entries
        assertEquals(2, renumbered.single { it.id == "new" }.orderIndex)
    }

    @Test
    fun `a reorder is applied in index order regardless of list order`() {
        val body = VaultBody(
            entries = listOf(
                totpEntry(id = "third", orderIndex = 20),
                totpEntry(id = "first", orderIndex = 5),
                totpEntry(id = "second", orderIndex = 10),
            ),
        )
        assertEquals(listOf("first", "second", "third"), body.renumbered().entries.map { it.id })
    }

    @Test
    fun `entries sharing an index keep the order they arrived in`() {
        val body = VaultBody(
            entries = listOf(
                totpEntry(id = "a", orderIndex = 0),
                totpEntry(id = "b", orderIndex = 0),
            ),
        )
        assertEquals(listOf("a", "b"), body.renumbered().entries.map { it.id })
    }

    @Test
    fun `an empty body renumbers to an empty body`() {
        assertEquals(emptyList(), VaultBody().renumbered().entries)
    }

    @Test
    fun `a body round-trips through JSON`() {
        val body = VaultBody(entries = listOf(totpEntry(), hotpEntry(counter = 42uL)))
        assertEquals(body, vaultJson.decodeFromString<VaultBody>(vaultJson.encodeToString(body)))
    }

    @Test
    fun `a body with no entries key decodes to an empty list`() {
        assertEquals(emptyList(), vaultJson.decodeFromString<VaultBody>("""{"v":1}""").entries)
    }

    @Test
    fun `a body writes version 1`() {
        assertTrue("\"v\":1" in vaultJson.encodeToString(VaultBody()))
    }
}
