package com.clawmate.platform.mika.memory

import android.content.Context
import com.wangzan101.mika.core.memory.*
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.observation.PlaceKind
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** App-private counterpart of Mika MemoryRuntime/RunLearning, shared by chat and phone tasks. */
data class RunNotes(val conversationId: String, val runId: String,
    val memoryNotes: List<MemoryNote> = emptyList(), val usedMemory: List<String> = emptyList(),
    val skillNotes: List<com.wangzan101.mika.core.skills.SkillNote> = emptyList())

class MikaMemoryRuntime(private val root: File, cipher: KeyCipher) {
    private val profileFile = File(root, "memory/profile.bin")
    val profile = ProfileStore(FileProfileBackend(profileFile, cipher), ids = { UUID.randomUUID().toString() })
    val skills = PersonalSkillFiles(root)
    val catalog get() = com.wangzan101.mika.core.knowledge.GuideCatalog.withPersonal(
        com.wangzan101.mika.core.knowledge.GuideCatalog.loadBundled(), skills)
    @Volatile var learningNotice: String = ""
        private set
    fun learn(turn: MikaMemoryTurn, end: com.wangzan101.mika.core.agent.TaskState,
        events: List<RunEvent>, model: ChatModel, tools: com.wangzan101.mika.core.tool.ToolIndex) {
        scope.launch {
            try {
                val sw = settings.switches.value
                if (!sw.learnSkills || !valid(turn)) return@launch
                val outcome = if (end is com.wangzan101.mika.core.agent.TaskState.Succeeded)
                    com.wangzan101.mika.core.agent.OutcomeJudge(model).judge(turn.goal, end.answer, turn.prior).outcome
                    else com.wangzan101.mika.core.agent.RunOutcome.Unknown
                mutex.withLock {
                    if (!valid(turn) || !settings.switches.value.learnSkills) return@withLock
                    val learning = RunLearning(sw.copy(autoRemember = false), null, skills, catalog, null,
                        com.wangzan101.mika.core.skills.SkillDrafter(model), { UUID.randomUUID().toString() })
                    val notes = learning.learn(RunAftermath(turn.conversation, turn.runId, turn.goal, end,
                        emptyList(), emptyList(), emptyList(), tools.names, tools, { events }, outcome = outcome))
                    learningNotice = notes.skillNotes.joinToString("\n") { it.text }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { learningNotice = "本次技能学习未完成，已有技能不受影响。" }
        }
    }
    val settings = MemorySettings(File(root, "settings.json"))
    val file = EncryptedMemoryFile(root, cipher, System::currentTimeMillis)
    private val taskFile = TaskMemoryFile(root, cipher, System::currentTimeMillis)
    val store = MemoryStore(file, System::currentTimeMillis, { UUID.randomUUID().toString() },
        inferenceAllowed = { settings.switches.value.inferMemories })
    val tasks = TaskMemoryStore(taskFile, System::currentTimeMillis, { UUID.randomUUID().toString() })
    val mutex = Mutex()
    private val revision = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var lastNotice: String = ""
        private set
    @Volatile var lastUndo: String? = null
        private set

    fun begin(goal: String, conversation: String, prior: List<com.wangzan101.mika.core.agent.Turn> = emptyList(), runId: String = conversation): MikaMemoryTurn {
        val switches = settings.switches.value
        return MikaMemoryTurn(this, revision.get(), goal, conversation,
            store.takeIf { switches.useMemory }?.also { it.reloadIfNeeded() },
            tasks.takeIf { switches.taskMemoryEnabled }?.also { it.reloadIfNeeded() }, prior, runId,
            if (switches.useMemory) profile.snapshot() else emptyList())
    }

    fun valid(turn: MikaMemoryTurn) = turn.revision == revision.get() && settings.switches.value.useMemory

    /** Invalidate in-flight chat tools/extractors before a user edits, forgets or disables memory. */
    suspend fun change(action: () -> Unit) {
        revision.incrementAndGet()
        mutex.withLock { action() }
    }

    fun note(changes: List<Change>) {
        if (changes.isEmpty()) return
        lastUndo = changes.last().undoToken
        lastNotice = "最近变更 ${changes.size} 条；可在条目中查看来源、编辑或删除。"
    }

    suspend fun finish(turn: MikaMemoryTurn, model: ChatModel, changes: List<Change>,
        events: List<RunEvent> = emptyList()): Int = mutex.withLock {
        if (!valid(turn) || turn.memory == null) return@withLock 0
        note(changes)
        val sw = settings.switches.value
        // RunLearning: only the actual user utterances are extraction inputs, never screen/assistant text.
        val readWeb = events.filterIsInstance<RunEvent.Observed>().any { it.place?.kind in setOf(PlaceKind.Site, PlaceKind.Page) }
        val notices = events.filterIsInstance<RunEvent.Activity>().any { it.label == "notices_read" }
        if (!sw.autoRemember || readWeb || notices || changes.isNotEmpty()) return@withLock changes.size
        // Stage extraction in memory; deleting while the model runs invalidates publication.
        val guarded = object : MemoryPersistence {
            override fun load() = store.items.value
            override fun save(items: List<MemoryItem>) { check(valid(turn)) { "memory_changed" } }
        }
        // The shared mutex serializes this temporary view with chat writes and management edits.
        val extractionStore = MemoryStore(guarded, store.clock, { UUID.randomUUID().toString() })
        val extracted = MemoryExtractor(model).extract(turn.utterances.all,
            MemoryExtractor.related(turn.utterances.all, store.items.value), extractionStore) { quote ->
            MemorySource("user_said", quote, turn.conversation, store.clock(), "Ui")
        }
        // Apply through the shared store to preserve its observable state and undo stack.
        // Extraction used a staging persistence above; publishing is handled below.
        if (!valid(turn)) return@withLock 0
        val published = extracted.mapNotNull { change -> when (change) {
            is Change.Added -> store.add(change.item.kind, change.item.content, change.item.keywords, change.item.source).getOrNull()
            is Change.Updated -> store.update(change.item.id, change.item.content, change.item.keywords, change.item.source).getOrNull()
            is Change.Forgot -> store.forget(change.before.id).getOrNull()
        } }
        note(published)
        published.size
    }

    fun finishLater(turn: MikaMemoryTurn, model: ChatModel, changes: List<Change>, events: List<RunEvent>) {
        scope.launch { try { finish(turn, model, changes, events) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { lastNotice = "本次记忆整理未完成，已保存的记忆不受影响。" } }
    }

    fun recordTask(turn: MikaMemoryTurn, events: List<RunEvent>) {
        scope.launch {
            mutex.withLock {
                if (!valid(turn) || !settings.switches.value.taskMemoryEnabled) return@withLock
                runCatching {
                    TaskMemoryRecorder.record(turn.goal, events, System.currentTimeMillis(), UUID.randomUUID().toString())
                        ?.let { tasks.add(it) }
                }
            }
        }
    }

    fun clear() {
        store.clearAll(); tasks.clearAll(); file.wipe(); taskFile.wipe()
        profileFile.delete(); File(profileFile.parentFile, "${profileFile.name}.tmp").delete()
        File(root, "skills").deleteRecursively(); learningNotice = ""
        lastUndo = null; lastNotice = "记忆和任务摘要已清除；聊天原文与运行记录仍保留。"
    }

    companion object {
        private val instances = ConcurrentHashMap<String, MikaMemoryRuntime>()
        fun of(context: Context): MikaMemoryRuntime {
            val root = File(context.applicationContext.filesDir, "mika-memory")
            return instances.computeIfAbsent(root.canonicalPath) { MikaMemoryRuntime(root, KeystoreKeyCipher("clawmate-mika-memory")) }
        }
    }
}

class MikaMemoryTurn(val runtime: MikaMemoryRuntime, val revision: Long, val goal: String,
    val conversation: String, val memory: MemoryStore?, val tasks: TaskMemoryStore?,
    val prior: List<com.wangzan101.mika.core.agent.Turn> = emptyList(), val runId: String = conversation,
    val profileSnapshot: List<ProfileEntry> = emptyList()) {
    val utterances = UserUtterances().apply {
        val confirmation = prior.lastOrNull(); val proposal = prior.dropLast(1).lastOrNull()
        if (confirmation != null && proposal != null && !com.wangzan101.mika.core.agent.NoticeTurn.tainted(prior))
            addPreviousConfirmation(confirmation.userText, proposal.outcome, proposal.status)
        add(goal)
    }
    fun context(): String? {
        val block = memory?.let { MemoryContext.build(it.items.value, goal, null, mode = MemoryContext.Mode.Auto) }
        memory?.markUsed(block?.usedIds.orEmpty())
        return listOfNotNull(ProfileContext.render(profileSnapshot), block?.message).joinToString("\n\n").ifBlank { null }
    }
}
