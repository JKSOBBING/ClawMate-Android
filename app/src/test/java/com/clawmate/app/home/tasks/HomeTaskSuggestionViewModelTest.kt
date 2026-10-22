package com.clawmate.app.home.tasks

import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeTaskSuggestionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val config = ConsolidationModelConfig("https://example.invalid", "fixture-key", "fixture-model")
    private val context = HomeTaskSuggestions.context(listOf(IndexedEvent(summary = "真实测试依据")), emptyList(), emptyList(), "2026-10-23")
    private val tasks = (1..3).map { HomeSuggestedTask("id-$it", "建议$it", "查询资讯$it", "测试依据", "news",
        context.evidence.values.toList(), false, "", "") }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun `construction and missing configuration perform no context or model work`() = runTest {
        val vm = HomeTaskSuggestionViewModel({ null }, { _, _ -> error("must not read") }, { _, _ -> error("must not generate") })
        advanceUntilIdle()
        assertTrue(vm.state.value.tasks.isEmpty())
        vm.generate(emptyList())
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.error!!.contains("填写并保存"))
    }

    @Test fun `no real context produces an actionable empty state with no model request`() = runTest {
        val vm = HomeTaskSuggestionViewModel({ config }, { _, _ -> HomeTaskSuggestions.context(emptyList(), emptyList(), emptyList(), "2026-10-23") },
            { _, _ -> error("must not send empty data") })
        vm.generate(emptyList())
        advanceUntilIdle()
        assertTrue(vm.state.value.tasks.isEmpty())
        assertTrue(vm.state.value.error!!.contains("刷新记忆索引"))
    }

    @Test fun `failed refresh preserves a prior complete batch and excludes its tasks from context`() = runTest {
        var calls = 0
        var previous = emptyList<String>()
        val vm = HomeTaskSuggestionViewModel({ config }, { _, old -> previous = old; context }, { _, _ ->
            calls++
            if (calls == 2) error("private provider content")
            tasks
        })
        vm.generate(emptyList())
        advanceUntilIdle()
        assertEquals(tasks, vm.state.value.tasks)
        vm.generate(emptyList())
        advanceUntilIdle()
        assertEquals(tasks.map { it.task }, previous)
        assertEquals(tasks, vm.state.value.tasks)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.error!!.contains("private provider"))
    }

    @Test fun `incomplete generated batches never replace a successful batch`() = runTest {
        val vm = HomeTaskSuggestionViewModel({ config }, { _, _ -> context }, { _, _ -> tasks.take(2) })
        vm.generate(emptyList())
        advanceUntilIdle()
        assertTrue(vm.state.value.tasks.isEmpty())
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.error)
    }

    @Test fun `duplicate taps are ignored and cancellation preserves previous suggestions`() = runTest {
        var calls = 0
        val vm = HomeTaskSuggestionViewModel({ config }, { _, _ -> context }, { _, _ ->
            calls++
            if (calls == 1) tasks else awaitCancellation()
        })
        vm.generate(emptyList())
        advanceUntilIdle()
        vm.generate(emptyList())
        vm.generate(emptyList())
        runCurrent()
        assertTrue(vm.state.value.loading)
        vm.cancel()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertFalse(vm.state.value.loading)
        assertEquals(tasks, vm.state.value.tasks)
        assertTrue(vm.state.value.error!!.contains("已取消"))
    }

    @Test fun `detail selection resolves only a task in the current generated batch`() = runTest {
        val vm = HomeTaskSuggestionViewModel({ config }, { _, _ -> context }, { _, _ -> tasks })
        vm.generate(emptyList())
        advanceUntilIdle()
        vm.select("unknown")
        assertNull(vm.state.value.selected)
        vm.select("id-2")
        assertEquals(tasks[1], vm.state.value.selected)
        vm.closeDetail()
        assertNull(vm.state.value.selected)
    }
}
