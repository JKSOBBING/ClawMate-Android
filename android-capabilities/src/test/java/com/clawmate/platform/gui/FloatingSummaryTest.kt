package com.clawmate.platform.gui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingSummaryTest {
    @Test fun extractsDistinctReadableLinesAndKeepsShortOnlyPages() {
        assertEquals("今日 公告 · 会议十点开始", floatingSummary(listOf("首页", "今日  公告", "今日 公告", "会议十点开始", "返回")))
        assertEquals("731 · ×", floatingSummary(listOf("731", "×", "731")))
        assertEquals("", floatingSummary(listOf(" ", "")))
        val lines = (1..8).map { "$it" + "字".repeat(100) }
        assertEquals(lines.take(4).joinToString(" · ") { it.take(72) }, floatingSummary(lines))
    }

    @Test fun existingLocalCapturesRemainReadableAlongsideNewSummaries() {
        val old = """{"sourcePackage":"org.example","sourceLabel":"Fixture","capturedAt":1,"lines":["文本"],"partial":false}"""
        val record = Json.decodeFromString<GuiCollection>(old)
        assertEquals("", record.summary)
        assertEquals("本页摘要", Json.decodeFromString<GuiCollection>(old.dropLast(1) + ""","summary":"本页摘要"}""").summary)
    }
}
