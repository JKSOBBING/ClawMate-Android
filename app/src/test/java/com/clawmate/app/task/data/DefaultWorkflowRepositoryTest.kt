package com.clawmate.app.task.data

import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.WorkflowRunResult
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [DefaultWorkflowRepository]: bundles configs into app-private storage,
 * derives the default task index from config metadata, and persists user
 * edits / enabled state / run status deterministically.
 */
class DefaultWorkflowRepositoryTest {

    private fun bundledDir(): File {
        val dir = Files.createTempDirectory("bundled").toFile()
        // Minimal, description-bearing config stubs matching DefaultConfigs file names.
        val stub = listOf(
            "default_alipay_bill.json" to "查看支付宝上的用户账单信息",
            "default_ele_order.json" to "查看饿了么上的用户订单信息",
            "default_bilibili_history.json" to "查看哔哩哔哩上的用户浏览记录",
            "default_tencent_video_history.json" to "查看腾讯视频上的用户观看记录",
            "default_weixin_chat.json" to "收集指定微信用户近3页的聊天记录",
            "default_weixin_today_chat.json" to "收集指定微信用户的今天聊天记录",
            "default_dianping_order.json" to "查看大众点评上的用户订单记录",
            "default_meituan_order.json" to "查看美团上的用户订单记录",
            "default_weibo_hot_search.json" to "查看微博热搜中排名第一的新闻。",
            "default_xiaohongshu_history.json" to "查看小红书上的用户浏览记录",
            "default_ctrip_order.json" to "查看携程上的用户全部订单",
        )
        stub.forEach { (name, desc) ->
            dir.resolve(name).writeText(
                """{"metadata":{"name":"basic-gui-task","description":"$desc"},"defaults":{"device":"Harmony"},"steps":[]}"""
            )
        }
        return dir
    }

    @Test
    fun `bootstraps default tasks and persists to app-private dir`() = runBlocking {
        val base = Files.createTempDirectory("base").toFile()
        val repo = DefaultWorkflowRepository(baseDir = base, bundled = FileDirBundledConfigReader(bundledDir()))

        val tasks = repo.tasks()
        assertEquals(DefaultConfigs.entries.size, tasks.size)
        // alipay -> scene 0, ele -> scene 1
        val alipay = tasks.first { it.id == "default_alipay_bill" }
        assertEquals(0, alipay.sceneId)
        assertEquals("查看支付宝上的用户账单信息", alipay.title)
        assertEquals("basic-gui-task", alipay.source)
        assertEquals(true, alipay.enabled)
        assertEquals("default_alipay_bill.json", alipay.configFileName)

        // Editable config JSON copied into storage.
        val json = repo.configJson(alipay)
        requireNotNull(json)
        assertTrue(WorkflowJsonParser.parse(json).let { it is WorkflowJsonParser.Result.Ok })
        // Index file now persisted.
        assertTrue(File(base, "workflows/task-index.json").isFile)
    }

    @Test
    fun `persists user edit and marks task user-modified`() = runBlocking {
        val base = Files.createTempDirectory("base").toFile()
        val repo = DefaultWorkflowRepository(baseDir = base, bundled = FileDirBundledConfigReader(bundledDir()))
        val task = repo.tasks().first { it.id == "default_ele_order" }

        val edited = """{"metadata":{"name":"custom","description":"我的自定义任务"},"steps":[]}"""
        repo.saveConfigJson(task, edited)

        val reloadedJson = repo.configJson(task)
        assertTrue(reloadedJson != null && reloadedJson == edited)
        val after = repo.tasks().first { it.id == task.id }
        assertTrue(after.isUserModified)
        assertEquals("我的自定义任务", after.title)
        val reopened = DefaultWorkflowRepository(base, FileDirBundledConfigReader(bundledDir()))
        assertEquals(edited, reopened.configJson(after))
        assertEquals("custom", reopened.tasks().first { it.id == task.id }.source)
    }

    @Test
    fun `invalid edit does not change existing configuration`() = runBlocking {
        val base = Files.createTempDirectory("base").toFile()
        val repo = DefaultWorkflowRepository(base, FileDirBundledConfigReader(bundledDir()))
        val task = repo.tasks().first()
        val before = repo.configJson(task)
        val attempt = runCatching { repo.saveConfigJson(task, "{broken json}") }
        assertTrue(attempt.isFailure)
        assertEquals(before, repo.configJson(task))
        assertFalse(repo.tasks().first().isUserModified)
        assertFalse(File(base, "workflows/configs").listFiles()!!.any { it.extension == "tmp" })
    }

