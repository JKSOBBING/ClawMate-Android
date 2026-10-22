package com.clawmate.app.datamanager.chatmemory

import com.clawmate.app.chat.model.ChatMode
import com.clawmate.app.datamanager.DataManagerPaths
import com.clawmate.app.datamanager.DailyLogBounds
import com.clawmate.app.task.model.WorkflowSceneConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class ChatMemoryReceipt(
    @SerialName("source_ref") val sourceRef: String,
    @SerialName("source_date") val sourceDate: String,
    val mode: String,
    val facts: List<ChatMemoryFact>,
)

@Serializable
data class ChatMemoryState(val version: Int, val receipts: List<ChatMemoryReceipt>) {
    companion object { fun empty() = ChatMemoryState(1, emptyList()) }
}

data class ChatMemoryExport(val relativePath: String, val content: String)

interface ChatMemoryStorage {
    fun readState(): ChatMemoryState
    fun checkpoint(state: ChatMemoryState)
    fun exportLogs(state: ChatMemoryState): Int
}

/** Receipts and extracted facts commit in the same file; corruption never means empty. */
class FileChatMemoryStorage(baseDir: File) : ChatMemoryStorage {
    private val base = baseDir.canonicalFile
    private val stateFile = File(base, "workflows/chat-memory/state.json")
    private val dailyLogRoot = DataManagerPaths.dailyLogRoot(base)

    override fun readState(): ChatMemoryState {
        if (!stateFile.exists()) return ChatMemoryState.empty()
        try {
            checked(stateFile)
            if (!stateFile.isFile || stateFile.length() > MAX_STATE_BYTES) throw IOException("同步记录过大或不是文件")
            return json.decodeFromString<ChatMemoryState>(stateFile.readText(Charsets.UTF_8)).also(::validate)
        } catch (e: Exception) {
            throw IOException("聊天同步记录无法读取；请保留原文件并检查存储，避免重复导入", e)
        }
    }

    override fun checkpoint(state: ChatMemoryState) {
        validate(state)
        val text = json.encodeToString(state)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_STATE_BYTES) throw IOException("聊天同步记录超出存储限制")
        writeAtomic(stateFile, text)
    }

    override fun exportLogs(state: ChatMemoryState): Int {
        val exports = exports(state)
        for (item in exports) {
            val target = checked(File(dailyLogRoot, item.relativePath))
            if (!target.toPath().startsWith(dailyLogRoot.canonicalFile.toPath())) throw IOException("聊天日志路径无效")
            if (!target.isFile || target.readText(Charsets.UTF_8) != item.content) writeAtomic(target, item.content)
        }
        return exports.size
    }

    private fun checked(file: File): File = file.canonicalFile.also {
        if (!it.toPath().startsWith(base.toPath())) throw IOException("聊天记忆路径超出应用存储")
    }

    private fun writeAtomic(file: File, text: String) {
        val target = checked(file)
        val parent = target.parentFile ?: throw IOException("聊天记忆目录无效")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建聊天记忆目录")
        val temp = File.createTempFile(".chat-memory-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val MAX_STATE_BYTES = 32 * 1024 * 1024
        private val json = Json { encodeDefaults = true }
        private val whitespace = Regex("\\s+")

        fun validate(state: ChatMemoryState) {
            require(state.version == 1) { "不支持的聊天同步记录版本" }
            val seen = hashSetOf<String>()
            for (receipt in state.receipts) {
                require(receipt.sourceRef.isNotBlank() && receipt.sourceRef.length <= 3000 && seen.add(receipt.sourceRef)) {
                    "聊天同步记录的消息标识无效或重复"
                }
                require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(receipt.sourceDate)) { "聊天同步日期无效" }
                LocalDate.parse(receipt.sourceDate)
                require(receipt.mode == ChatMode.CLOUD_AGENT || receipt.mode == ChatMode.LOCAL_INFERENCE) { "聊天同步模式无效" }
                require(receipt.facts.size <= ChatMemoryExtraction.MAX_FACTS_PER_PART) { "聊天同步事实数量无效" }
                for (fact in receipt.facts) {
                    require(WorkflowSceneConfig.memoryScenes.any { it.sceneId == fact.sceneId } &&
                        fact.text.isNotBlank() && fact.text.length <= ChatMemoryExtraction.MAX_FACT_CHARS) { "聊天同步事实无效" }
                }
            }
        }

        fun exports(state: ChatMemoryState): List<ChatMemoryExport> {
            validate(state)
            val groups = linkedMapOf<Pair<String, Int>, MutableList<String>>()
            for (receipt in state.receipts) {
                val mode = if (receipt.mode == ChatMode.LOCAL_INFERENCE) "local" else "cloud"
                for (fact in receipt.facts) {
                    val path = "${receipt.sourceDate}/app-chat__${mode}__${fact.sceneId}.md"
                    groups.getOrPut(path to fact.sceneId) { mutableListOf() } += whitespace.replace(fact.text, " ").trim()
                }
            }
            // Fixed-size files keep old daily-log source refs stable and ensure
            // the Android parser never drops entries past its per-file bound.
            return groups.flatMap { (key, allEntries) -> allEntries.chunked(DailyLogBounds.MAX_ENTRIES_PER_DAY).mapIndexed { part, entries ->
                val metadata = buildJsonObject {
                    putJsonObject("workflow_metadata") { put("task_scene_id", key.second); put("source_kind", "app_chat") }
                    put("latest_entry_index", entries.size)
                }
                val lines = mutableListOf("<!-- DAILY_LOG_METADATA", metadata.toString(), "-->", "",
                    "# 聊天足迹 / ${WorkflowSceneConfig.memoryLabel(key.second)}", "")
                entries.forEachIndexed { index, entry -> lines += "${index + 1}. $entry"; lines += "" }
                val path = if (part == 0) key.first else key.first.removeSuffix(".md") + "__${part + 1}.md"
                ChatMemoryExport(path, lines.joinToString("\n"))
            } }
        }
    }
}
