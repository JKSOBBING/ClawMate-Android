package com.clawmate.platform.localmodel

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

class GgufImportTest {
    @get:Rule val temp = TemporaryFolder()

    // This fixture validates storage only. Full model compatibility is checked by llama.cpp at load time.
    private val bytes = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        .put("GGUF".toByteArray()).putInt(3).putLong(13).putLong(27).array()

    @Test fun importsSelectsAndDeduplicatesAcrossRestarts() {
        val manager = LocalModelManager(temp.root)
        val id = manager.importGguf("../测试模型.gguf", bytes.inputStream(), bytes.size.toLong())
        val reopened = LocalModelManager(temp.root)
        assertEquals(id, reopened.selectedModelId())
        assertArrayEquals(bytes, reopened.resolveGguf(id).readBytes())
        assertEquals(id, reopened.importGguf("副本.GGUF", bytes.inputStream()))
        val shown = manager.list().single()
        assertEquals("★ 测试模型.gguf", shown.label)
        assertTrue(shown.runnable)
        assertEquals(File(temp.root, "gguf"), File(shown.path).parentFile)
        assertTrue(manager.delete(id))
        assertEquals("", reopened.selectedModelId())
        assertTrue(reopened.list().isEmpty())
    }

    @Test fun badHeadersShardsIncompleteAndCancelledCopiesCannotChangeSelection() {
        val manager = LocalModelManager(temp.root)
        val legacy = File(temp.root, "modelscope/acme__old").apply { mkdirs() }
        File(legacy, "config.json").writeText("{}")
        File(legacy, "model.mnn").writeText("weight")
        File(legacy, ".download_complete").writeText("done")
        val oldId = manager.select("modelscope:acme/old").id
        assertThrows(IllegalArgumentException::class.java) { manager.resolveGguf(oldId) }
        assertThrows(IllegalArgumentException::class.java) { manager.resolveGguf("gguf:../../private") }
        assertThrows(IllegalArgumentException::class.java) { manager.importGguf("broken.gguf", ByteArray(40).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { manager.importGguf("x-00001-of-00002.gguf", bytes.inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { manager.importGguf("truncated.gguf", bytes.inputStream(), 100) }
        assertThrows(CancellationException::class.java) {
            manager.importGguf("ok.gguf", bytes.inputStream(), beforePublish = { throw CancellationException() })
        }
        assertEquals(oldId, manager.selectedModelId())
        assertFalse(manager.list().single().runnable)
        assertTrue(manager.list().single().statusText.contains("MNN 格式"))
        assertArrayEquals(emptyArray<String>(), File(temp.root, "gguf").list())
    }

    @Test fun completedDownloadResolvesOnlyOneMainFile() {
        val manager = LocalModelManager(temp.root)
        val repo = File(temp.root, "modelscope/acme__voice").apply { mkdirs() }
        val nested = File(repo, "q4").apply { mkdir() }
        val main = File(nested, "chat-q4.gguf").apply { writeBytes(bytes) }
        File(repo, "mmproj-f16.gguf").writeBytes(bytes)
        val id = "modelscope:acme/voice"
        assertFalse(manager.list().single().runnable)
        assertThrows(IllegalArgumentException::class.java) { manager.resolveGguf(id) }
        File(repo, ".download_complete").writeText("done")
        assertTrue(manager.select(id).runnable)
        assertEquals(main, manager.resolveGguf(id))
        File(repo, "chat-q8.gguf").writeBytes(bytes)
        assertFalse(manager.list().single().runnable)
        assertThrows(IllegalArgumentException::class.java) { manager.resolveGguf(id) }
        assertTrue(manager.delete(id))
    }
}
