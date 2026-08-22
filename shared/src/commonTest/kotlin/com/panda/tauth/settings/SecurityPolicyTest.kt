package com.panda.tauth.settings

import com.panda.tauth.vault.VaultBody
import com.panda.tauth.vault.vaultJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun policyFrom(json: String): SecurityPolicy = vaultJson.decodeFromString<VaultBody>(json).policy

class SecurityPolicyTest {
    // One default per test, so loosening any one of them names itself in the failure.

    @Test
    fun `the idle timeout defaults to five minutes`() {
        assertEquals(5, SecurityPolicy().idleTimeoutMinutes)
    }

    @Test
    fun `locking on minimize defaults to on`() {
        assertTrue(SecurityPolicy().lockOnMinimize)
    }

    @Test
    fun `locking on focus loss defaults to off`() {
        // Copying a code and switching to a browser is the commonest interaction, and locking on it
        // would cost a full Argon2id derivation every time.
        assertFalse(SecurityPolicy().lockOnFocusLoss)
    }

    @Test
    fun `the hide grace period defaults to none`() {
        assertEquals(0, SecurityPolicy().hideGraceSeconds)
    }

    @Test
    fun `the clipboard clear delay defaults to twenty seconds`() {
        assertEquals(20, SecurityPolicy().clipboardClearSeconds)
    }

    @Test
    fun `a body with no policy object yields the full defaults`() {
        assertEquals(SecurityPolicy(), policyFrom("""{"v":1,"entries":[]}"""))
    }

    @Test
    fun `a partial policy fills the idle timeout from the default`() {
        assertEquals(5, policyFrom("""{"v":1,"policy":{"lockOnFocusLoss":true}}""").idleTimeoutMinutes)
    }

    @Test
    fun `a partial policy keeps the value it does carry`() {
        assertTrue(policyFrom("""{"v":1,"policy":{"lockOnFocusLoss":true}}""").lockOnFocusLoss)
    }

    @Test
    fun `an unknown policy key is ignored`() {
        assertEquals(SecurityPolicy(), policyFrom("""{"v":1,"policy":{"lockOnFullMoon":true}}"""))
    }

    @Test
    fun `a policy round-trips through JSON`() {
        val policy = SecurityPolicy(
            idleTimeoutMinutes = 1,
            lockOnMinimize = false,
            lockOnFocusLoss = true,
            hideGraceSeconds = 30,
            clipboardClearSeconds = 60,
        )
        val body = VaultBody(policy = policy)
        assertEquals(policy, vaultJson.decodeFromString<VaultBody>(vaultJson.encodeToString(body)).policy)
    }

    @Test
    fun `the policy carries no display setting`() {
        // Display settings live in preferences.json, outside the encrypted body.
        assertFalse("sortOrder" in vaultJson.encodeToString(VaultBody(policy = SecurityPolicy())))
    }

    @Test
    fun `a negative idle timeout is rejected`() {
        // It reads as disabled to every check while naming a duration, so it would switch the
        // control off in a body that appears to set it.
        assertFailsWith<IllegalArgumentException> { SecurityPolicy(idleTimeoutMinutes = -1) }
    }

    @Test
    fun `a negative clipboard delay is rejected`() {
        assertFailsWith<IllegalArgumentException> { SecurityPolicy(clipboardClearSeconds = -1) }
    }

    @Test
    fun `a negative hide grace period is rejected`() {
        assertFailsWith<IllegalArgumentException> { SecurityPolicy(hideGraceSeconds = -1) }
    }

    @Test
    fun `a body carrying a negative duration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            policyFrom("""{"v":1,"policy":{"idleTimeoutMinutes":-1}}""")
        }
    }
}
