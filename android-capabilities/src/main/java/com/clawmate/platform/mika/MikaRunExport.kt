package com.clawmate.platform.mika

import android.app.Activity
import android.content.Intent
import com.wangzan101.mika.core.remote.RunExportRecord
import com.wangzan101.mika.core.remote.RunRecordExport
import java.io.File

/**
 * Export one stored run in the upstream `RunRecordExport` format.
 *
 * The kernel already re-redacts on the way out (`RunRecordExport.of` walks every string and marks
 * runs whose summary was never closed as `partial`), so this port only has to hand it the stored
 * summary and events. Screenshots are not exported — media events keep their id and step only.
 *
 * Delivery is the system share sheet with the JSONL as text: a single run is small, and this
 * avoids adding a provider or activity-result plumbing to the shared host.
 */
object MikaRunExport {

    /** Upper bound for the share payload; keeps the binder transaction well under its limit. */
    const val MAX_SHARE_CHARS = 300_000

    /**
     * The JSONL export (header line, then one event per line) or `null` when the run is unknown.
     * Pure enough to unit-test: no Android types involved.
     */
    fun payloadJsonl(root: File, runId: String): String? {
        val store = MikaRunStore(root)
        val summary = runCatching { kotlinx.coroutines.runBlocking { store.summary(runId) } }.getOrNull()
            ?: return null
        val events = runCatching { kotlinx.coroutines.runBlocking { store.events(runId) } }
            .getOrDefault(emptyList())
        val record = RunExportRecord(
            taskId = null,
            conversationId = null,
            runId = runId,
            summary = summary,
            events = events,
        )
        return runCatching { RunRecordExport.of(record).jsonl }.getOrNull()
    }

    /**
     * Opens the system share sheet with the export as plain text. Runs on the UI thread; returns
     * false when there is nothing to share.
     */
    fun share(activity: Activity, runId: String, jsonl: String): Boolean {
        if (jsonl.isEmpty() || jsonl.length > MAX_SHARE_CHARS) return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Mika 运行记录 $runId")
            putExtra(Intent.EXTRA_TEXT, jsonl)
        }
        return runCatching {
            activity.startActivity(Intent.createChooser(send, "导出 Mika 运行记录"))
            true
        }.getOrDefault(false)
    }
}
