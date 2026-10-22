package com.clawmate.app.home.comfort

import com.clawmate.app.datamanager.ConsolidationModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeComfortViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val config = ConsolidationModelConfig("https://example.invalid", "fixture-key", "fixture-model")
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun `opening is network free and missing configuration preserves the draft`() = runTest {
        val vm = HomeComfortViewModel({ null }, { _, _ -> error("no network") })
        vm.open()
        vm.input("我想聊聊")
        vm.send()
        advanceUntilIdle()
        assertEquals("我想聊聊", vm.state.value.input)
        assertTrue(vm.state.value.messages.isEmpty())
        assertTrue(vm.state.value.error!!.contains("填写并保存"))
        vm.close()
        vm.send()
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test fun `successful send appends only the user and actual returned reply`() = runTest {
        var calls = 0
        val vm = HomeComfortViewModel({ config }, { _, messages ->
            calls++; assertEquals("今天有点累", messages.last().content); HomeComfortReply("测试模型实际返回", false)
        })
        vm.open()
        assertTrue(vm.state.value.messages.isEmpty())
        vm.input("今天有点累")
        vm.send()
        vm.send()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(listOf(HomeComfortRole.USER, HomeComfortRole.ASSISTANT), vm.state.value.messages.map { it.role })
        assertEquals("测试模型实际返回", vm.state.value.messages.last().content)
        assertFalse(vm.state.value.sending)
    }

    @Test fun `failure and retry preserve one user turn and any unsent draft`() = runTest {
        var calls = 0
        val vm = HomeComfortViewModel({ config }, { _, messages ->
            calls++
            assertEquals(1, messages.size)
            if (calls == 1) error("private server error") else HomeComfortReply("重试实际回复", false)
        })
        vm.open(); vm.input("第一条"); vm.send()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.messages.size)
        assertFalse(vm.state.value.error!!.contains("private server"))
        vm.input("还未发送的新想法")
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(2, vm.state.value.messages.size)
        assertEquals("还未发送的新想法", vm.state.value.input)
    }

    @Test fun `close cancels an unfinished reply without fabricating an assistant message`() = runTest {
        val vm = HomeComfortViewModel({ config }, { _, _ -> awaitCancellation() })
        vm.open(); vm.input("正在等待"); vm.send()
        runCurrent()
        assertTrue(vm.state.value.sending)
        vm.close()
        advanceUntilIdle()
        assertFalse(vm.state.value.open)
        assertFalse(vm.state.value.sending)
        assertEquals(1, vm.state.value.messages.size)
        assertTrue(vm.state.value.error!!.contains("已停止"))
        vm.open()
        assertEquals(1, vm.state.value.messages.size)
    }

    @Test fun `restarting prevents an old late reply or cancellation from replacing new chat`() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val vm = HomeComfortViewModel({ config }, { _, _ ->
            calls++
            if (calls == 1) withContext(NonCancellable) { release.await(); HomeComfortReply("old late response", false) }
            else HomeComfortReply("新对话回复", false)
        })
        vm.open(); vm.input("旧对话"); vm.send()
        runCurrent()
        vm.restart()
        vm.input("新对话"); vm.send()
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("新对话", "新对话回复"), vm.state.value.messages.map { it.content })
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.sending)
    }
}
