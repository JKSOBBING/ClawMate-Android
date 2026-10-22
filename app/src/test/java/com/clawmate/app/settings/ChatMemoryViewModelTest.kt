package com.clawmate.app.settings

import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.datamanager.chatmemory.*
import com.clawmate.app.settings.ui.ChatMemoryViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMemoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val config = ConsolidationModelConfig("https://example.invalid/v1", "fixture-key", "fixture-model")
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun `construction opening and cancelling consent never read chats or call models`() = runTest {
        var calls = 0
        val vm = ChatMemoryViewModel({ config }, { _, _ -> calls++; error("must not run") }, { error("must not index") })
        advanceUntilIdle()
        assertEquals(0, calls)
        vm.requestSync()
        assertEquals("example.invalid", vm.state.value.consent!!.service)
        assertEquals("fixture-model", vm.state.value.consent!!.model)
        assertFalse(vm.state.value.toString().contains("fixture-key"))
        vm.dismissConsent()
        vm.confirmSync()
        advanceUntilIdle()
        assertEquals(0, calls)
        assertNull(vm.state.value.consent)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `missing config gives actionable error with zero processing`() = runTest {
        val vm = ChatMemoryViewModel({ null }, { _, _ -> error("must not read") }, { error("must not index") })
        vm.requestSync()
        vm.confirmSync()
        advanceUntilIdle()
        assertNull(vm.state.value.consent)
        assertTrue(vm.state.value.error!!.contains("填写并保存"))
        assertFalse(vm.state.value.busy)
    }

    @Test fun `changed destination requires fresh consent before reading anything`() = runTest {
        var current = config
        val vm = ChatMemoryViewModel({ current }, { _, _ -> error("must not run") }, { error("must not index") })
        vm.requestSync()
        current = config.copy(baseUrl = "https://changed.invalid")
        vm.confirmSync()
        advanceUntilIdle()
        assertTrue(vm.state.value.error!!.contains("已改变"))
        assertFalse(vm.state.value.busy)
        vm.requestSync()
        assertEquals("changed.invalid", vm.state.value.consent!!.service)
        vm.dismissConsent()
    }

    @Test fun `confirmed sync runs once and reports only returned counts`() = runTest {
        var calls = 0
        var indexCalls = 0
        val vm = ChatMemoryViewModel({ config }, { saved, progress ->
            assertEquals(config, saved)
            calls++
            progress(ChatMemoryProgress(2, 2, false))
            ChatMemorySyncSummary(2, 3, 1, 3)
        }, { indexCalls++; 3 })
        vm.requestSync()
        vm.confirmSync()
        vm.confirmSync()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(0, indexCalls)
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.notice!!.contains("新增 3 条事实"))
        assertTrue(vm.state.value.notice!!.contains("请刷新"))
        vm.refreshIndex()
        advanceUntilIdle()
        assertEquals(1, indexCalls)
        assertTrue(vm.state.value.notice!!.contains("3 条事件"))
    }

    @Test fun `failed extraction never reports invented success and releases busy state`() = runTest {
        val vm = ChatMemoryViewModel({ config }, { _, _ -> throw ChatMemoryResponseException("fixture private raw response") }, { 0 })
        vm.requestSync()
        vm.confirmSync()
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.notice)
        assertFalse(vm.state.value.error!!.contains("fixture private"))
        vm.refreshIndex()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.notice!!.contains("未找到可索引事件"))
    }

    @Test fun `cancel stops pending sync and permits a later action`() = runTest {
        val vm = ChatMemoryViewModel({ config }, { _, progress ->
            progress(ChatMemoryProgress(1, 2, false))
            awaitCancellation()
        }, { 1 })
        vm.requestSync()
        vm.confirmSync()
        runCurrent()
        assertTrue(vm.state.value.syncing)
        assertEquals(1, vm.state.value.progress!!.processedParts)
        vm.refreshIndex() // Busy guard must not start overlapping work.
        assertFalse(vm.state.value.indexing)
        vm.cancelSync()
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.notice!!.contains("已取消"))
        vm.refreshIndex()
        advanceUntilIdle()
        assertTrue(vm.state.value.notice!!.contains("1 条事件"))
    }

    @Test fun `local index refresh requires neither cloud config nor chat access`() = runTest {
        val vm = ChatMemoryViewModel({ error("must not read config") }, { _, _ -> error("must not read chat") }, { 5 })
        vm.refreshIndex()
        advanceUntilIdle()
        assertTrue(vm.state.value.notice!!.contains("5 条事件"))
        assertFalse(vm.state.value.busy)
    }

    @Test fun `storage corruption and index failure remain visible without resetting receipts`() = runTest {
        val vm = ChatMemoryViewModel({ config }, { _, _ -> throw IOException("聊天同步记录无法读取；保留原文件") },
            { throw IOException("fixture secret path") })
        vm.requestSync()
        vm.confirmSync()
        advanceUntilIdle()
        assertTrue(vm.state.value.error!!.contains("避免重复导入"))
        vm.refreshIndex()
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.error!!.contains("索引刷新失败"))
        assertFalse(vm.state.value.error!!.contains("fixture secret"))
    }
}
