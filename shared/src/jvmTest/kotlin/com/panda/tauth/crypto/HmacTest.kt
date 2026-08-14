package com.panda.tauth.crypto

import com.panda.tauth.totp.HashAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals

// RFC 2202 case 1 and RFC 4231 case 1 share their inputs.
private val KEY = ByteArray(20) { 0x0b }
private val DATA = "Hi There".encodeToByteArray()

// Case 6 of each: a key longer than the hash's block size, which HMAC hashes down to a block before
// using it. The two RFCs specify different key lengths for that case and both are kept as published.
private val LONG_KEY_2202 = ByteArray(80) { 0xaa.toByte() }
private val LONG_KEY_4231 = ByteArray(131) { 0xaa.toByte() }
private val LONG_KEY_DATA = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()

class HmacTest {
    @Test
    fun `RFC 2202 case 1 for HMAC-SHA-1`() {
        assertEquals("b617318655057264e28bc0b6fb378c8ef146be00", hmac(HashAlgorithm.SHA1, KEY, DATA).toHexString())
    }

    @Test
    fun `RFC 4231 case 1 for HMAC-SHA-256`() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hmac(HashAlgorithm.SHA256, KEY, DATA).toHexString(),
        )
    }

    @Test
    fun `RFC 4231 case 1 for HMAC-SHA-512`() {
        assertEquals(
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cded" +
                "aa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            hmac(HashAlgorithm.SHA512, KEY, DATA).toHexString(),
        )
    }

    @Test
    fun `RFC 2202 case 6 for HMAC-SHA-1`() {
        assertEquals(
            "aa4ae5e15272d00e95705637ce8a3b55ed402112",
            hmac(HashAlgorithm.SHA1, LONG_KEY_2202, LONG_KEY_DATA).toHexString(),
        )
    }

    @Test
    fun `RFC 4231 case 6 for HMAC-SHA-256`() {
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hmac(HashAlgorithm.SHA256, LONG_KEY_4231, LONG_KEY_DATA).toHexString(),
        )
    }

    @Test
    fun `RFC 4231 case 6 for HMAC-SHA-512`() {
        assertEquals(
            "80b24263c7c1a3ebb71493c1dd7be8b49b46d1f41b4aeec1121b013783f8f352" +
                "6b56d037e05f2598bd0fd2215d6a1e5295e64f73f63f0aec8b915a985d786598",
            hmac(HashAlgorithm.SHA512, LONG_KEY_4231, LONG_KEY_DATA).toHexString(),
        )
    }
}
