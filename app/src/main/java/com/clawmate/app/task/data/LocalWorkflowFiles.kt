package com.clawmate.app.task.data

import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class WorkflowFileCategory(val directory: String, val title: String, val subtitle: String, val accent: String, val background: String) {
    CONFIG("configs", "配置", "Workflow JSON", "#276EF1", "#EEF4FF"),
    DAILY("daily-log", "日志", "Daily Log", "#148F73", "#EAF8F3"),
    RUN("runs", "运行文件", "Run outputs", "#C76800", "#FFF3E2"),
}

data class ManagedWorkflowFile(val category: WorkflowFileCategory, val relativePath: String, val name: String,
    val size: Long, val modifiedAt: Long)
data class WorkflowFilesSnapshot(val files: List<ManagedWorkflowFile> = emptyList(), val complete: Boolean = true,
    val error: String? = null)
data class WorkflowFilePreview(val text: String = "", val truncated: Boolean = false, val error: String? = null)

/** Read-only adapter for TaskPage's file manager; never opens paths outside app-private workflows. */
class LocalWorkflowFiles(private val base: File) {
    suspend fun list(): WorkflowFilesSnapshot = withContext(Dispatchers.IO) {
        val files = mutableListOf<ManagedWorkflowFile>()
        var complete = true
        var failed = false
        for (category in WorkflowFileCategory.values()) {
            try {
                val root = categoryRoot(category)
                val visited = mutableSetOf<String>()
                fun scan(directory: File, depth: Int) {
                    if (depth > 10 || files.size >= MAX_FILES) { complete = false; return }
                    val canonical = directory.canonicalFile
                    if (!inside(canonical, root) && canonical != root) { complete = false; return }
                    if (!visited.add(canonical.path)) return
                    val children = canonical.listFiles()
                    if (children == null) { if (canonical.exists()) failed = true; return }
                    for (child in children) {
                        if (files.size >= MAX_FILES) { complete = false; break }
                        val safe = child.canonicalFile
                        if (!inside(safe, root)) { complete = false; continue }
                        if (safe.isDirectory) scan(safe, depth + 1)
                        else if (safe.isFile) files.add(ManagedWorkflowFile(category,
                            category.directory + "/" + safe.relativeTo(root).invariantSeparatorsPath,
                            safe.relativeTo(root).invariantSeparatorsPath, safe.length(), safe.lastModified()))
                    }
                }
                if (root.exists()) scan(root, 0)
            } catch (_: Exception) { failed = true }
        }
        WorkflowFilesSnapshot(files.sortedWith(compareBy<ManagedWorkflowFile> { it.category.ordinal }.thenByDescending { it.modifiedAt }.thenBy { it.name }),
            complete = complete && !failed, error = if (failed) "部分目录无法读取，请重试" else null)
    }

    suspend fun read(file: ManagedWorkflowFile): WorkflowFilePreview = withContext(Dispatchers.IO) {
        try {
            val root = categoryRoot(file.category)
            require(file.relativePath.startsWith(file.category.directory + "/"))
            val resolved = File(File(base, "workflows"), file.relativePath).canonicalFile
            require(inside(resolved, root) && resolved.isFile)
            val bytes = ByteArray(MAX_PREVIEW_BYTES + 1)
            var count = 0
            resolved.inputStream().use { input ->
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    if (read == 0) break
                    count += read
                }
            }
            if ((0 until minOf(count, 1024)).any { bytes[it] == 0.toByte() })
                WorkflowFilePreview(error = "此文件为二进制，无法显示文本预览")
            else WorkflowFilePreview(String(bytes, 0, minOf(count, MAX_PREVIEW_BYTES), Charsets.UTF_8), count > MAX_PREVIEW_BYTES)
        } catch (_: Exception) { WorkflowFilePreview(error = "无法读取所选文件，请刷新列表") }
    }

    private fun categoryRoot(category: WorkflowFileCategory): File {
        val workspace = File(base, "workflows").canonicalFile
        require(inside(workspace, base.canonicalFile))
        val directory = File(workspace, category.directory).canonicalFile
        require(inside(directory, workspace))
        return directory
    }

    companion object {
        const val MAX_FILES = 4000
        const val MAX_PREVIEW_BYTES = 128_000
        private fun inside(file: File, parent: File) = file.path.startsWith(parent.path + File.separator)
        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
