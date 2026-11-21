package com.clawmate.platform.mika

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunRecords
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.record.RunSummary
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File

/**
 * File-backed port of Mika's run records (`records/<runId>/run.json` + `events.jsonl`).
 *
 * Upstream splits the contract in two: [RunSink] is what `AgentLoop` pushes into, and
 * [RunRecords] is the read side used by `history_read`. Both are implemented here over
 * app-private files so a phone task keeps a durable, redacted record without either side
 * reaching into ClawMate's storage.
 *
 * Contract rules carried over from upstream:
 * - events arrive already redacted and truncated by the kernel; this class re-encodes them as
 *   JSONL only, it does not add interpretation;
 * - a write failure must never fail the task, so every disk call is guarded and swallowed;
 * - reading an unknown, removed or unreadable run returns `null` / an empty list instead of
 *   throwing.
 */
class MikaRunStore(private val root: File) : RunRecords {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Starts a recorder for [runId]; events are appended and the summary written on [Recorder.close]. */
    fun begin(runId: String, goal: String, model: String, origin: String? = null): Recorder {
        val dir = File(root, safeId(runId))
        runCatching { dir.mkdirs() }
        return Recorder(
            runId = safeId(runId),
            dir = dir,
            summary = RunSummary(
                id = safeId(runId),
                goal = goal.trim().take(MAX_GOAL),
                model = model,
                startedAtMs = System.currentTimeMillis(),
                origin = origin,
            ),
        )
    }

    override suspend fun summary(runId: String): RunSummary? {
        val dir = File(root, safeId(runId))
        val stored = File(dir, RUN_FILE)
        if (stored.isFile) {
            runCatching { json.decodeFromString(RunSummary.serializer(), stored.readText()) }.getOrNull()?.let { return it }
        }
        return fold(events(runId), runId)
    }

    override suspend fun events(runId: String): List<RunEvent> {
        val file = File(File(root, safeId(runId)), EVENTS_FILE)
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    if (line.isBlank()) null
                    else runCatching { json.decodeFromString(RunEvent.serializer(), line) }.getOrNull()
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    /** Newest-first summaries for the run list UI; unreadable entries are skipped. */
    fun list(limit: Int = 20): List<RunSummary> {
        val dirs = runCatching { root.listFiles()?.filter { it.isDirectory } }.getOrNull() ?: return emptyList()
        return dirs.asSequence()
            .mapNotNull { dir ->
                val file = File(dir, RUN_FILE)
                if (!file.isFile) null
                else runCatching { json.decodeFromString(RunSummary.serializer(), file.readText()) }.getOrNull()
            }
            .sortedByDescending { it.startedAtMs }
            .take(limit)
            .toList()
    }

    /**
     * Same list projected onto a ClawMate-owned type, so a host module that does not depend on
     * `:mika-core` (the shared Android host) can read records without leaking kernel types.
     */
    fun listBriefs(limit: Int = 20): List<MikaRunBrief> = list(limit).map { summary ->
        MikaRunBrief(
            id = summary.id,
            goal = summary.goal,
            model = summary.model,
            startedAtMs = summary.startedAtMs,
            endedAtMs = summary.endedAtMs,
            outcome = summary.outcome,
            steps = summary.steps,
            answerOrReason = summary.answerOrReason,
        )
    }

    inner class Recorder internal constructor(
        val runId: String,
        private val dir: File,
        private var summary: RunSummary,
    ) : RunSink, Closeable {

        private val lock = Any()
        private var closed = false

        override fun event(e: RunEvent) {
            synchronized(lock) {
                if (closed) return
                summary = summary.with(e)
                runCatching {
                    File(dir, EVENTS_FILE).appendText(json.encodeToString(RunEvent.serializer(), e) + "\n")
                }
            }
        }

        override fun media(mediaId: Int, png: ByteArray) {
            synchronized(lock) {
                if (closed) return
                runCatching {
                    val media = File(dir, MEDIA_DIR).apply { mkdirs() }
                    File(media, "$mediaId.png").writeBytes(png)
                }
            }
        }

        /** Records the terminal outcome and writes `run.json` atomically. */
        fun close(outcome: String?, answerOrReason: String?) {
            synchronized(lock) {
                if (closed) return
                closed = true
                summary = summary.copy(
                    endedAtMs = summary.endedAtMs ?: System.currentTimeMillis(),
                    outcome = outcome,
                    answerOrReason = answerOrReason?.trim()?.take(MAX_ANSWER),
                )
                runCatching {
                    val target = File(dir, RUN_FILE)
                    val temp = File(dir, "$RUN_FILE.tmp")
                    temp.writeText(json.encodeToString(RunSummary.serializer(), summary))
                    if (!temp.renameTo(target)) {
                        target.delete()
                        temp.renameTo(target)
                    }
                }
            }
        }

        override fun close() = close(null, null)
    }

    private fun fold(events: List<RunEvent>, runId: String): RunSummary? {
        val first = events.firstOrNull() ?: return null
        val started = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()
        var summary = RunSummary(
            id = runId,
            goal = started?.goal.orEmpty(),
            model = started?.model.orEmpty(),
            startedAtMs = started?.atMs ?: first.atMs,
            origin = started?.origin,
        )
        for (e in events) summary = summary.with(e)
        return summary
    }

    /** Keeps a run id inside its own directory: ids come from the kernel, not from user text. */
    private fun safeId(runId: String): String {
        val cleaned = runId.trim().map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '_'
        }.joinToString("")
        return cleaned.take(MAX_ID).takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "run"
    }

    private companion object {
        const val RUN_FILE = "run.json"
        const val EVENTS_FILE = "events.jsonl"
        const val MEDIA_DIR = "media"
        const val MAX_ID = 80
        const val MAX_GOAL = 2_000
        const val MAX_ANSWER = 4_000
    }
}

/** Plain projection of a stored run summary; carries no `:mika-core` type. */
data class MikaRunBrief(
    val id: String,
    val goal: String,
    val model: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val outcome: String?,
    val steps: Int,
    val answerOrReason: String?,
)
