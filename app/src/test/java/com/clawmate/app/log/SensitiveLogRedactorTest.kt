package com.clawmate.app.log

import com.clawmate.app.log.domain.SensitiveLogRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic tests for [SensitiveLogRedactor]. */
class SensitiveLogRedactorTest {

    @Test
    fun `auth header bearer token is redacted`() {
        val out = SensitiveLogRedactor.redact("Authorization: Bearer abcdef1234567890")
        assertFalse(out.contains("abcdef1234567890"))
        assertTrue(out.contains(SensitiveLogRedactor.MASK))
        assertFalse(out.contains("Bearer"))
    }

    @Test
    fun `openai style api key is redacted`() {
        val out = SensitiveLogRedactor.redact("call failed with key sk-1234567890abcdef")
        assertFalse(out.contains("sk-1234567890abcdef"))
        assertTrue(out.contains(SensitiveLogRedactor.MASK))
    }

    @Test
    fun `api key assignment is redacted`() {
        val out = SensitiveLogRedactor.redact("payload api_key=SECRET123456 body=ok")
        assertFalse(out.contains("SECRET123456"))
        assertTrue(out.contains(SensitiveLogRedactor.MASK))
    }

    @Test
    fun `aws secret access key is redacted`() {
        val out = SensitiveLogRedactor.redact("AWS_SECRET_ACCESS_KEY=supersecretvalue000")
        assertFalse(out.contains("supersecretvalue000"))
        assertTrue(out.contains(SensitiveLogRedactor.MASK))
    }

    @Test
    fun `github token is redacted`() {
        val out = SensitiveLogRedactor.redact("token ghp_0123456789abcdefghij")
        assertFalse(out.contains("ghp_0123456789abcdefghij"))
        assertTrue(out.contains(SensitiveLogRedactor.MASK))
    }

    @Test
    fun `generic password key keeps label masks value`() {
        val out = SensitiveLogRedactor.redact("password=hunter2 please")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("password="))
        assertTrue(out.contains(SensitiveLogRedactor.MASK))
    }

    @Test
    fun `benign text passes through unchanged`() {
        val msg = "[App] initialized endpoint=https://example.com health=ok"
        assertEquals(msg, SensitiveLogRedactor.redact(msg))
    }

    @Test
    fun `blank message passes through`() {
        assertEquals("", SensitiveLogRedactor.redact(""))
        assertEquals("   ", SensitiveLogRedactor.redact("   "))
    }

    @Test
    fun `redact is deterministic`() {
        val input = "Authorization: Bearer abc123 api_key=XYZ987 body ok"
        assertEquals(SensitiveLogRedactor.redact(input), SensitiveLogRedactor.redact(input))
    }

    @Test
    fun `already masked value is not re-expanded`() {
        val input = "token = [REDACTED]"
        val out = SensitiveLogRedactor.redact(input)
        assertTrue(out.contains("[REDACTED]"))
        // No second masking pass should change it; assert stable.
        assertEquals(out, SensitiveLogRedactor.redact(out))
    }
}
