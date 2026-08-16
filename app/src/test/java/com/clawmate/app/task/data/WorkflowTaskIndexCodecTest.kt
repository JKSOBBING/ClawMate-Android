package com.clawmate.app.task.data

import com.clawmate.app.task.model.WorkflowTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the deterministic task-index persistence mapping (encode/decode). */
class WorkflowTaskIndexCodecTest {

    private fun task(id: String = "t1", enabled: Boolean = true) = WorkflowTask(
        id = id,
        sceneId = 3,
        icon = "Chat",
        iconColor = "#FF9347",
        title = "收集聊天记录",
        source = "basic-gui-task",
        enabled = enabled,
        configFileName = "default_weixin_chat.json",
        lastRunStatus = "未运行",
        lastRun = "尚未执行",
        lastMessage = "点击运行后开始执行 workflow",
        isUserModified = true,
    )

    @Test
    fun `encode decode round-trips task fields`() {
        val original = listOf(task("t1", enabled = true), task("t2", enabled = false))
        val decoded = WorkflowTaskIndexCodec.decode(WorkflowTaskIndexCodec.encode(original))
        assertTrue(decoded != null)
        val decodedList = requireNotNull(decoded)
        assertEquals(2, decodedList.size)
        assertEquals("t1", decodedList[0].id)
        assertEquals("收集聊天记录", decodedList[0].title)
        assertEquals(true, decodedList[0].enabled)
        assertEquals(true, decodedList[0].isUserModified)
        assertEquals(false, decodedList[1].enabled)
    }

    @Test
    fun `decode tolerates unknown and malformed fields`() {
        // JSON contains extra unknown keys; should still decode.
        val json = """
            { "version": 1, "tasks": [
              { "id": "a", "sceneId": 0, "icon": "Buy", "iconColor": "#4B8BFF",
                "title": "账单", "source": "basic", "enabled": true,
                "configFileName": "default_alipay_bill.json",
                "lastRunStatus": "成功", "lastRun": "2026-08-16 10:00:00",
                "lastMessage": "ok", "totallyNewField": [1,2,3] }
            ] }
        """.trimIndent()
        val decoded = com.clawmate.app.task.data.WorkflowTaskIndexCodec.decode(json)
        val list = requireNotNull(decoded)
        assertEquals(1, list.size)
        assertEquals("账单", list[0].title)
    }

    @Test
    fun `decode returns null for unrecoverable json`() {
        assertNull(WorkflowTaskIndexCodec.decode("not valid json!!"))
        assertNull(WorkflowTaskIndexCodec.decode("[]"))
    }

    @Test
    fun `encode output is deterministic for same input`() {
        val list = listOf(task(), task(id = "t9", enabled = false))
        assertEquals(WorkflowTaskIndexCodec.encode(list), WorkflowTaskIndexCodec.encode(list))
    }
}
