package com.clawmate.app.profile.avatar.generation

import com.clawmate.app.profile.avatar.data.generation.AvatarProviderLimits
import com.clawmate.app.profile.avatar.data.generation.SeedreamResponseParser
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the Seedream JSON response parser + bounded base64
 * decode, ported from `normalizeSeedreamImageUrl`/`normalizeSeedreamImageBase64`.
 */
class SeedreamResponseParserTest {

    @Test
    fun `parses first url and base64 from data array`() {
        val raw = """{"id":"req-7","data":[{"url":"https://cdn/x.png","b64_json":"aGVsbG8="}]}"""
        val result = SeedreamResponseParser.parse(raw)
        assertEquals("https://cdn/x.png", result.imageUrl)
        assertEquals("aGVsbG8=", result.imageBase64)
        assertEquals("req-7", result.providerRequestId)
    }

    @Test
    fun `empty data yields empty result`() {
        val result = SeedreamResponseParser.parse("""{"id":"x","data":[]}""")
        assertEquals("", result.imageUrl)
        assertEquals("", result.imageBase64)
    }

    @Test
    fun `only uses the first data item`() {
        val raw = """{"data":[{"url":"first","b64_json":"AAA="},{"url":"second"}]}"""
        val result = SeedreamResponseParser.parse(raw)
        assertEquals("first", result.imageUrl)
        assertEquals("AAA=", result.imageBase64)
    }

    @Test
    fun `non array data or absent url yields empty`() {
        assertEquals("", SeedreamResponseParser.seedreamImageUrl("""{"data":"not-array"}"""))
        assertEquals("", SeedreamResponseParser.seedreamImageBase64("""{"data":[{"foo":"x"}]}"""))
        assertEquals("", SeedreamResponseParser.parse("not json").imageUrl)
        assertNull(SeedreamResponseParser.decodeBoundedBase64(""))
    }

    @Test
    fun `normalize helpers match the source first-data-string behaviour`() {
        assertEquals("https://cdn/a.png", SeedreamResponseParser.seedreamImageUrl(
            """{"data":[{"url":"  https://cdn/a.png  "}]}"""))
        assertEquals("abc", SeedreamResponseParser.seedreamImageBase64(
            """{"data":[{"b64_json":"  abc  "}]}"""))
    }

    @Test
    fun `bounded base64 decode round-trips real bytes`() {
        val original = ByteArray(1024) { (it % 251).toByte() }
        val encoded = Base64.getEncoder().encodeToString(original)
        val decoded = SeedreamResponseParser.decodeBoundedBase64(encoded)
        assertArrayEquals(original, decoded)
    }

    @Test
    fun `bounded base64 rejects oversized and non-base64 input`() {
        assertNull(SeedreamResponseParser.decodeBoundedBase64("#$%not base64!!"))
        val oversized = "A".repeat(AvatarProviderLimits.MAX_BASE64_LENGTH + 1)
        assertNull(SeedreamResponseParser.decodeBoundedBase64(oversized))
    }

    @Test
    fun `decoded size past image cap is rejected`() {
        // A base64 string that decodes to more than the image byte cap.
        val bigBytes = ByteArray(AvatarProviderLimits.MAX_IMAGE_BYTES + 1)
        val encoded = Base64.getEncoder().encodeToString(bigBytes)
        assertEquals(null, SeedreamResponseParser.decodeBoundedBase64(encoded))
    }

    @Test
    fun `data uri base64 prefix is stripped before decode`() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val withPrefix = "data:image/png;base64,$encoded"
        val decoded = SeedreamResponseParser.decodeBoundedBase64(withPrefix)
        assertTrue(decoded != null)
        assertEquals(3, decoded!!.size)
    }
}
