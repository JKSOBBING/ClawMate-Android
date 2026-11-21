package com.clawmate.platform.mika

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Anchors
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MikaRouteLearningTest {
    @get:Rule val temp = TemporaryFolder()
    private val goal = "打开计算器"
    private fun events() = listOf(
        RunEvent.TaskStarted(0, goal, "fixture"),
        RunEvent.ActionTarget(1, 1, "phone_launch_app", """{"package":"com.calculator"}""", null,
            Anchors(null, emptyList()), Anchors("com.calculator", listOf("计算器")), true, "c1", false),
        RunEvent.ToolResult(2, 1, "phone_launch_app", """{"ok":true}""", true, null, callId = "c1"),
        RunEvent.TaskEnded(3, "succeeded", "已经打开计算器"),
    )
    private fun judge(outcome: String) = object : ChatModel {
        override val capabilities = Capabilities(true, false, 64000, false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
            ModelReply(listOf(Part.Text("""{"outcome":"$outcome"}""")), StopReason.EndTurn, null)
    }
    @Test fun successfulTraceIsPersistedOnlyAfterDoneJudgment() = runTest {
        val dir = temp.newFolder()
        val store = MikaRouteStore(dir)
        MikaRouteLearning.learn(goal, TaskState.Succeeded("已打开"), events(), store, judge("done"), knownTools())
        assertEquals(1, MikaRouteStore(dir).count())
        assertEquals("com.calculator", store.all().single().packages.single())
    }
    @Test fun ambiguousDispatchMustNotBecomeAShortenedRoute() = runTest {
        val store = MikaRouteStore(temp.newFolder())
        val history = events().toMutableList().apply {
            add(lastIndex, RunEvent.ToolResult(2, 2, "phone_tap",
                """{"ok":false,"error":"operation_failed"}""", null, null, callId = "c2"))
        }
        MikaRouteLearning.learn(goal, TaskState.Succeeded("已到达"), history, store, judge("done"), knownTools())
        assertEquals(0, store.count())
    }

    @Test fun completedReplayReportsOutcomeWithoutReplacingTheLearnedRoute() = runTest {
        val store = MikaRouteStore(temp.newFolder())
        val replay = RunEvent.Replay(4, "existing", 1, 1, true, "matched")
        var outcome: com.wangzan101.mika.core.agent.RunOutcome? = null
        MikaRouteLearning.learn(goal, TaskState.Succeeded("已打开"), listOf(replay),
            store, judge("done"), knownTools()) { outcome = it }
        assertEquals(com.wangzan101.mika.core.agent.RunOutcome.Done, outcome)
        assertEquals(0, store.count())
    }

    @Test fun partialAndDoubtfulAnswersDoNotBecomeReusableRoutes() = runTest {
        val store = MikaRouteStore(temp.newFolder())
        MikaRouteLearning.learn(goal, TaskState.Succeeded("只做了部分"), events(), store, judge("partial"), knownTools())
        MikaRouteLearning.learn(goal, TaskState.Succeeded("不确定", verified = false), events(), store, judge("done"), knownTools())
        assertEquals(0, store.count())
    }
}
