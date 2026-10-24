package com.clawmate.app.task.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalWorkflowFilesTest {
    @Test fun categoryListingUsesActualFilesAndByteSizes() = runBlocking {
        val base = Files.createTempDirectory("workflow-files").toFile()
        val config = file(base, "configs/a.json", "{}")
        val daily = file(base, "daily-log/2026-10-25/1.md", "# 本机记录")
        file(base, "runs/out.txt", "运行输出")
        File(base, "unrelated.txt").writeText("不属于工作流")
        val listing = LocalWorkflowFiles(base).list()
        assertTrue(listing.complete)
        assertEquals(3, listing.files.size)
        assertEquals(config.length(), listing.files.single { it.category == WorkflowFileCategory.CONFIG }.size)
        assertEquals(daily.length(), listing.files.single { it.category == WorkflowFileCategory.DAILY }.size)
        assertEquals("2026-10-25/1.md", listing.files.single { it.category == WorkflowFileCategory.DAILY }.name)
    }

    @Test fun previewReadsExactUtf8WithoutChangingTheFile() = runBlocking {
        val base = Files.createTempDirectory("workflow-preview").toFile()
        val content = "设备文本\n**Markdown**"
        val original = file(base, "daily-log/day.md", content)
        val store = LocalWorkflowFiles(base)
        val preview = store.read(store.list().files.single())
        assertEquals(content, preview.text)
        assertFalse(preview.truncated)
        assertNull(preview.error)
        assertEquals(content, original.readText())
    }

    @Test fun forgedPathCannotReadOutsideTheSelectedCategory() = runBlocking {
        val base = Files.createTempDirectory("workflow-boundary").toFile()
        File(base, "outside.txt").writeText("private-fixture")
        val forged = ManagedWorkflowFile(WorkflowFileCategory.CONFIG, "configs/../../outside.txt", "outside.txt", 15, 0)
        val result = LocalWorkflowFiles(base).read(forged)
        assertNotNull(result.error)
        assertFalse(result.text.contains("private-fixture"))
    }

    @Test fun oversizedPreviewReportsTruncationWithoutLoadingTheWholeDocument() = runBlocking {
        val base = Files.createTempDirectory("workflow-large").toFile()
        file(base, "configs/large.json", "x".repeat(LocalWorkflowFiles.MAX_PREVIEW_BYTES + 100))
        val store = LocalWorkflowFiles(base)
        val result = store.read(store.list().files.single())
        assertTrue(result.truncated)
        assertEquals(LocalWorkflowFiles.MAX_PREVIEW_BYTES, result.text.length)
    }

    @Test fun binaryAndMissingFilesAreExplicitReadErrors() = runBlocking {
        val base = Files.createTempDirectory("workflow-binary").toFile()
        val file = File(base, "workflows/runs/image.bin").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 0, 2)) }
        val store = LocalWorkflowFiles(base)
        val listed = store.list().files.single()
        assertNotNull(store.read(listed).error)
        file.delete()
        assertNotNull(store.read(listed).error)
    }

    private fun file(base: File, relative: String, text: String): File = File(base, "workflows/$relative").apply {
        parentFile.mkdirs(); writeText(text)
    }
}
