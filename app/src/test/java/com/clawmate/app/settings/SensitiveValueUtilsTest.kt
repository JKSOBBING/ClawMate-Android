package com.clawmate.app.settings

import com.clawmate.app.settings.domain.SensitiveValueUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sensitive masking / reveal / restore behaviour (mirrors SensitiveValueUtils.ets). */
class SensitiveValueUtilsTest {

    @Test
    fun `blank is never masked`() {
        assertFalse(SensitiveValueUtils.isMaskedSensitiveValue(""))
        assertFalse(SensitiveValueUtils.isMaskedSensitiveValue("   "))
    }

    @Test
    fun `mask marker is detected anywhere`() {
        assertTrue(SensitiveValueUtils.isMaskedSensitiveValue("abcd••••"))
        assertTrue(SensitiveValueUtils.isMaskedSensitiveValue("••••"))
        assertTrue(SensitiveValueUtils.isMaskedSensitiveValue(" •• tail"))
        assertFalse(SensitiveValueUtils.isMaskedSensitiveValue("plain-secret"))
    }

    @Test
    fun `mask is deterministic and length-preserving`() {
        assertEquals("", SensitiveValueUtils.maskSensitiveValue(""))
        assertEquals("••", SensitiveValueUtils.maskSensitiveValue("ab"))
        assertEquals("••••", SensitiveValueUtils.maskSensitiveValue("abcd"))
        // length > 4 -> first4 + 8 bullets + last4 (matches HarmonyOS)
        assertEquals("abcd••••••••efgh", SensitiveValueUtils.maskSensitiveValue("abcdefgh"))
        // masking an already-masked value is idempotent-enough and still marked
        assertEquals("••••", SensitiveValueUtils.maskSensitiveValue("••••"))
    }

    @Test
    fun `restore masked default recovers the exact default`() {
        val secret = "super-secret-key"
        val masked = SensitiveValueUtils.maskSensitiveValue(secret)
        assertEquals(secret, SensitiveValueUtils.restoreMaskedSensitiveSettingDefault(masked, secret))
    }

    @Test
    fun `restore masked unknown value yields empty (never invented)`() {
        // A masked value that does NOT correspond to the given default is not
        // guessed; it restores to "" so we never write a wrong literal back.
        val maskedDifferent = SensitiveValueUtils.maskSensitiveValue("some-other-secret")
        assertEquals("", SensitiveValueUtils.restoreMaskedSensitiveSettingDefault(maskedDifferent, "the-default"))
    }

    @Test
    fun `non-masked value passes through restore untouched`() {
        assertEquals("real-value", SensitiveValueUtils.restoreMaskedSensitiveSettingDefault("real-value", "default"))
        // blank default with a masked value is not recoverable
        assertEquals("", SensitiveValueUtils.restoreMaskedSensitiveSettingDefault(SensitiveValueUtils
            .maskSensitiveValue("abc"), ""))
    }
}
