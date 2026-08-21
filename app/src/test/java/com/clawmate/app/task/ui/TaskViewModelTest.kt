package com.clawmate.app.task.ui

import com.clawmate.app.task.data.WorkflowRepository
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.WorkflowRunResult
import com.clawmate.app.task.runner.WorkflowRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        private var current = initial

        override suspend fun tasks() = current
        override suspend fun configJson(task: WorkflowTask) = "{\"steps\":[]}"
        override suspend fun saveConfigJson(task: WorkflowTask, newJson: String) = Unit
        override suspend fun setTaskEnabled(taskId: String, enabled: Boolean) {
            current = current.map { if (it.id == taskId) it.copy(enabled = enabled) else it }
        }
        override suspend fun recordRun(taskId: String, result: WorkflowRunResult) {
            recorded += taskId
        }
    }
}
