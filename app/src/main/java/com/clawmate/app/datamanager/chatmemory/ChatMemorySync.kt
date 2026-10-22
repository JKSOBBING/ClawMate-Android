package com.clawmate.app.datamanager.chatmemory

import com.clawmate.app.chat.model.ChatSession
import java.io.File
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

fun interface ChatMemorySessionSource { suspend fun loadRecent(now: Long): List<ChatSession> }
fun interface ChatMemoryExtractor { suspend fun extract(inputs: List<ChatMemoryInput>): List<ChatMemoryResult> }

data class ChatMemoryProgress(val processedParts: Int, val totalParts: Int, val individualRetry: Boolean)
data class ChatMemorySyncSummary(val processedParts: Int, val factCount: Int, val exportedFiles: Int, val totalFacts: Int)

/** Explicit, serial, restartable synchronization; never invents local fallback facts. */
class ChatMemorySync(
    baseDir: File,
    private val sessions: ChatMemorySessionSource,
    private val extractor: ChatMemoryExtractor,
    private val storage: ChatMemoryStorage = FileChatMemoryStorage(baseDir),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val mutex = locks.getOrPut(baseDir.canonicalPath) { Mutex() }

    suspend fun sync(
        explicitAction: Boolean,
        now: Long = System.currentTimeMillis(),
        progress: (ChatMemoryProgress) -> Unit = {},
    ): ChatMemorySyncSummary {
        require(explicitAction) { "请先确认聊天记忆同步，再开始处理聊天内容" }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                var state = storage.readState().also(FileChatMemoryStorage::validate)
                // Repair exports from a previous committed batch before looking at new messages.
                currentCoroutineContext().ensureActive()
                storage.exportLogs(state)
                val pending = ChatMemoryExtraction.pendingInputs(sessions.loadRecent(now),
                    state.receipts.map { it.sourceRef }.toSet(), now, zone)
                val batches = ArrayDeque(ChatMemoryExtraction.batches(pending))
                var processed = 0
                var facts = 0
                var individualRetry = false
                progress(ChatMemoryProgress(0, pending.size, false))
                while (batches.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val batch = batches.first()
                    val results = try {
                        val raw = extractor.extract(batch)
                        // Also enforce the contract for injected extractors, before any receipt commits.
                        ChatMemoryExtraction.parseResults(buildJsonObject {
                            put("items", Json.encodeToJsonElement(raw))
                        }.toString(), batch)
                    } catch (e: ChatMemoryResponseException) {
                        if (batch.size == 1) throw e
                        batches.removeFirst()
                        batch.asReversed().forEach { batches.addFirst(listOf(it)) }
                        individualRetry = true
                        progress(ChatMemoryProgress(processed, pending.size, true))
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    val receipts = batch.zip(results).map { (input, result) ->
                        ChatMemoryReceipt(input.sourceRef, input.sourceDate, input.mode, result.facts)
                    }
                    val next = ChatMemoryState(1, state.receipts + receipts)
                    storage.checkpoint(next)
                    state = next
                    processed += batch.size
                    facts += receipts.sumOf { it.facts.size }
                    batches.removeFirst()
                    progress(ChatMemoryProgress(processed, pending.size, individualRetry))
                }
                currentCoroutineContext().ensureActive()
                val exportedFiles = storage.exportLogs(state)
                ChatMemorySyncSummary(processed, facts, exportedFiles, state.receipts.sumOf { it.facts.size })
            }
        }
    }

    companion object {
        // Multiple screens/repository instances share the same on-device checkpoint lock.
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
