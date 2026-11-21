package com.clawmate.platform.mika.memory

import com.wangzan101.mika.core.agent.*
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.skills.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MikaSkillLearningTest {
    private val md = """---
name: settings-audit
display: 设置复核
description: 在设置中检索并核对选项
aliases: [设置复核]
---
## 入口
打开设置。
## 步骤
搜索目标选项，读取当前值，然后返回。
## 容易出错的地方
不要误改当前值。
## 怎样算办成
读取到目标设置并报告。
## 什么时候停下交给用户
需要额外权限时停止。
"""
    private val tools = knownTools()
    private fun events() = (1..5).map { RunEvent.ToolResult(it.toLong(), it, requireNotNull(tools.nameOf(com.wangzan101.mika.core.tool.Verb.Tap)), """{"ok":true}""", true, null) } +
        RunEvent.TaskEnded(6, "succeeded", "已核对")
    private fun aftermath(outcome: RunOutcome) = RunAftermath("conv", "run-test", "核对设置", TaskState.Succeeded("已核对"),
        emptyList(), emptyList(), emptyList(), tools.names, tools, { events() }, outcome = outcome)
    @Test fun verifiedMultiStepRunCreatesPendingDraftAndRequiresApproval() = runTest {
        val model = TextModel(md); val files = MapSkills(); val catalog = GuideCatalog.loadBundled()
        val notes = RunLearning(MemorySwitches(), null, files, catalog, null, SkillDrafter(model), { "draft-test" }).learn(aftermath(RunOutcome.Done))
        assertEquals(1, model.requests.size)
        assertEquals(1, files.pending().size)
        assertTrue(files.list().isEmpty())
        assertEquals("draft-test", notes.skillNotes.single().draftId)
    }
    @Test fun partialAndDisabledLearningNeverDraft() = runTest {
        val model = TextModel(md); val files = MapSkills(); val catalog = GuideCatalog.loadBundled()
        RunLearning(MemorySwitches(), null, files, catalog, null, SkillDrafter(model), { "d" }).learn(aftermath(RunOutcome.Partial))
        RunLearning(MemorySwitches(learnSkills = false), null, files, catalog, null, SkillDrafter(model), { "d" }).learn(aftermath(RunOutcome.Done))
        assertTrue(model.requests.isEmpty()); assertTrue(files.pending().isEmpty())
    }
}
