package com.clawmate.app.datamanager

import java.io.File

/**
 * Explicit app-private write API for daily-log documents — the one place that
 * documents how an external workflow importer writes daily-log files so the
 * [DailyLogEventSource] tree can later be consumed by the Home pipeline.
 *
 * Phase-29 rule #4: daily-log files are **never auto-created** by the
 * recommendation pipeline. Nothing in [DailyLogEventSource] or
 * [DailyLogPlannerEventMapper] creates a directory or file; importing is the
 * workflow/import layer's job. [DailyLogWorkspace] is that import contract:
 *
 *   val ws = DailyLogWorkspace(appPrivFileDir)
 *   ws.writeDocument(
 *       day = "2026-08-01",
 *       fileName = "wechat.logs.md",
 *       entries = listOf("1. ...", "2. ..."),
 *       metadata = mapOf("workflow" to "wechat"),
 *   )
 *
 * The write is bounded and follows the exact layout the reader expects:
 *   <base>/workflows/daily-log/YYYY-MM-DD/<fileName>.md
 * Each document begins with the optional Harmony metadata header followed by
 * blank-line separated numbered entries.
 *
 * This is a pure `java.io.File` API (no Android deps), so unit tests can use a
 * temp dir to model both writing and the subsequent read path.
 */
object DailyLogWorkspace {

    /**
     * Append/replace one daily-log document. Never used by the recommendation
     * pipeline itself — only by external workflow/import code (and tests).
     *
     * @return the written [File], or null when the request is invalid (bad day,
     *   bad file name, over the entry cap, or the resulting text would exceed
     *   the document bound).
     */
    fun writeDocument(
        baseDir: File,
        day: String,
        fileName: String,
        entries: List<String>,
        metadata: Map<String, String> = emptyMap(),
    ): File? {
        val dayDir = File(File(baseDir, DAILY_LOG_ROOT_RELATIVE), day)
        if (!DailyLogPaths.isValidDayDirectory(day)) return null
        if (!fileName.endsWith(".md") || fileName.length > DailyLogBounds.MAX_FILE_NAME_LENGTH) return null
        if (entries.isEmpty() || entries.size > DailyLogBounds.MAX_ENTRIES_PER_DAY) return null

        val body = StringBuilder()
        if (metadata.isNotEmpty()) {
            body.append("<!-- DAILY_LOG_METADATA\n")
            body.append("{")
            val items = metadata.entries
                .filter { it.value.isNotBlank() }
                .take(DailyLogBounds.MAX_FILE_NAME_LENGTH)
                .joinToString(",") { (k, v) ->
                    "\"${escape(k)}\":\"${escape(v)}\""
                }
            body.append(items)
            body.append("}\n-->\n")
        }
        entries.forEachIndexed { idx, text ->
            if (text.isNotBlank()) {
                body.append("${idx + 1}. ").append(text.trim()).append("\n\n")
            }
        }
        val content = body.toString()
        if (content.isBlank()) return null
        if (content.length > DailyLogBounds.MAX_DOCUMENT_CHARACTERS) return null

        return runCatching {
            dayDir.mkdirs()
            val file = File(dayDir, fileName)
            file.writeText(content)
            file
        }.getOrNull()
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * The relative path of the daily-log root, for documentation/tests.
     * Mirrors `workflows/daily-log` under the app-private base dir.
     */
    const val DAILY_LOG_ROOT_RELATIVE: String = "workflows/daily-log"
}
