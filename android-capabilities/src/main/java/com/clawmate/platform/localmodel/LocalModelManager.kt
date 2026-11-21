package com.clawmate.platform.localmodel

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * 管理应用私有目录里的 ModelScope 模型包和单文件 GGUF。两个来源共用持久化选择。
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
        private val catalogLock = Any()
    }

    /** 一个已发现（可能不完整）的本地模型目录。 */
    data class Model(
        val id: String,
        val label: String,
        val path: String,
        val sizeBytes: Long,
        val statusText: String,
        val ready: Boolean,
        val runnable: Boolean = false,
    )

    private val gguf = GgufFiles(filesDir)
    private val packageRoot = File(filesDir.canonicalFile, ROOT_DIR)
    private val selectionFile: File get() = File(filesDir, "models/selected.json")

    fun selectedModelId(): String = runCatching { selectionFile.readText().trim() }.getOrDefault("")

    fun list(): List<Model> = synchronized(catalogLock) {
        val selected = selectedModelId()
        val downloaded = packages().map { dir -> describe(dir, selected) }
        (downloaded + gguf.models(selected)).sortedBy { it.id }
    }

    /** 只从应用私有导入目录或已完成的 ModelScope 包中解析模型。 */
    fun resolveGguf(modelId: String): File = synchronized(catalogLock) {
        if (modelId.startsWith(GgufFiles.PREFIX)) return@synchronized gguf.resolve(modelId)
        val dir = packages().singleOrNull { modelId == "modelscope:" + decodeRepoDir(it.name) }
        require(dir != null && isComplete(dir)) { "请选择已导入的 GGUF 模型或下载完整的单模型 GGUF 仓库" }
        requireNotNull(gguf.inPackage(regularFiles(dir, MAX_SCAN_DEPTH + 1))) { "此模型包没有可单独加载的唯一 GGUF 语言模型" }
    }

    /** 复制在调用线程进行。最后一次取消检查与选择提交之间由调用方的 beforePublish 协调。 */
    fun importGguf(name: String, source: InputStream, expectedBytes: Long = -1, checkActive: () -> Unit = {},
        beforePublish: () -> Unit = checkActive): String =
        gguf.import(name, source, expectedBytes, checkActive, beforePublish, catalogLock, ::writeSelection)

    private fun packages(): List<File> {
        if (packageRoot.canonicalFile != packageRoot) return emptyList()
        return packageRoot.listFiles().orEmpty().filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
    }

    private fun regularFiles(dir: File, depth: Int = Int.MAX_VALUE): Sequence<File> =
        dir.walkTopDown().maxDepth(depth).onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

    private fun isComplete(dir: File): Boolean = Files.isRegularFile(File(dir, COMPLETE_FILE).toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun describe(dir: File, selected: String): Model {
        val hasConfig = File(dir, "config.json").isFile
        val files = regularFiles(dir, MAX_SCAN_DEPTH + 1).toList()
        val hasWeight = files.any { file -> file.length() > 0 && WEIGHT_EXTENSIONS.any { file.name.endsWith(it, true) } }
        val complete = isComplete(dir)
        val hasGguf = files.any { it.name.endsWith(".gguf", ignoreCase = true) }
        val runnable = complete && hasGguf && gguf.inPackage(files.asSequence()) != null
        val repo = decodeRepoDir(dir.name)
        val status = when {
            runnable -> "下载完成 · GGUF · llama.cpp"
            hasGguf && !complete -> "GGUF 下载未完成"
            hasGguf && !hasWeight -> "请在设置中导入完整的单文件 GGUF 语言模型"
            !hasConfig && !hasWeight -> "缺失 config.json 与权重"
            !hasConfig -> "缺少 config.json"
            !hasWeight -> "缺少模型权重文件"
            !complete -> "文件齐全，但下载未完成（缺少完成标记）"
            files.any { it.name.endsWith(".mnn", ignoreCase = true) } ->
                "下载完成 · MNN 格式；本地文字聊天需导入同一模型的 GGUF 版本"
            else -> "下载完成"
        }
        val id = "modelscope:$repo"
        return Model(
            id = id,
            label = (if (id == selected) "★ " else "") + repo,
            path = dir.absolutePath,
            sizeBytes = regularFiles(dir).sumOf { it.length() },
            statusText = status,
            ready = runnable || (hasConfig && hasWeight && complete),
            runnable = runnable,
        )
    }

    /** 选择模型：只允许已经完整下载的目录，并把选择记在本机。 */
    fun select(modelId: String): Model = synchronized(catalogLock) {
        val model = list().firstOrNull { it.id == modelId } ?: throw IllegalArgumentException("模型不存在或尚未扫描到: $modelId")
        check(model.ready) { "模型尚未下载完成，暂不可加载" }
        writeSelection(model.id)
        model
    }

    private fun writeSelection(id: String) {
        val dir = selectionFile.parentFile!!
        check(dir.isDirectory || dir.mkdirs()) { "无法创建模型选择目录" }
        val pending = File.createTempFile(".selected-", ".tmp", dir)
        try {
            pending.writeText(id)
            Files.move(pending.toPath(), selectionFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }

    /** 删除模型目录；路径必须仍在 `filesDir/modelscope/` 内，且目录里确实有模型内容。 */
    fun delete(modelId: String, downloadRunning: Boolean = false): Boolean = synchronized(catalogLock) {
        if (modelId.startsWith(GgufFiles.PREFIX)) {
            gguf.remove(modelId)
            if (selectedModelId() == modelId) selectionFile.delete()
            return@synchronized true
        }
        check(!downloadRunning) { "下载进行中，暂不能删除模型文件" }
        val model = list().firstOrNull { it.id == modelId } ?: throw IllegalArgumentException("模型不存在: $modelId")
        val dir = File(model.path)
        val target = dir.canonicalFile
        check(target == dir && target.parentFile == packageRoot && dir.isDirectory) { "不是可删除的模型目录" }
        check(File(dir, "config.json").isFile || File(dir, COMPLETE_FILE).isFile || File(dir, MANIFEST_FILE).isFile ||
            regularFiles(dir, MAX_SCAN_DEPTH + 1).any { file ->
                file.length() > 0 && (file.name.endsWith(".gguf", true) || WEIGHT_EXTENSIONS.any { file.name.endsWith(it, true) })
            }) { "目录中没有模型文件，已拒绝删除" }
        // Files.walk does not follow symbolic links.
        Files.walk(target.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
        check(!dir.exists()) { "部分文件未能删除，请稍后重试" }
        if (selectedModelId() == modelId) selectionFile.delete()
        true
    }

    /** `owner__repo` → `owner/repo`。 */
    private fun decodeRepoDir(dir: String): String {
        val index = dir.indexOf("__")
        return if (index <= 0 || index + 2 >= dir.length) dir else dir.substring(0, index) + "/" + dir.substring(index + 2)
    }
}
