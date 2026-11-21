package com.clawmate.platform.localmodel

import com.clawmate.platform.localmodel.download.ModelScopeSpec

/**
 * 模型下载“计划”的只读说明：不联网、不写盘，只把默认仓库、来源域名、目标目录和
 * “哪些文件会被下载”的规则说明清楚，供应用内核对与真机验证使用。
 */
object LocalModelPlan {
    const val SOURCE_NAME = "ModelScope（modelscope.cn，HTTPS）"

    fun source(): String = SOURCE_NAME

    fun build(repoRaw: String, includeAuxiliary: Boolean): Map<String, Any?> {
        val parts = ModelScopeSpec.parseRepo(repoRaw)
        val result = LinkedHashMap<String, Any?>()
        result["source"] = SOURCE_NAME
        result["repo"] = repoRaw.trim()
        result["includeAuxiliary"] = includeAuxiliary
        if (parts == null) {
            result["ok"] = false
            result["error"] = "仓库格式应为 owner/repo，例如 " + ModelScopeSpec.DEFAULT_REPO
            return result
        }
        val (owner, repo) = parts
        result["ok"] = true
        result["fileListUrl"] = ModelScopeSpec.fileListUrl(owner, repo)
        result["targetDir"] = ModelScopeSpec.ROOT_DIR + "/" + ModelScopeSpec.encodeRepoDir(owner, repo)
        result["selection"] = if (includeAuxiliary) "全部文件（含视觉/OM 子目录）"
        else "GGUF 仓库只下载唯一的单文件语言模型。其他模型按 config.json 选择模型、分词与嵌入文件，默认跳过视觉与 OM 子目录"
        result["checks"] = listOf(
            "文件清单逐项校验路径（拒绝 ..、绝对路径、空段）与大小上限",
            "写入前检查剩余空间，不足直接失败并说明所需/可用大小",
            "断点续传使用 Range，服务端不支持时重下该文件",
            "完成后按清单大小与 SHA256 校验，全部通过才写 .download_complete",
            "取消会删除本仓库已下载目录；暂停保留断点"
        )
        return result
    }
}
