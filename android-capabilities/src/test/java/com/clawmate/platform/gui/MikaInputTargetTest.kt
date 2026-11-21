package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Typing without an explicit control failed on a real phone with `target_missing_or_ambiguous`:
 * the kernel's default path only accepts an already-focused editable node, but focus lands a few
 * hundred milliseconds after the tap. These cases pin the fallback rules.
 */
class MikaInputTargetTest {

    private fun element(ref: String, editable: Boolean, focused: Boolean) = UiElement(
        ref = ref, role = "EditText", name = "", centerX = 5, centerY = 5,
        bounds = listOf(0, 0, 10, 10), resourceId = null,
        clickable = false, editable = editable, scrollable = false, focused = focused,
    )

    @Test
    fun `the single focused editable wins`() {
        val picked = pickEditableTarget(
            listOf(element("@e1", true, true), element("@e2", true, false), element("@e3", false, false)),
        )
        assertEquals("@e1", picked?.ref)
    }

    @Test
    fun `a single editable waits until focus arrives`() {
        val picked = pickEditableTarget(
            listOf(element("@e1", true, false), element("@e2", false, false)),
        )
        assertNull(picked)
    }

    @Test
    fun `two editables without focus stay ambiguous instead of guessing`() {
        assertNull(pickEditableTarget(listOf(element("@e1", true, false), element("@e2", true, false))))
    }

    @Test
    fun `a screen without editable controls has no typing target`() {
        assertNull(pickEditableTarget(listOf(element("@e1", false, true))))
    }

    @Test
    fun `a focused box wins over another one that is not focused`() {
        val picked = pickEditableTarget(listOf(element("@e1", true, false), element("@e2", true, true)))
        assertEquals("@e2", picked?.ref)
    }
}
