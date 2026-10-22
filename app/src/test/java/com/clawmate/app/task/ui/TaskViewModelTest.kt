package com.clawmate.app.task.ui

import com.clawmate.app.task.data.WorkflowRepository
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.WorkflowRunResult
import com.clawmate.app.task.runner.WorkflowRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `batch execution reports completion and persists each result`() = runTest {
        val tasks = listOf(task("one"), task("two"))
        val repository = FakeTaskRepository(tasks)
        val runner = object : WorkflowRunner {
            override suspend fun run(definition: com.clawmate.app.task.model.WorkflowDefinition) =
                WorkflowRunResult(RunStatus.SUCCESS, "ok")
        }
        val vm = TaskViewModel(repository, runner)
        advanceUntilIdle()

        vm.runEnabledTasks()
        advanceUntilIdle()

        assertFalse(vm.state.value.batchRunning)
        assertEquals("批量执行完成：成功 2，失败 0", vm.state.value.batchMessage)
        assertEquals(listOf("one", "two"), repository.recorded)
        assertTrue(vm.state.value.tasks.all { it.lastRunStatus == "成功" })
    }

    private val noRun = object : WorkflowRunner {
        override suspend fun run(definition: com.clawmate.app.task.model.WorkflowDefinition): WorkflowRunResult =
            error("Viewing configuration must not execute it")
    }

    @Test
    fun `config viewer preserves original text without executing or saving`() = runTest {
        val task = task("inspect")
        val repository = FakeTaskRepository(listOf(task))
        val raw = "{\n  \"steps\": [], \"unknown\": true\n}"
        repository.readConfig = { raw }
        val vm = TaskViewModel(repository, noRun)
        vm.openConfig(task)
        advanceUntilIdle()
        assertEquals(raw, vm.config.value!!.text)
        assertFalse(vm.config.value!!.isLoading)
        assertNull(vm.config.value!!.error)
        assertTrue(repository.recorded.isEmpty())
        assertEquals(0, repository.saves)
    }

    @Test
    fun `missing blank and unreadable configurations can be retried`() = runTest {
        val task = task("missing")
        val repository = FakeTaskRepository(listOf(task))
        val vm = TaskViewModel(repository, noRun)
        for (read in listOf<suspend (WorkflowTask) -> String?>(
            { null }, { "  " }, { throw java.io.IOException("private path") },
        )) {
            repository.readConfig = read
            vm.openConfig(task)
            advanceUntilIdle()
            assertTrue(vm.config.value!!.error != null)
            assertFalse(vm.config.value!!.error!!.contains("private path"))
        }
        repository.readConfig = { "{malformed but inspectable}" }
        vm.openConfig(task)
        advanceUntilIdle()
        assertEquals("{malformed but inspectable}", vm.config.value!!.text)
        assertNull(vm.config.value!!.error)
    }

    @Test
    fun `late reads cannot replace a newer task or reopen a closed viewer`() = runTest {
        val first = task("first")
        val second = task("second")
        val oldRead = CompletableDeferred<String>()
        val repository = FakeTaskRepository(listOf(first, second))
        repository.readConfig = { if (it.id == first.id) withContext(NonCancellable) { oldRead.await() } else "second config" }
        val vm = TaskViewModel(repository, noRun)
        vm.openConfig(first)
        advanceUntilIdle()
        vm.closeConfig()
        vm.openConfig(second)
        advanceUntilIdle()
        oldRead.complete("stale config")
        advanceUntilIdle()
        assertEquals(second.id, vm.config.value!!.task.id)
        assertEquals("second config", vm.config.value!!.text)
        vm.closeConfig()
        assertNull(vm.config.value)
    }

    @Test
    fun `large configurations have a bounded read-only preview`() = runTest {
        val task = task("large")
        val repository = FakeTaskRepository(listOf(task))
        repository.readConfig = { "x".repeat(TaskViewModel.MAX_CONFIG_PREVIEW_CHARS + 1) }
        val vm = TaskViewModel(repository, noRun)
        vm.openConfig(task)
        advanceUntilIdle()
        assertTrue(vm.config.value!!.truncated)
        assertEquals(TaskViewModel.MAX_CONFIG_PREVIEW_CHARS, vm.config.value!!.text.length)
        assertEquals(0, repository.saves)
    }

    @Test
    fun `valid save preserves raw unknown fields and refreshes metadata without running`() = runTest {
        val task = task("edit")
        val repository = FakeTaskRepository(listOf(task))
        val vm = TaskViewModel(repository, noRun)
        vm.openConfig(task)
        advanceUntilIdle()
        vm.editConfig()
        val edited = """{ "metadata":{"description":"renamed"}, "steps":[], "future":true }"""
        vm.updateConfigDraft(edited)
        vm.saveConfig()
        vm.saveConfig()
        advanceUntilIdle()
        assertEquals(1, repository.saves)
        assertEquals(edited, repository.savedJson)
        assertEquals(edited, vm.config.value!!.text)
        assertNull(vm.config.value!!.draft)
        assertEquals("renamed", vm.state.value.tasks.single().title)
        assertTrue(vm.state.value.tasks.single().isUserModified)
        assertTrue(repository.recorded.isEmpty())
    }

    @Test
    fun `invalid drafts and failed writes preserve original text and allow retry`() = runTest {
        val task = task("edit")
        val repository = FakeTaskRepository(listOf(task))
        val vm = TaskViewModel(repository, noRun)
        vm.openConfig(task)
        advanceUntilIdle()
        val original = vm.config.value!!.text
        vm.editConfig()
        vm.updateConfigDraft("{steps:[]}")
        vm.saveConfig()
        advanceUntilIdle()
        assertEquals(0, repository.saves)
        assertTrue(vm.config.value!!.editError != null)
        val draft = """{"steps":[],"extra":1}"""
        vm.updateConfigDraft(draft)
        repository.writeConfig = { throw java.io.IOException("private file path") }
        vm.saveConfig()
        advanceUntilIdle()
        assertEquals(original, vm.config.value!!.text)
        assertEquals(draft, vm.config.value!!.draft)
        assertFalse(vm.config.value!!.editError!!.contains("private file path"))
        assertFalse(vm.config.value!!.isSaving)
        repository.writeConfig = {}
        vm.saveConfig()
        advanceUntilIdle()
        assertEquals(draft, vm.config.value!!.text)
    }

    @Test
    fun `dirty cancel and back require explicit discard and can keep editing`() = runTest {
        val task = task("edit")
        val repository = FakeTaskRepository(listOf(task))
        val vm = TaskViewModel(repository, noRun)
        vm.openConfig(task)
        advanceUntilIdle()
        vm.editConfig()
        vm.updateConfigDraft("draft")
        vm.cancelConfigEdit()
        assertTrue(vm.config.value!!.showDiscardDialog)
        vm.keepConfigEditing()
        assertEquals("draft", vm.config.value!!.draft)
        assertFalse(vm.config.value!!.showDiscardDialog)
        vm.closeConfig()
        assertTrue(vm.config.value!!.showDiscardDialog)
        vm.discardConfigEdits()
        assertNull(vm.config.value)
        assertEquals(0, repository.saves)
    }

    @Test
    fun `saving cannot be dismissed or have its draft replaced`() = runTest {
        val task = task("edit")
        val repository = FakeTaskRepository(listOf(task))
        val gate = CompletableDeferred<Unit>()
        repository.writeConfig = { gate.await() }
        val vm = TaskViewModel(repository, noRun)
        vm.openConfig(task)
        advanceUntilIdle()
        vm.editConfig()
        val draft = """{"steps":[],"extra":2}"""
        vm.updateConfigDraft(draft)
        vm.saveConfig()
        advanceUntilIdle()
        vm.closeConfig()
        vm.cancelConfigEdit()
        vm.updateConfigDraft("changed while saving")
        vm.openConfig(task("different"))
        assertTrue(vm.config.value!!.isSaving)
        assertEquals(draft, vm.config.value!!.draft)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.config.value!!.isSaving)
        assertEquals(draft, vm.config.value!!.text)
    }

    @Test
    fun `truncated preview cannot become an editable document`() = runTest {
        val task = task("large")
        val repository = FakeTaskRepository(listOf(task))
        repository.readConfig = { "x".repeat(TaskViewModel.MAX_CONFIG_PREVIEW_CHARS + 1) }
        val vm = TaskViewModel(repository, noRun)
        vm.openConfig(task)
        advanceUntilIdle()
        vm.editConfig()
        vm.updateConfigDraft("""{"steps":[]}""")
        vm.saveConfig()
        advanceUntilIdle()
        assertNull(vm.config.value!!.draft)
        assertEquals(0, repository.saves)
    }

    private fun task(id: String) = WorkflowTask(
        id = id,
        sceneId = 0,
        icon = "Buy",
        iconColor = "#4B8BFF",
        title = id,
        source = "test",
        enabled = true,
        configFileName = "$id.json",
        lastRunStatus = "未运行",
        lastRun = "尚未执行",
        lastMessage = "",
    )

    private class FakeTaskRepository(private val initial: List<WorkflowTask>) : WorkflowRepository {
        var recorded = mutableListOf<String>()
        var saves = 0
        var savedJson: String? = null
        var writeConfig: suspend (String) -> Unit = {}
        var readConfig: suspend (WorkflowTask) -> String? = { "{\"steps\":[]}" }
        private var current = initial

        override suspend fun tasks() = current
        override suspend fun configJson(task: WorkflowTask) = readConfig(task)
        override suspend fun saveConfigJson(task: WorkflowTask, newJson: String) {
            saves++
            writeConfig(newJson)
            savedJson = newJson
        }
        override suspend fun setTaskEnabled(taskId: String, enabled: Boolean) {
            current = current.map { if (it.id == taskId) it.copy(enabled = enabled) else it }
        }
        override suspend fun recordRun(taskId: String, result: WorkflowRunResult) {
            recorded += taskId
        }
    }
}
