package com.clawmate.platform

import java.io.File
import java.net.URI

/** Image inputs cross a UI/runtime boundary; remote URLs and private non-image stores are refused. */
object LocalImageAccess {
    fun validate(value: String, privateRoots: List<File>): String {
        require(value.length in 1..8192) { "图片 URI 无效" }
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("图片 URI 无效") }
        if (uri.scheme == "content") {
            require(!uri.authority.isNullOrBlank() && uri.userInfo == null) { "内容 URI 无效" }
            return value // ContentResolver still enforces the system's actual read grant.
        }
        require(uri.scheme == "file" && uri.authority.isNullOrEmpty() && uri.query == null && uri.fragment == null) {
            "本地识别只接受系统选图 URI 或应用私有图片"
        }
        val file = runCatching { File(uri).canonicalFile }.getOrElse { throw IllegalArgumentException("图片路径无效") }
        require(privateRoots.any { file.toPath().startsWith(it.canonicalFile.toPath()) && file != it.canonicalFile }) {
            "图片不在应用可用目录中，请重新选择"
        }
        return file.toURI().toString()
    }
}
