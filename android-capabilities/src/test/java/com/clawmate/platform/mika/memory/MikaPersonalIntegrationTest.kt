package com.clawmate.platform.mika.memory

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.memory.*
import com.wangzan101.mika.core.skills.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class MikaPersonalIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun runtime() = MikaMemoryRuntime(temp.root, AesGcmKeyCipher { key })
    private fun id(s: String) = Json.parseToJsonElement(s).jsonObject.getValue("id").jsonPrimitive.content
    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { put(it.first, it.second) } }.toString()
    private fun ok(s: String) = Json.parseToJsonElement(s).jsonObject["ok"]?.jsonPrimitive?.boolean == true

    @Test fun profileToolUsesUserWordsAndOnlyNextRunSnapshotChanges() = runTest {
        val r = runtime(); val sessions = MikaMemorySessions(r)
        val before = r.begin("介绍我", "before")
        val goal = "把我是软件工程师加入我的用户画像"
        val turn = id(sessions.begin(goal, "a"))
        assertTrue(ok(sessions.call(turn, "profile_edit", args("op" to "add", "text" to "用户是软件工程师", "quote" to goal))))
        assertTrue(before.profileSnapshot.isEmpty())
        assertTrue(runtime().begin("介绍我", "b").context()!!.contains("软件工程师"))
        assertFalse(temp.root.resolve("memory/profile.bin").readText().contains("软件工程师"))
        assertFalse(ok(sessions.call(turn, "profile_edit", args("op" to "add", "text" to "用户住在123号", "quote" to goal))))
    }
    @Test fun previousConfirmationUsesOnlyOriginalAdjacentUserWords() = runTest {
        val r = runtime(); val sessions = MikaMemorySessions(r)
        val prior = listOf(Turn("我该怎么称呼你", "可以叫我助手，要记住吗？", "succeeded"),
            Turn("好的，记住以后叫你小助手", "好", "succeeded"))
        val request = args("kind" to "preference", "content" to "用户以后叫助手小助手", "quote" to "好的，记住以后叫你小助手")
        assertTrue(ok(sessions.call(id(sessions.begin("现在保存上一轮的称呼偏好", "same", prior)), "memory_save", request)))
        assertFalse(ok(sessions.call(id(sessions.begin("现在保存上一轮的称呼偏好", "other")), "memory_save", request)))
        val no = prior.dropLast(1) + Turn("好的，但是不要记住", "好", "succeeded")
        assertFalse(r.begin("保存", "a", no).utterances.containsMemoryQuote("好的，但是不要记住"))
        assertFalse(r.begin("保存", "a", prior).utterances.containsMemoryQuote("可以叫我助手"))
    }
    @Test fun hostHistoryExcludesAttachmentsAndUnpairedMessages() {
        val raw = """{"messages":[{"role":"user","content":"你好","kind":"chat"},{"role":"assistant","content":"建议简短回答？","kind":"chat"},{"role":"user","content":"好的，记住简短回答","kind":"chat"},{"role":"assistant","content":"好的","kind":"chat"},{"role":"user","content":"当前未完成"}]}"""
        val prior = MikaConversationHistory.decode(raw)
        assertEquals(2, prior.size)
        assertEquals("好的，记住简短回答", prior.last().userText)
        assertEquals("unverified", prior.last().status)
        assertTrue(MikaConversationHistory.decode("""{"messages":[{"role":"user","content":"图里的人","imagePath":"private.png"},{"role":"assistant","content":"不能作原话"}]}""").isEmpty())
    }
    @Test fun approvedDraftEntersCatalogAndClearRemovesProfileAndSkills() = runTest {
        val r = runtime()
        val md = """---
name: calendar-review
display: 日程复核
description: 核对日程再汇总
aliases: [日程复核]
---
## 入口
打开日历。
## 步骤
读取日程再汇总。
## 容易出错的地方
确认日期。
## 怎样算办成
结果与屏幕一致。
## 什么时候停下交给用户
需要登录时停下。
"""
        r.skills.putPending("draft-1", PersonalSkill("calendar-review", md, SkillStatus.pending, "run-test", null, 1, emptyList()))
        assertFalse(r.catalog.items.any { it.name == "calendar-review" })
        assertNotNull(r.skills.approve("draft-1"))
        assertTrue(r.catalog.items.any { it.name == "calendar-review" })
        r.profile.add("用户喜欢复核日程")
        r.change { r.clear() }
        assertTrue(runtime().profile.snapshot().isEmpty())
        assertTrue(runtime().skills.list().isEmpty())
    }
}
