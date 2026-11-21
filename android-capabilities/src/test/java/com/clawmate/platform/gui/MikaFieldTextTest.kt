package com.clawmate.platform.gui

import org.junit.Assert.*
import org.junit.Test

class MikaFieldTextTest {
    @Test fun actualSearchQueryIsNotReplacedWithPlaceholder() {
        assertEquals("上海城市漫步", mikaFieldText("上海城市漫步", false, false, "搜索", "上海城市漫步"))
        assertEquals("", mikaFieldText("搜索", true, false, "搜索", "上海城市漫步"))
    }
    @Test fun ambiguousAndSensitiveReadbackMustNotTriggerBlindRewrite() {
        assertNull(mikaFieldText("搜索 上海", false, false, "搜索", "上海"))
        assertNull(mikaFieldText("private", false, true, null, "private"))
    }
    @Test fun typingThePlaceholderItselfCanStillMatch() {
        assertEquals("搜索", mikaFieldText("搜索", false, false, "搜索", "搜索"))
    }
    @Test fun displayedHintTakesPrecedenceOverASeparateRecommendation() {
        val placeholder = mikaInputPlaceholder("搜索, ", "上海citywalk必逛路线", true)
        assertEquals("搜索,", placeholder)
        assertNull(mikaFieldText("搜索, 上海城市漫步", false, false, placeholder, "上海城市漫步"))
        assertNull(mikaInputPlaceholder("已经输入的值", "搜索", false))
    }
}
