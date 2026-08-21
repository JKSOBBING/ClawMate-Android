package com.clawmate.app.ui

import androidx.compose.ui.graphics.Color
import com.clawmate.app.ui.theme.BlueViolet700
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
}
