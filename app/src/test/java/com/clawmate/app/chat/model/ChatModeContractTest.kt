package com.clawmate.app.chat.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression contract for the Harmony chat mode selector. */
class ChatModeContractTest {

    @Test
    fun `known modes retain their persisted ids and labels`() {
        assertEquals(ChatMode.CLOUD_AGENT, ChatMode.normalize(ChatMode.CLOUD_AGENT))
        assertEquals("云端智能体", ChatMode.label(ChatMode.CLOUD_AGENT))
        assertEquals(ChatMode.LOCAL_INFERENCE, ChatMode.normalize(ChatMode.LOCAL_INFERENCE))
        assertEquals("本地推理", ChatMode.label(ChatMode.LOCAL_INFERENCE))
    }

    @Test
    fun `unknown persisted mode falls back to cloud agent`() {
        assertEquals(ChatMode.CLOUD_AGENT, ChatMode.normalize("legacy-mode"))
        assertEquals(ChatMode.CLOUD_AGENT, ChatMode.normalize(null))
    }
}
