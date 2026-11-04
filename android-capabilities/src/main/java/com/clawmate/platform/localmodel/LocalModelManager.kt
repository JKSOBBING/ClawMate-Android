package com.clawmate.platform.localmodel

import java.io.File

/**
 * 已下载模型的扫描、切换与删除（只操作应用私有 `filesDir/modelscope/{owner}__{repo}`）。
 *
 * 与 Compose 基线的 `LocalModelInventory` 保持同样的判定口径（`config.json` + 权重文件），
 * 但本类自包含，放在 `:android-capabilities` 里供任一宿主复用，不反向依赖 app 模块。
 */
class LocalModelManager(private val filesDir: File) {

    companion object {
        /** 认可的权重扩展名。 */
        val WEIGHT_EXTENSIONS: List<String> = listOf(".mnn", ".om", ".safetensors", ".weight")
        const val ROOT_DIR = "modelscope"
        const val MANIFEST_FILE = ".download_manifest.json"
        const val COMPLETE_FILE = ".download_complete"
        const val MAX_SCAN_DEPTH = 4
    }

    /** 一个已发现（可能不完整）的本地模型目录。 */
    data class Model(
        val id: String,
        val label: String,
        val path: String,
        val sizeBytes: Long,
        val statusText: String,
        val ready: Boolean,
    )

    private val selectionFile: File get() = File(filesDir, "models/selected.json")

    fun selectedModelId(): String = runCatching { selectionFile.readText().trim() }.getOrDefault("")

    /** 扫描 `filesDir/modelscope/` 下的每个仓库目录；不完整的目录也会列出并标注原因。 */
    fun list(): List<Model> {
        val root = File(filesDir, ROOT_DIR)
        if (!root.isDirectory) return emptyList()
        val selected = selectedModelId()
        return (root.list() ?: emptyArray())
            .sorted()
            .mapNotNull { name -> runCatching { describe(File(root, name), name, selected) }.getOrNull() }
            .sortedBy { it.id }
    }

    private fun describe(dir: File, dirName: String, selected: String): Model? {
        if (!dir.isDirectory) return null
        val hasConfig = File(dir, "config.json").isFile
        val hasWeight = hasWeightFile(dir, 0)
        val complete = File(dir, COMPLETE_FILE).isFile
        val repo = decodeRepoDir(dirName)
        val status = when {
            !hasConfig && !hasWeight -> "缺失 config.json 与权重"
            !hasConfig -> "缺少 config.json"
            !hasWeight -> "缺少模型权重文件"
            !complete -> "文件齐全，但下载未完成（缺少完成标记）"
            else -> "下载完成"
        }
        val id = "modelscope:$repo"
        return Model(
            id = id,
            label = (if (id == selected) "★ " else "") + repo,
            path = dir.absolutePath,
            sizeBytes = dirSize(dir),
            statusText = status,
            ready = hasConfig && hasWeight && complete,
        )
    }

    /** 选择模型：只允许已经完整下载的目录，并把选择记在本机。 */
    fun select(modelId: String): Model {
        val model = list().firstOrNull { it.id == modelId } ?: throw IllegalArgumentException("模型不存在或尚未扫描到: $modelId")
        check(model.ready) { "模型尚未下载完成，暂不可加载" }
        selectionFile.parentFile?.mkdirs()
        selectionFile.writeText(model.id)
        return model
    }

    /** 删除模型目录；路径必须仍在 `filesDir/modelscope/` 内，且目录里确实有模型内容。 */
    fun delete(modelId: String, downloadRunning: Boolean = false): Boolean {
        check(!downloadRunning) { "下载进行中，暂不能删除模型文件" }
        val model = list().firstOrNull { it.id == modelId } ?: throw IllegalArgumentException("模型不存在: $modelId")
        val dir = File(model.path)
        val root = File(filesDir, ROOT_DIR).canonicalFile
        val target = dir.canonicalFile
        val relative = target.relativeTo(root).path.replace('\\', '/')
        check(relative.isNotEmpty() && !relative.startsWith("..")) { "不是可删除的模型目录" }
        check(dir.isDirectory) { "不是可删除的模型目录" }
        check(File(dir, "config.json").isFile || File(dir, COMPLETE_FILE).isFile || File(dir, MANIFEST_FILE).isFile ||
            hasWeightFile(dir, 0)) { "目录中没有模型文件，已拒绝删除" }
        dir.walkBottomUp().toList().forEach { entry ->
            if (entry.isFile) { check(entry.delete() || !entry.exists()) { "删除失败：${entry.name}" } } else entry.delete()
        }
        check(!dir.exists()) { "部分文件未能删除，请稍后重试" }
        if (selectedModelId() == modelId) selectionFile.delete()
        return true
    }

    private fun hasWeightFile(dir: File, depth: Int): Boolean {
        if (depth > MAX_SCAN_DEPTH) return false
        return (dir.listFiles() ?: emptyArray()).any { child ->
            if (child.isFile) child.length() > 0 && WEIGHT_EXTENSIONS.any { child.name.lowercase().endsWith(it) }
            else hasWeightFile(child, depth + 1)
        }
    }

    private fun dirSize(dir: File): Long = (dir.listFiles() ?: emptyArray()).sumOf { child ->
        if (child.isFile) child.length() else dirSize(child)
    }

    /** `owner__repo` → `owner/repo`。 */
    private fun decodeRepoDir(dir: String): String {
        val index = dir.indexOf("__")
        return if (index <= 0 || index + 2 >= dir.length) dir else dir.substring(0, index) + "/" + dir.substring(index + 2)
    }
}
