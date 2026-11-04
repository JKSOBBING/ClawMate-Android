package com.clawmate.app.localmodel.download

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelScopeDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val filesDir by lazy { temp.newFolder("files") }

    private class Call(val url: String, val range: String, val response: Response)

    private fun response(url: String, code: Int, body: ByteArray, extra: (okhttp3.Response.Builder) -> Unit = {}): Response {
        val builder = Response.Builder().request(okhttp3.Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
            .code(code).message("ok").body(body.toResponseBody("application/octet-stream".toMediaType()))
        extra(builder)
        return builder.build()
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun downloader(script: (okhttp3.Request) -> Response, events: (String) -> Unit = {}): ModelScopeDownloader {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = script(chain.request())
            events("url=" + chain.request().url + " range=" + (chain.request().header("Range") ?: "") + " code=" + response.code)
            response
        }.build()
        return ModelScopeDownloader(filesDir, client)
    }

    private fun manifest(extraFiles: String = ""): ByteArray {
        val files = listOf(
            """{"Type":"blob","Path":"config.json","Size":${CONFIG.size},"Sha256":"${sha(CONFIG)}"}""",
            """{"Type":"blob","Path":"llm.mnn","Size":${WEIGHT.size},"Sha256":"${sha(WEIGHT)}"}""",
            """{"Type":"blob","Path":"tokenizer.mtok","Size":${TOKENIZER.size}}""",
            """{"Type":"blob","Path":"vision/vision_pre.mnn","Size":${AUX.size}}"""
        ).joinToString(",")
        return """{"Code":200,"Data":{"Files":[$files$extraFiles]}}""".toByteArray()
    }

    private fun target(): File = File(filesDir, "modelscope/o__r")

    private fun waitFor(downloader: ModelScopeDownloader, timeoutMs: Long = 10000): ModelDownloadProgress {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val progress = downloader.progress()
            if (!downloader.isRunning()) return progress
            Thread.sleep(25)
        }
        throw AssertionError("下载未在超时内结束：" + downloader.progress())
    }

    @Test fun defaultSelectionDownloadsOnlyReferencedFiles() {
        val downloader = downloader(script = { request ->
            val url = request.url.toString()
            when {
                url.contains("/repo/files") -> response(url, 200, manifest())
                url.endsWith("config.json") -> response(url, 200, CONFIG)
                url.endsWith("llm.mnn") -> response(url, 200, WEIGHT)
                url.endsWith("tokenizer.mtok") -> response(url, 200, TOKENIZER)
                else -> response(url, 404, ByteArray(0))
            }
        })
        assertTrue(downloader.start("o/r", false))
        val progress = waitFor(downloader)
        assertEquals("done", progress.state)
        assertEquals(100, progress.percent)
        assertTrue(File(target(), "llm.mnn").isFile)
        assertTrue(File(target(), "tokenizer.mtok").isFile)
        assertFalse("未勾选完整下载时不应写入视觉子目录", File(target(), "vision/vision_pre.mnn").exists())
        assertTrue(File(target(), ModelScopeSpec.COMPLETE_FILE).isFile)
        assertTrue(File(target(), ModelScopeSpec.MANIFEST_FILE).readText().contains("llm.mnn"))
    }

    @Test fun fullDownloadIncludesAuxiliaryFiles() {
        val downloader = downloader(script = { request ->
            val url = request.url.toString()
            when {
                url.contains("/repo/files") -> response(url, 200, manifest())
                url.endsWith("config.json") -> response(url, 200, CONFIG)
                url.endsWith("llm.mnn") -> response(url, 200, WEIGHT)
                url.endsWith("tokenizer.mtok") -> response(url, 200, TOKENIZER)
                url.endsWith("vision_pre.mnn") -> response(url, 200, AUX)
                else -> response(url, 404, ByteArray(0))
            }
        })
        assertTrue(downloader.start("o/r", true))
        val progress = waitFor(downloader)
        assertEquals("done", progress.state)
        assertTrue(File(target(), "vision/vision_pre.mnn").isFile)
    }

    @Test fun resumeSendsRangeHeaderForPartialFile() {
        val dir = target().also { it.mkdirs() }
        File(dir, "llm.mnn").writeBytes(WEIGHT.copyOfRange(0, 100))
        val ranges = mutableListOf<String>()
        val downloader = downloader(script = { request ->
            val url = request.url.toString()
            ranges.add(request.header("Range") ?: "")
            when {
                url.contains("/repo/files") -> response(url, 200, manifest())
                url.endsWith("config.json") -> response(url, 200, CONFIG)
                url.endsWith("llm.mnn") -> {
                    val from = 100
                    response(url, 206, WEIGHT.copyOfRange(from, WEIGHT.size)) { it.header("Content-Range", "bytes 100-${WEIGHT.size - 1}/${WEIGHT.size}") }
                }
                url.endsWith("tokenizer.mtok") -> response(url, 200, TOKENIZER)
                else -> response(url, 404, ByteArray(0))
            }
        })
        assertTrue(downloader.start("o/r", false))
        assertEquals("done", waitFor(downloader).state)
        assertTrue(ranges.contains("bytes=100-"))
        assertTrue(File(dir, "llm.mnn").readBytes().contentEquals(WEIGHT))
    }

    @Test fun serverIgnoringRangeRestartsFile() {
        val dir = target().also { it.mkdirs() }
        File(dir, "llm.mnn").writeBytes(WEIGHT.copyOfRange(0, 100))
        val downloader = downloader(script = { request ->
            val url = request.url.toString()
            when {
                url.contains("/repo/files") -> response(url, 200, manifest())
                url.endsWith("config.json") -> response(url, 200, CONFIG)
                url.endsWith("llm.mnn") -> response(url, 200, WEIGHT) // 忽略 Range
                url.endsWith("tokenizer.mtok") -> response(url, 200, TOKENIZER)
                else -> response(url, 404, ByteArray(0))
            }
        })
        assertTrue(downloader.start("o/r", false))
        assertEquals("done", waitFor(downloader).state)
        assertTrue(File(dir, "llm.mnn").readBytes().contentEquals(WEIGHT))
    }

    @Test fun checksumMismatchFailsAndLeavesNoCompleteMarker() {
        val downloader = downloader(script = { request ->
            val url = request.url.toString()
            when {
                url.contains("/repo/files") -> response(url, 200, manifest())
                url.endsWith("config.json") -> response(url, 200, CONFIG)
                url.endsWith("llm.mnn") -> response(url, 200, ByteArray(WEIGHT.size) { 7 }) // 内容错误
                url.endsWith("tokenizer.mtok") -> response(url, 200, TOKENIZER)
                else -> response(url, 404, ByteArray(0))
            }
        })
        assertTrue(downloader.start("o/r", false))
        val progress = waitFor(downloader)
        assertEquals("error", progress.state)
        assertTrue(progress.failedFiles.isNotEmpty())
        assertFalse(File(target(), ModelScopeSpec.COMPLETE_FILE).exists())
    }

    @Test fun httpErrorIsReportedNotSwallowed() {
        val downloader = downloader(script = { request ->
            val url = request.url.toString()
            if (url.contains("/repo/files")) response(url, 500, ByteArray(0)) else response(url, 404, ByteArray(0))
        })
        assertTrue(downloader.start("o/r", false))
        val progress = waitFor(downloader)
        assertEquals("error", progress.state)
        assertTrue(progress.message.contains("HTTP 500"))
    }

    @Test fun invalidRepoIsRejectedWithoutNetwork() {
        val downloader = downloader(script = { request -> response(request.url.toString(), 200, ByteArray(0)) })
        assertFalse(downloader.start("not-a-repo", false))
        assertEquals("error", downloader.progress().state)
    }

    @Test fun pauseThenResumeContinuesFromExistingBytes() {
        val holder = arrayOfNulls<ModelScopeDownloader>(1)
        val downloader = downloader(script = { request ->
            val url = request.url.toString()
            when {
                url.contains("/repo/files") -> response(url, 200, manifest())
                url.endsWith("config.json") -> response(url, 200, CONFIG)
                url.endsWith("llm.mnn") -> response(url, 200, WEIGHT)
                url.endsWith("tokenizer.mtok") -> {
                    // 第二个文件正在写入时发出暂停，模拟用户中途点击暂停。
                    holder[0]?.pause()
                    response(url, 200, TOKENIZER)
                }
                else -> response(url, 404, ByteArray(0))
            }
        })
        holder[0] = downloader
        assertTrue(downloader.start("o/r", false))
        val paused = waitFor(downloader)
        assertEquals("paused", paused.state)
        assertTrue("暂停时已经写入的 config.json 应保留", File(target(), "config.json").isFile)
        assertTrue("暂停时已经写完的 llm.mnn 应保留", File(target(), "llm.mnn").isFile)
        assertFalse("暂停后不应写完成标记", File(target(), ModelScopeSpec.COMPLETE_FILE).isFile)

        val resumed = downloader(script = { request ->
            val url = request.url.toString()
            when {
                url.contains("/repo/files") -> response(url, 200, manifest())
                url.endsWith("config.json") -> response(url, 200, CONFIG)
                url.endsWith("llm.mnn") -> response(url, 200, WEIGHT)
                url.endsWith("tokenizer.mtok") -> response(url, 200, TOKENIZER)
                else -> response(url, 404, ByteArray(0))
            }
        })
        assertTrue(resumed.start("o/r", false))
        val resumedProgress = waitFor(resumed)
        assertEquals("done", resumedProgress.state)
        assertTrue(File(target(), "llm.mnn").readBytes().contentEquals(WEIGHT))
        assertTrue(File(target(), ModelScopeSpec.COMPLETE_FILE).isFile)
    }

    @Test fun cancelRemovesPartialDirectory() {
        val gate = CountDownLatch(1)
        val downloader = downloader(script = { request ->
            val url = request.url.toString()
            when {
                url.contains("/repo/files") -> response(url, 200, manifest())
                url.endsWith("config.json") -> response(url, 200, CONFIG)
                url.endsWith("llm.mnn") -> { gate.await(5, TimeUnit.SECONDS); response(url, 200, WEIGHT) }
                else -> response(url, 404, ByteArray(0))
            }
        })
        assertTrue(downloader.start("o/r", false))
        Thread.sleep(200)
        downloader.cancel()
        gate.countDown()
        val progress = waitFor(downloader)
        assertEquals("cancelled", progress.state)
        assertFalse(target().exists())
    }

    companion object {
        private val CONFIG = "{\"llm_model\":\"llm.mnn\",\"tokenizer_file\":\"tokenizer.mtok\"}".toByteArray()
        private val WEIGHT = ByteArray(4096) { (it % 97).toByte() }
        private val TOKENIZER = ByteArray(256) { (it % 31).toByte() }
        private val AUX = ByteArray(64) { 3 }
    }
}