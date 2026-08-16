package com.clawmate.app.profile.avatar.generation

import com.clawmate.app.profile.avatar.data.generation.AvatarUrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for strict URL normalization + content-type validation used
 * by the avatar provider. No network I/O.
 */
class AvatarUrlValidatorTest {

    @Test
    fun `accepts well-formed http and https base urls`() {
        assertTrue(AvatarUrlValidator.validateBaseUrl("https://img.example.com").valid)
        assertTrue(AvatarUrlValidator.validateBaseUrl("https://img.example.com/").valid)
        assertTrue(AvatarUrlValidator.validateBaseUrl("http://10.0.0.5:8080").valid)
        assertTrue(AvatarUrlValidator.validateBaseUrl("https://example.com/v1/generation").valid)
    }

    @Test
    fun `normalizes a trailing slash off the base url`() {
        assertEquals(
            "https://img.example.com",
            AvatarUrlValidator.validateBaseUrl("https://img.example.com/").normalized,
        )
        assertEquals(
            "https://img.example.com",
            AvatarUrlValidator.validateBaseUrl("  https://img.example.com  ").normalized,
        )
    }

    @Test
    fun `rejects blank relative ftp or other schemes`() {
        assertFalse(AvatarUrlValidator.validateBaseUrl("").valid)
        assertFalse(AvatarUrlValidator.validateBaseUrl("  ").valid)
        assertFalse(AvatarUrlValidator.validateBaseUrl("ftp://x.example.com").valid)
        assertFalse(AvatarUrlValidator.validateBaseUrl("not-a-url").valid)
    }

    @Test
    fun `rejects urls with embedded credentials`() {
        assertFalse(AvatarUrlValidator.validateBaseUrl("https://user:pass@host.example.com").valid)
    }

    @Test
    fun `rejects hosts with spaces or missing host`() {
        assertFalse(AvatarUrlValidator.validateBaseUrl("https:///path").valid)
        assertFalse(AvatarUrlValidator.validateBaseUrl("https://has spaces.example.com").valid)
    }

    @Test
    fun `download url validation mirrors base validation`() {
        assertTrue(AvatarUrlValidator.validateDownloadUrl("https://cdn.example.com/a.png").valid)
        assertFalse(AvatarUrlValidator.validateDownloadUrl("file:///tmp/a.png").valid)
    }

    @Test
    fun `well-formed http url check is strict`() {
        assertTrue(AvatarUrlValidator.isWellFormedHttpUrl("https://cdn.example.com/a/b.png"))
        assertTrue(AvatarUrlValidator.isWellFormedHttpUrl("https://10.0.0.5:8443/x"))
        assertFalse(AvatarUrlValidator.isWellFormedHttpUrl("//cdn.example.com/a.png"))
        assertFalse(AvatarUrlValidator.isWellFormedHttpUrl("javascript:alert(1)"))
    }

    @Test
    fun `content type acceptance only for images`() {
        assertTrue(AvatarUrlValidator.acceptedContentType("image/jpeg"))
        assertTrue(AvatarUrlValidator.acceptedContentType("image/png"))
        assertTrue(AvatarUrlValidator.acceptedContentType("image/webp"))
        assertTrue(AvatarUrlValidator.acceptedContentType("image/jpeg; charset=utf-8"))
        assertFalse(AvatarUrlValidator.acceptedContentType("text/html"))
        assertFalse(AvatarUrlValidator.acceptedContentType("application/octet-stream"))
        assertFalse(AvatarUrlValidator.acceptedContentType(null))
        assertFalse(AvatarUrlValidator.acceptedContentType(""))
        assertFalse(AvatarUrlValidator.acceptedContentType("image/svg+xml"))
    }
}
