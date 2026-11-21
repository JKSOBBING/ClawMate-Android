package com.clawmate.platform.gui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MikaInputTest {
    @Test fun allowsTextTheUserActuallyAskedFor() {
        assertTrue(MikaInput.allowed("帮我在备忘录里写年终总结", "年终总结"))
        assertTrue(MikaInput.allowed("搜一下附近好吃的火锅", "火锅"))
        assertTrue(MikaInput.allowed("open YouTube and search for Formula 1", "Formula 1"))
        assertTrue("english case-insensitive", MikaInput.allowed("open YouTube and search Formula", "formula"))
    }

    @Test fun derivedInputAndMultilineDraftsMatchMikaButInvalidControlsAreRejected() {
        assertTrue("computed values are valid input", MikaInput.allowed("帮我订明天的闹钟", "2026-11-18"))
        assertTrue("multiline draft", MikaInput.allowed("写两行总结", "第一行\n第二行"))
        assertFalse("over upstream cap", MikaInput.allowed("写摘要", "a".repeat(10001)))
        assertFalse("NUL is invalid", MikaInput.allowed("abc", "a\u0000b"))
    }

    @Test fun spaceAndCaseVariantsOfTheUsersWordsAreAccepted() {
        assertTrue(MikaInput.allowed("去微博帮我找一下有关英雄联盟s16的热点内容", "英雄联盟 S16"))
        assertTrue(MikaInput.allowed("去微博帮我找一下有关英雄联盟s16的热点内容", "s16"))
        assertTrue(MikaInput.allowed("search Formula 1 news", "Formula1"))
    }

    @Test fun clearingAFieldIsAlwaysAllowed() {
        assertTrue(MikaInput.allowed("whatever", ""))
    }

    @Test fun capIsInclusiveAtTenThousandCharacters() {
        val text = "字".repeat(10000)
        assertTrue(MikaInput.allowed(text + "任务", text))
    }
}
