package com.clawmate.platform.gui

import com.clawmate.platform.mika.MikaRouteStore
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.SystemActionResult
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.Verification
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZonedDateTime

class MikaPhoneRunTest {
    @org.junit.Test fun checksNavigationOnlyForExplicitTapSequences() {
        org.junit.Assert.assertEquals(2, navigationTapCount("点击列表，点击全部活动即可"))
        org.junit.Assert.assertEquals(2, navigationTapCount("Tap sections then click all events."))
        org.junit.Assert.assertNull(navigationTapCount("点击列表，然后读取第一项并汇总"))
        org.junit.Assert.assertNull(navigationTapCount("读取最新活动"))
    }

    private fun evidenceReviewer(quote: String, verdict: String = "pass") = object : ChatModel {
        override val capabilities = Capabilities(false, false, 16_000, false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            require(tools.isEmpty())
            val material = messages.flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString { it.text }
            require(quote in material)
            val check = if (verdict == "pass") "met" else "unmet"
            val json = """{"verdict":"$verdict","reason":"fixture evidence","checks":[{"criterion":"read","status":"$check","quote":"$quote"}]}"""
            return ModelReply(listOf(Part.Text(json)), StopReason.EndTurn, null)
        }
    }

    @Test fun describedCoordinatesAreResolvedAgainstTheNamedTabBeforeDispatch() = runTest {
        val taps = mutableListOf<Pair<Int, Int>>()
        val screen = object : FixtureScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                val tabs = listOf(
                    UiElement("@e1", "Button", "Video", 300, 965, listOf(200,936,400,995), null, true,false,false),
                    UiElement("@e2", "Button", "Discovery", 500, 965, listOf(400,936,600,995), null, true,false,false),
                )
                return ScreenSnapshot("p1", "example.fixture", tabs, 1000, 1000)
            }
            override suspend fun peek() = snapshot()
            override suspend fun screenshotPng() = byteArrayOf(1)
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                taps += x to y
                return ActionResult.Ok(changed = true)
            }
        }
        val ops = object : com.wangzan101.mika.core.vision.ImageOps {
            override fun size(img: ByteArray) = 1000 to 1000
            override fun aHash(img: ByteArray) = 0L
            override fun toJpeg(img: ByteArray, maxSide: Int, quality: Int) = img
            override fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int, strokePx: Int) = img
            override fun crop(img: ByteArray, box: IntArray) = img
            override fun scale(img: ByteArray, factor: Double) = img
            override fun pixels(img: ByteArray) = IntArray(1) { -1 }
            override fun isMostlyBlack(img: ByteArray) = false
        }
        val vision = object : ChatModel {
            override val capabilities = Capabilities(false, true, 16_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("Discovery | tab | (318,975)")), StopReason.EndTurn, null)
        }
        var turn = 0
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 16_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val call = when (++turn) {
                    1 -> Part.ToolCall("observe", "phone_observe", "{}")
                    2 -> Part.ToolCall("describe", "phone_describe", """{"task":"Find Discovery","reason":"Verify the tab location"}""")
                    3 -> Part.ToolCall("tap", "phone_tap", """{"x":318,"y":975,"target":"Discovery"}""")
                    else -> return ModelReply(listOf(Part.Text("Done")), StopReason.EndTurn, null)
                }
                return ModelReply(listOf(call), StopReason.ToolUse, null)
            }
        }
        MikaPhoneRun("Open Discovery in Fixture", model,
            vision = com.wangzan101.mika.core.vision.VisionService(vision, ops)).execute(screen) {}
        assertEquals(listOf(500 to 966), taps) // Mika rounds the bounds midpoint (965.5).
    }

    @Test fun cancelledRunStillClosesItsStoredRecord() = runTest {
        val directory = kotlin.io.path.createTempDirectory("mika-cancel").toFile()
        try {
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val model = object : ChatModel {
                override val capabilities = Capabilities(true, false, 16_000, false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    entered.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
            }
            val run = MikaPhoneRun("Read Fixture", model, runsRoot = directory)
            val job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch { run.execute(FixtureScreen()) {} }
            entered.await()
            job.cancel()
            job.join()
            val summary = com.clawmate.platform.mika.MikaRunStore(directory).list().single()
            assertEquals("cancelled", summary.outcome)
            assertTrue(summary.endedAtMs != null)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun explicitAppLaunchFindsInstalledLabelWithoutMatchingDiscussionOrPrefixes() {
        val apps = listOf("com.xingin.xhs" to "小红书", "fixture.notes" to "小红", "fixture.clock" to "Calculator")
        assertEquals("com.xingin.xhs", MikaAppRequest.target("打开小红书，总结一下近期热点", apps))
        assertEquals("fixture.clock", MikaAppRequest.target("Please open the system Calculator app.", apps))
        assertEquals(null, MikaAppRequest.target("怎么打开小红书？", apps))
        assertEquals(null, MikaAppRequest.target("打开小红书助手", apps))
        assertEquals(null, MikaAppRequest.target("打开小红书", apps + ("fixture.other" to "小红书")))
    }

    @Test fun upstreamAgentReadsTheApprovedPortAndReturnsItsAnswer() = runTest {
        val screen = FixtureScreen()
        var calls = 0
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 16_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (++calls == 1) {
                    assertTrue(tools.any { it.name == "phone_observe" })
                    return ModelReply(listOf(Part.ToolCall("observe", "phone_observe", "{}")), StopReason.ToolUse, null)
                }
                assertTrue(messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
                    .any { it.callId == "observe" && "Fixture title" in it.content })
                return ModelReply(listOf(Part.Text("Fixture title")), StopReason.EndTurn, null)
            }
        }
        val run = MikaPhoneRun("Read the title in Fixture", model, reviewModel = evidenceReviewer("Fixture title"))
        run.execute(screen) { }
        assertEquals(MikaPhoneResult("success", "Fixture title"), run.result.await())
        assertTrue(screen.reads > 0)
    }

    @Test fun unsupportedReadingIsReturnedAsUnverifiedAfterReview() = runTest {
        var first = true
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 16_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (first) {
                    first = false
                    return ModelReply(listOf(Part.ToolCall("look", "phone_observe", "{}")), StopReason.ToolUse, null)
                }
                return ModelReply(listOf(Part.Text("The current timeout is 1 minute.")), StopReason.EndTurn, null)
            }
        }
        val run = MikaPhoneRun("Read the current timeout", model, reviewModel = evidenceReviewer("Fixture title", "fail"))
        run.execute(FixtureScreen()) {}
        assertEquals("unverified", run.result.await().status)
    }

    @Test fun upstreamAlarmToolUsesTheSystemPortAndKeepsUiToolsOut() = runTest {
        val actions = mutableListOf<SystemAction>()
        val screen = object : FixtureScreen() {
            override suspend fun systemAction(action: SystemAction): SystemActionResult {
                actions.add(action)
                return SystemActionResult(ActionResult.Ok(), handledBy = "example.clock")
            }
        }
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 16_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val reply = messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
                if (reply.isEmpty()) {
                    assertTrue(tools.any { it.name == "phone_set_alarm" })
                    assertTrue(tools.none { it.name in setOf("phone_tap", "phone_observe", "phone_system_command") })
                    val call = Part.ToolCall("alarm", "phone_set_alarm", """{"hour":10,"minute":0}""")
                    return ModelReply(listOf(call), StopReason.ToolUse, null)
                }
                assertTrue(reply.any { it.callId == "alarm" && "example.clock" in it.content })
                return ModelReply(listOf(Part.Text("Clock received the request")), StopReason.EndTurn, null)
            }
        }
        val run = MikaPhoneRun("Set an alarm at 10 AM", model, MikaPhoneMode.SYSTEM)
        run.execute(screen) {}
        assertEquals(listOf(SystemAction.SetAlarm(10, 0)), actions)
        assertEquals("success", run.result.await().status)
    }

    @Test fun upstreamAgentDispatchesTapTypeAndSubmitAndReceivesTheResult() = runTest {
        val actions = mutableListOf<String>()
        val screen = object : FixtureScreen() {
            private var page = 0

            override suspend fun snapshot(): ScreenSnapshot {
                val name = listOf("Search tab", "Search field", "Fixture result for S16")[page]
                val element = UiElement("@e1", if (page == 1) "EditText" else "TextView", name,
                    25, 25, listOf(100, 100, 800, 300), null, page == 0, page == 1, false,
                    focused = page == 1, searchField = page == 1)
                return ScreenSnapshot("p$page", "example.fixture", listOf(element), 100, 100)
            }

            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                assertEquals("@e1", ref)
                assertEquals("p0", snapshotId)
                actions.add("tap")
                page = 1
                return ActionResult.Ok(changed = true)
            }

            override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
                assertEquals(1, page)
                assertEquals("S16", text)
                actions.add("type")
                return ActionResult.Ok(changed = true, verification = Verification.MATCH)
            }

            override suspend fun pressKey(keyCode: Int): ActionResult {
                assertEquals(66, keyCode)
                assertEquals(listOf("tap", "type"), actions)
                actions.add("enter")
                page = 2
                return ActionResult.Ok(changed = true)
            }
        }
        var turn = 0
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 16_000, false)

            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                assertTrue(tools.map { it.name }.containsAll(listOf("phone_observe", "phone_tap", "phone_type_text")))
                val call = when (++turn) {
                    1 -> Part.ToolCall("observe", "phone_observe", "{}")
                    2 -> Part.ToolCall("tap", "phone_tap", """{"element":"Search tab"}""")
                    3 -> Part.ToolCall("type", "phone_type_text", """{"text":"S16","pressEnter":true}""")
                    4 -> Part.ToolCall("result", "phone_observe", "{}")
                    else -> {
                        assertTrue(messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
                            .any { it.callId == "result" && "Fixture result for S16" in it.content })
                        return ModelReply(listOf(Part.Text("Fixture result for S16")), StopReason.EndTurn, null)
                    }
                }
                return ModelReply(listOf(call), StopReason.ToolUse, null)
            }
        }
        val run = MikaPhoneRun("Search for S16 in Fixture", model, reviewModel = evidenceReviewer("Fixture result for S16"))
        run.execute(screen) {}

        assertEquals(listOf("tap", "type", "enter"), actions)
        assertEquals("success", run.result.await().status)
        for (tool in listOf("phone_tap", "phone_type_text")) {
            assertTrue(run.toolTrace().any { it.tool == tool && it.phase == "model" })
            assertTrue(run.toolTrace().any { it.tool == tool && it.phase == "result" && it.ok == true })
        }
    }

    @Test fun nextAlarmRespectsPassedTimesAndRepeatDays() {
        val now = ZonedDateTime.parse("2026-11-16T15:00:00+08:00[Asia/Shanghai]")
        val once = MikaSystemScreen.nextOccurrence(SystemAction.SetAlarm(10, 0), now)
        assertEquals(now.plusDays(1).withHour(10).toInstant().toEpochMilli(), once)
        val monday = MikaSystemScreen.nextOccurrence(SystemAction.SetAlarm(10, 0, days = listOf(2)), now)
        assertEquals(now.plusWeeks(1).withHour(10).toInstant().toEpochMilli(), monday)
    }

    @Test fun upstreamCalendarToolValidatesTheBatchBeforeWritingIt() = runTest {
        val batches = mutableListOf<List<SystemAction.AddCalendarEvent>>()
        val screen = object : FixtureScreen() {
            override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult {
                batches.add(events)
                return SystemActionResult(ActionResult.Ok(), eventUris = listOf("content://com.android.calendar/events/20"))
            }
        }
        var turn = 0
        val model = object : ChatModel {
            override val capabilities = Capabilities(true, false, 16_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val tool = "phone_calendar_bulk_insert"
                assertTrue(tools.any { it.name == tool })
                assertTrue(tools.none { it.name in setOf("phone_add_calendar_event", "phone_set_alarm", "phone_observe") })
                return when (++turn) {
                    1 -> ModelReply(listOf(Part.ToolCall("invalid", tool,
                        """{"events":[{"title":"First","start":"2026-11-18","allDay":true},{"title":"Broken","start":"invalid"}]}""")),
                        StopReason.ToolUse, null)
                    2 -> {
                        assertTrue(batches.isEmpty())
                        ModelReply(listOf(Part.ToolCall("valid", tool,
                            """{"events":[{"title":"First","start":"2026-11-18","allDay":true}]}""")), StopReason.ToolUse, null)
                    }
                    else -> {
                        val receipt = messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
                        assertTrue(receipt.any { it.callId == "valid" && "eventIds" in it.content && "20" in it.content })
                        ModelReply(listOf(Part.Text("Calendar accepted the batch")), StopReason.EndTurn, null)
                    }
                }
            }
        }
        val run = MikaPhoneRun("Write the calendar events", model, MikaPhoneMode.CALENDAR_BATCH)
        run.execute(screen) {}
        val event = batches.single().single()
        assertEquals(Instant.parse("2026-11-18T00:00:00Z").toEpochMilli(), event.beginMs)
        assertEquals(Instant.parse("2026-11-19T00:00:00Z").toEpochMilli(), event.endMs)
        assertEquals("success", run.result.await().status)
    }

    @get:Rule val temp = TemporaryFolder()

    private suspend fun runReplay(
        recordedButton: String = "Compute",
        answer: (List<String>) -> Part,
    ): Pair<TaskState, Int> {
        val goal = "Tap Compute and read the result"
        var taps = 0
        val screen = object : FixtureScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                val button = UiElement("@e1", "Button", "Compute", 25, 25, listOf(10, 10, 40, 40), null, true, false, false)
                val result = button.copy(ref = "@e2", role = "TextView", name = if (taps > 0) "731" else "0", clickable = false)
                return ScreenSnapshot("p1", "example.fixture", listOf(button, result), 100, 100)
            }

            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                assertEquals("@e1", ref)
                taps++
                return ActionResult.Ok(changed = true)
            }
        }
        val anchor = Anchors("example.fixture", emptyList())
        val target = Selector("Button", recordedButton, null, emptyList(), 0, true)
        val store = MikaRouteStore(temp.newFolder())
        store.put(Route("saved", goal, 0, listOf("example.fixture"), emptyMap(),
            listOf(RouteStep("phone_tap", "{}", target, anchor, anchor)), lastUsedMs = 0))
        fun model(reply: (List<ToolSpec>) -> Part) = object : ChatModel {
            override val capabilities = Capabilities(true, false, 16_000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val part = reply(tools)
                return ModelReply(listOf(part), if (part is Part.ToolCall) StopReason.ToolUse else StopReason.EndTurn, null)
            }
        }
        val matcher = RouteMatcher(model { Part.Text("""{"route":1,"slots":[]}""") }, store, null)
        val main = model { offered -> answer(offered.map { it.name }) }
        val state = AgentLoop(main, toolDispatcher(screen), "BASE", maxSteps = 4, routes = matcher,
            flags = SpeedFlags(replay = true, batchSteps = true)).run(goal)
        return state to taps
    }

    @Test fun completedRouteOnlyOffersCloseoutAndKeepsTheResult() = runTest {
        val (state, taps) = runReplay { offered ->
            assertEquals(listOf("end_not_done"), offered)
            Part.Text("731")
        }
        assertTrue(state is TaskState.Succeeded)
        assertEquals("731", (state as TaskState.Succeeded).answer)
        assertEquals(1, taps)
    }

    @Test fun completedRouteRejectsEvenAnUnsolicitedWrite() = runTest {
        val (state, taps) = runReplay {
            Part.ToolCall("extra", "phone_tap", """{"ref":"@e1","snapshotId":"p1"}""")
        }
        assertTrue(state is TaskState.Failed)
        assertEquals(1, taps)
    }

    @Test fun divergedRouteAllowsTheModelToFinishTheAction() = runTest {
        var turns = 0
        val (state, taps) = runReplay(recordedButton = "Old label") { offered ->
            assertTrue("phone_tap" in offered && "do_steps" in offered)
            if (++turns == 1) Part.ToolCall("continue", "phone_tap", """{"ref":"@e1","snapshotId":"p1"}""")
            else Part.Text("731")
        }
        assertTrue(state is TaskState.Succeeded)
        assertEquals(1, taps)
    }

    private open class FixtureScreen : ScreenPort {
        var reads = 0
        override suspend fun snapshot(): ScreenSnapshot {
            reads++
            val title = UiElement("@e1", "TextView", "Fixture title", 25, 25,
                listOf(10, 10, 90, 90), null, false, false, false)
            return ScreenSnapshot("p1", "example.fixture", listOf(title), 100, 100)
        }
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Failed("disabled")
        override suspend fun tapXY(x: Int, y: Int): ActionResult = ActionResult.Failed("disabled")
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = ActionResult.Failed("disabled")
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Failed("disabled")
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Failed("disabled")
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Failed("disabled")
        override suspend fun back() = ActionResult.Failed("disabled")
        override suspend fun pressKey(keyCode: Int): ActionResult = ActionResult.Failed("disabled")
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("disabled")
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = listOf("example.fixture" to "Fixture")
    }
}
