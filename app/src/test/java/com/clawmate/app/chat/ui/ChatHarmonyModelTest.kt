package com.clawmate.app.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHarmonyModelTest {
    @Test fun sourceComposerCountsEmptySegmentsAndUtf16WithFourLineCap() {
        assertEquals(58, harmonyComposerHeight(""))
        assertEquals(58, harmonyComposerHeight("1234567890"))
        assertEquals(80, harmonyComposerHeight("12345678901"))
        assertEquals(102, harmonyComposerHeight("\n\n"))
        assertEquals(124, harmonyComposerHeight("\n\n\n\n\n"))
        assertEquals(80, harmonyComposerHeight("😀".repeat(6)))
    }
    @Test fun sourceTimeBoundaries() {
        assertEquals(listOf("夜深了", "早上好", "中午好", "下午好", "晚上好", "晚上好"),
            listOf(5, 6, 11, 14, 18, 23).map(::harmonyChatGreeting))
    }
}