    @Test
    fun `configuration paths cannot escape the private config directory`() = runBlocking {
        val base = Files.createTempDirectory("base").toFile()
        val repo = DefaultWorkflowRepository(base, FileDirBundledConfigReader(bundledDir()))
        val task = repo.tasks().first()
        val sentinel = File(base, "sentinel.json").apply { writeText("unchanged") }
        for (name in listOf("../../sentinel.json", "..\\..\\sentinel.json", sentinel.absolutePath, "C:sentinel.json", "..")) {
            val malicious = task.copy(configFileName = name)
            assertTrue(name, runCatching { repo.saveConfigJson(malicious, """{"steps":[]}""") }.isFailure)
            assertTrue(name, runCatching { repo.configJson(malicious) }.isFailure)
            assertEquals("unchanged", sentinel.readText())
        }
    }

    @Test
    fun `persists enabled toggle`() = runBlocking {
        val base = Files.createTempDirectory("base").toFile()
        val repo = DefaultWorkflowRepository(baseDir = base, bundled = FileDirBundledConfigReader(bundledDir()))
        val task = repo.tasks().first { it.id == "default_weixin_chat" }
        repo.setTaskEnabled(task.id, false)
        assertFalse(repo.tasks().first { it.id == task.id }.enabled)
        repo.setTaskEnabled(task.id, true)
        assertTrue(repo.tasks().first { it.id == task.id }.enabled)
    }

    @Test
    fun `persists run status mapping`() = runBlocking {
        val base = Files.createTempDirectory("base").toFile()
        val repo = DefaultWorkflowRepository(baseDir = base, bundled = FileDirBundledConfigReader(bundledDir()))
        val task = repo.tasks().first { it.id == "default_weibo_hot_search" }

        repo.recordRun(
            task.id,
            WorkflowRunResult(
                status = RunStatus.BLOCKED,
                message = "step 1 unsupported: GUI task automation",
                steps = emptyList(),
            ),
        )
        val after = repo.tasks().first { it.id == task.id }
        assertEquals("被阻断", after.lastRunStatus)
        assertTrue(after.lastMessage.contains("unsupported"))
        assertTrue(after.lastRun != "尚未执行")
    }

    @Test
    fun `reload reads persisted state instead of re-bundling`() = runBlocking {
        val base = Files.createTempDirectory("base").toFile()
        val repo = DefaultWorkflowRepository(baseDir = base, bundled = FileDirBundledConfigReader(bundledDir()))
        val task = repo.tasks().first { it.id == "default_ele_order" }
        repo.setTaskEnabled(task.id, false)

        // A fresh repository instance over the same base dir must see the edit.
        val repo2 = DefaultWorkflowRepository(baseDir = base, bundled = FileDirBundledConfigReader(bundledDir()))
        assertFalse(repo2.tasks().first { it.id == task.id }.enabled)
    }

    @Test
    fun `partial persisted index restores missing code-defined defaults`() = runBlocking {
        val base = Files.createTempDirectory("base").toFile()
        val bundled = bundledDir()
        val workflows = File(base, "workflows/configs").apply { mkdirs() }
        val kept = bundled.resolve("default_alipay_bill.json").readText()
        workflows.resolve("default_alipay_bill.json").writeText(kept)
        File(base, "workflows/task-index.json").writeText(
            WorkflowTaskIndexCodec.encode(
                listOf(
                    WorkflowTask(
                        id = "default_alipay_bill",
                        sceneId = 0,
                        icon = "Buy",
                        iconColor = "#4B8BFF",
                        title = "我的自定义账单标题",
                        source = "custom",
                        enabled = false,
                        configFileName = "default_alipay_bill.json",
                        lastRunStatus = "未运行",
                        lastRun = "尚未执行",
                        lastMessage = "保留用户状态",
                        isUserModified = true,
                    ),
                ),
            ),
        )

        val tasks = DefaultWorkflowRepository(base, FileDirBundledConfigReader(bundled)).tasks()

        assertEquals(DefaultConfigs.entries.size, tasks.size)
        assertEquals("我的自定义账单标题", tasks.first { it.id == "default_alipay_bill" }.title)
        assertEquals(false, tasks.first { it.id == "default_alipay_bill" }.enabled)
        assertTrue(tasks.any { it.id == "default_ele_order" })
    }
}
