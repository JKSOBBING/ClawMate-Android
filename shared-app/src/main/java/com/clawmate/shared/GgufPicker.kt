package com.clawmate.shared

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.clawmate.platform.localmodel.LocalModelManager
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** Uses the temporary SAF read grant only while copying the selected model into private storage. */
internal class GgufPicker(private val activity: Activity, private val models: LocalModelManager) : Closeable {
    private class Import {
        val id = UUID.randomUUID().toString()
        val signal = CancellationSignal()
        var state = "running"
        var modelId = ""
        var error = ""
        var publishing = false
        var stream: InputStream? = null
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gguf-import") }
    private var current: Import? = null
    private var awaitingResult: Import? = null
    private var closed = false

    fun start(): String {
        val job = synchronized(this) {
            check(!closed && current?.state != "running" && awaitingResult == null) { "模型导入尚未结束" }
            Import().also { current = it; awaitingResult = it }
        }
        activity.runOnUiThread {
            synchronized(this) {
                if (job.state != "running") {
                    if (awaitingResult === job) awaitingResult = null
                } else {
                    try {
                        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                        }, REQUEST_IMPORT)
                    } catch (failure: Exception) {
                        awaitingResult = null
                        fail(job, failure)
                    }
                }
            }
        }
        return job.id
    }

    @Synchronized
    fun poll(id: String): String {
        val job = current?.takeIf { it.id == id }
        return JSONObject().put("state", job?.state ?: "error").put("modelId", job?.modelId.orEmpty())
            .put("error", job?.error ?: "导入任务已结束，请重新选择文件").toString()
    }

    @Synchronized
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_IMPORT) return false
        val job = awaitingResult ?: return true
        awaitingResult = null
        if (job.state != "running") return true
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            job.state = "cancelled"
        } else if (uri.scheme != "content") {
            fail(job, IllegalArgumentException("请选择系统文件选择器中的 GGUF 文档"))
        } else {
            worker.execute { copy(job, uri) }
        }
        return true
    }

    private fun copy(job: Import, uri: Uri) {
        try {
            val resolver = activity.contentResolver
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            var name = "model.gguf"
            var size = -1L
            resolver.query(uri, columns, null, null, null, job.signal)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
            val descriptor = resolver.openAssetFileDescriptor(uri, "r", job.signal)
                ?: throw IllegalStateException("无法读取所选文件")
            val modelId = descriptor.use {
                it.createInputStream().use { stream ->
                    synchronized(this) {
                        checkActive(job)
                        job.stream = stream
                    }
                    models.importGguf(name, stream, size, { checkActive(job) }) {
                        synchronized(this) {
                            checkActive(job)
                            job.publishing = true
                        }
                    }
                }
            }
            synchronized(this) {
                job.modelId = modelId
                job.state = "done"
            }
        } catch (failure: Exception) {
            synchronized(this) { if (job.state == "running") fail(job, failure) }
        } finally {
            synchronized(this) { job.stream = null }
        }
    }

    @Synchronized
    private fun checkActive(job: Import) {
        if (job.state != "running") throw CancellationException("已取消导入")
    }

    private fun fail(job: Import, failure: Exception) {
        job.error = failure.localizedMessage?.takeIf { it.isNotBlank() }?.take(240) ?: "导入 GGUF 文件失败"
        job.state = "error"
    }

    fun cancel(id: String): Boolean {
        val job = synchronized(this) {
            val active = current
            if (active == null || active.id != id || active.state != "running" || active.publishing) return false
            active.state = "cancelled"
            active
        }
        job.signal.cancel()
        val stream = synchronized(this) { job.stream }
        runCatching { stream?.close() }
        activity.runOnUiThread {
            if (synchronized(this) { awaitingResult === job }) activity.finishActivity(REQUEST_IMPORT)
        }
        return true
    }

    override fun close() {
        val id = synchronized(this) { closed = true; current?.id }
        if (id != null) cancel(id)
        worker.shutdown()
    }

    private companion object {
        const val REQUEST_IMPORT = 7225
    }
}
