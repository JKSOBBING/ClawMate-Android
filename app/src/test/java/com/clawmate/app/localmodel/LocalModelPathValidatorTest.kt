package com.clawmate.app.localmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deterministic tests for the app-private model path validator: allows only
 * `filesDir/model` and `filesDir/modelscope/<repo>`, rejects traversal,
 * escapes, and anything outside the app sandbox. No real storage touched.
 */
class LocalModelPathValidatorTest {

    private val filesDir = "/data/user/0/com.clawmate.app/files"

    @Test
    fun `legacy model dir is accepted`() {
        assertTrue(LocalModelPathValidator.isValidModelDir(filesDir, "$filesDir/model"))
        assertTrue(LocalModelPathValidator.isLegacyModelDir(filesDir, "$filesDir/model"))
    }

    @Test
    fun `modelscope repo dir is accepted`() {
        val path = "$filesDir/modelscope/owner__repo"
        assertTrue(LocalModelPathValidator.isValidModelDir(filesDir, path))
        // a repo dir is NOT a direct child of filesDir
        assertFalse(LocalModelPathValidator.isLegacyModelDir(filesDir, path))
    }

    @Test
    fun `escapes out of filesDir are rejected`() {
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, "$filesDir/../etc"))
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, "$filesDir/model/../../evil"))
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, "/data/user/0/other.app/files/model"))
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, "/sdcard/model"))
    }

    @Test
    fun `traversal and dot segments are rejected`() {
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, "$filesDir/model/.."))
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, "$filesDir/model/../model2"))
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, "$filesDir/model/./model"))
        assertTrue(LocalModelPathValidator.hasEscapingSegment("../model"))
        assertTrue(LocalModelPathValidator.hasEscapingSegment("model/.."))
    }

    @Test
    fun `non whitelisted base dir is rejected`() {
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, "$filesDir/other"))
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, "$filesDir/avatar/model"))
    }

    @Test
    fun `null blank or the filesDir root itself are rejected`() {
        assertFalse(LocalModelPathValidator.isValidModelDir(null, null))
        assertFalse(LocalModelPathValidator.isValidModelDir("", "$filesDir/model"))
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, filesDir))
        assertFalse(LocalModelPathValidator.isValidModelDir(filesDir, "$filesDir/"))
    }

    @Test
    fun `resolveModelDir only resolves safe relative ids`() {
        assertEqualsNotNull("model", filesDir, LocalModelPathValidator.resolveModelDir(filesDir, "model"))
        assertNull(LocalModelPathValidator.resolveModelDir(filesDir, "../etc"))
        assertNull(LocalModelPathValidator.resolveModelDir(filesDir, "model/../../evil"))
        assertNull(LocalModelPathValidator.resolveModelDir(filesDir, null))
    }

    private fun assertEqualsNotNull(expectedTail: String, filesDir: String?, resolved: String?) {
        assertTrue("resolved should not be null", resolved != null)
        assertTrue("resolved ends with $expectedTail", resolved!!.endsWith("/$expectedTail"))
    }
}
