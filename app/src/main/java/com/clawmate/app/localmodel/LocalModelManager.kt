package com.clawmate.app.localmodel

import com.clawmate.app.localmodel.download.ModelScopeSpec
import java.io.File

/**
 * 已下载模型的切换与删除（纯文件操作，绑定到 `filesDir`）。
 *
 * - 只允许操作 `filesDir/modelscope/{owner}__{repo}` 与遗留的 `filesDir/model`；
 * - 路径必须落在 filesDir 内，且经过 [LocalModelPathValidator] 校验；
 * - 删除前确认目标是模型目录（含 config.json 或下载标记），避免误删其它数据；
 * - 下载进行中时拒绝删除，避免与写入冲突（由调用方传入 `busy`）。
 */
class LocalModelManager(private val filesDir: File) {

    /** 当前选中的模型 id；与鸿蒙端一致，保存在应用私有的小配置里。 */
    private val selectionFile: File get() = File(filesDir, "models/selected.json")

    fun selectedModelId(): String = runCatching { selectionFile.readText().trim() }.getOrDefault("")

    fun select(modelId: String): LocalModelInfo {
        val models = LocalModelInventory.scan(filesDir.absolutePath)
        val model = models.firstOrNull { it.id == modelId } ?: throw IllegalArgumentException("模型不存在或尚未扫描到: $modelId")
        if (!model.ready) throw IllegalStateException("模型尚未下载完成，暂不可加载")
        if (!LocalModelPathValidator.isValidModelDir(filesDir.absolutePath, model.path)) {
            throw IllegalArgumentException("模型路径无效")
        }
        selectionFile.parentFile?.mkdirs()
        selectionFile.writeText(model.id)
        return model
    }

    /** 删除某个模型目录与完成标记；下载标记不完整时也允许清理半成品。 */
    fun delete(modelId: String, busy: Boolean = false): Boolean {
        check(!busy) { "下载进行中，暂不能删除模型文件" }
        val models = LocalModelInventory.scan(filesDir.absolutePath)
        val model = models.firstOrNull { it.id == modelId } ?: throw IllegalArgumentException("模型不存在: $modelId")
        val dir = File(model.path)
        if (!LocalModelPathValidator.isValidModelDir(filesDir.absolutePath, model.path)) {
            throw IllegalArgumentException("模型路径无效")
        }
        // 二次确认目标确实是模型目录（顶层只允许 modelscope/<repo> 或 model）。
        val relative = dir.relativeTo(filesDir).path.replace('\\', '/')
        val allowed = relative.startsWith(ModelScopeSpec.ROOT_DIR + "/") || relative == "model"
        check(allowed && dir.isDirectory) { "不是可删除的模型目录" }
        val looksLikeModel = File(dir, "config.json").isFile || File(dir, ModelScopeSpec.COMPLETE_FILE).isFile ||
            File(dir, ModelScopeSpec.MANIFEST_FILE).isFile || LocalModelInventory.isModelDirectory(dir)
        check(looksLikeModel) { "目录中没有模型文件，已拒绝删除" }
        var deleted = 0
        dir.walkBottomUp().forEach { entry ->
            if (entry.isFile) { if (entry.delete()) deleted++ } else entry.delete()
        }
        if (dir.exists()) throw IllegalStateException("部分文件未能删除，请稍后重试")
        if (selectedModelId() == modelId) selectionFile.delete()
        return deleted >= 0
    }

    /** 扫描结果与选中状态；界面据此展示“已下载/可加载/未完整”。 */
    fun list(): List<LocalModelInfo> {
        val selected = selectedModelId()
        return LocalModelInventory.scan(filesDir.absolutePath).map { it.copy(label = if (it.id == selected) "★ " + it.label else it.label) }
    }
}
