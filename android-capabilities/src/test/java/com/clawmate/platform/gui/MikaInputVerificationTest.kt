package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.TextField
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.uistate.typeAndVerify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MikaInputVerificationTest {
    @Test fun frozenAccessibleTextWithNewSuggestionsIsUnknownNotFailure() = runTest {
        var writes = 0
        val field = object : TextField {
            override fun read() = ""
            override fun write(text: String): Boolean { writes++; return true }
        }
        assertEquals(Verification.UNKNOWN, typeAndVerify(field, "上海城市漫步", reacted = { true }))
        assertEquals(2, writes)
    }
    @Test fun actualWrongTextRemainsMismatchDespiteOtherScreenChanges() = runTest {
        var written = false
        val field = object : TextField {
            override fun read() = if (written) "错误内容" else ""
            override fun write(text: String): Boolean { written = true; return true }
        }
        assertEquals(Verification.MISMATCH, typeAndVerify(field, "上海城市漫步", reacted = { true }))
    }
}
