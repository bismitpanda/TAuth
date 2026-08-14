package com.panda.tauth.vault

import com.panda.tauth.settings.SecurityPolicy
import kotlinx.serialization.Serializable

// An absent policy object decodes to the full default set.
@Serializable
data class VaultBody(
    val v: Int = BODY_VERSION,
    val policy: SecurityPolicy = SecurityPolicy(),
    val entries: List<VaultEntry> = emptyList(),
) {
    // Stable, so entries sharing an index keep the order they already had.
    fun renumbered(): VaultBody = copy(
        entries = entries
            .sortedBy { it.orderIndex }
            .mapIndexed { index, entry -> entry.copy(orderIndex = index) },
    )
}
