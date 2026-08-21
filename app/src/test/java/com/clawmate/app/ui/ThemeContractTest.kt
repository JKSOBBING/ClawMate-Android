package com.clawmate.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clawmate.app.ui.theme.BlueViolet700
import com.clawmate.app.ui.theme.ClawMateDesign
import com.clawmate.app.ui.theme.MutedText
import com.clawmate.app.ui.theme.NearWhite
import com.clawmate.app.ui.theme.TabIndicator
import com.clawmate.app.ui.theme.TabSelected
import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks the Android shell palette to the HarmonyOS light-theme contract. */
class ThemeContractTest {

    @Test
    fun `shell palette matches harmony reference`() {
        assertEquals(Color(0xFF8066FF), BlueViolet700)
        assertEquals(Color(0xFFF4F8FF), NearWhite)
        assertEquals(Color(0xFF5B5CE2), TabSelected)
        assertEquals(Color(0xFFEEF0FF), TabIndicator)
        assertEquals(Color(0xFF747B87), MutedText)
    }

    /**
     * Locks the shared form-input contract used by every page (see
     * ClawMateInputs.kt) to the HarmonyOS settings field spec: 42dp single-line
     * height, 12dp control radius, 40dp button height, #F2F5FC input fill.
     */
    @Test
    fun `input contract matches harmony settings field spec`() {
        assertEquals(42.dp, ClawMateDesign.Size.InputHeight)
        assertEquals(40.dp, ClawMateDesign.Size.ButtonHeight)
        assertEquals(12.dp, ClawMateDesign.Shape.Control)
        assertEquals(Color(0xFFF2F5FC), ClawMateDesign.Colors.Input)
        assertEquals(Color(0xFFE6EAF2), ClawMateDesign.Colors.InputDisabled)
        assertEquals(Color(0xFFE3E8F1), ClawMateDesign.Colors.Border)
    }
}
