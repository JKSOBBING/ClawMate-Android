package com.clawmate.platform.localmodel

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Immutable single-file imports. The ID is the content digest, independent of the SAF URI or display name. */
internal class GgufFiles(filesDir: File) {
    private val root = File(filesDir.canonicalFile, "gguf")

    companion object {
        internal const val PREFIX = "gguf:"
        private val digestPattern = Regex("[0-9a-f]{64}")
        private val shardPattern = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)
        private val activeCopies = mutableSetOf<File>()

        fun isMainModelName(name: String): Boolean =
            name.endsWith(".gguf", ignoreCase = true) && !shardPattern.containsMatchIn(name) &&
                !name.startsWith("mmproj", ignoreCase = true)
    }

    fun models(selected: String): List<LocalModelManager.Model> = entries().map { file ->
        val id = PREFIX + file.name.substringBefore('-')
        val valid = hasHeader(file)
        LocalModelManager.Model(id, (if (id == selected) "★ " else "") + file.name.substringAfter('-'),
            file.absolutePath, file.length(), if (valid) "已导入 GGUF · llama.cpp" else "GGUF 文件已损坏",
            ready = valid, runnable = valid)
    }

    fun resolve(id: String): File {
        val file = find(id) ?: throw IllegalArgumentException("找不到导入的 GGUF 模型，请在模型设置中导入")
        require(hasHeader(file)) { "GGUF 文件已损坏，请重新导入" }
        return file
    }

    fun remove(id: String) {
        val file = find(id) ?: throw IllegalArgumentException("找不到导入的 GGUF 模型")
        Files.delete(file.toPath())
    }

    /** A completed ModelScope package may contain one primary GGUF and a separate vision projector. */
    fun inPackage(files: Sequence<File>): File? {
        val candidate = files.filter { isMainModelName(it.name) }.take(2).singleOrNull()
        return candidate?.takeIf { hasHeader(it) }
    }

    private fun find(id: String): File? {
        val hash = id.removePrefix(PREFIX)
        require(id.startsWith(PREFIX) && digestPattern.matches(hash)) { "请选择已导入的单文件 GGUF 模型" }
        return entries().firstOrNull { it.name.startsWith("$hash-") }
    }

    private fun entries(): List<File> {
        if (!root.isDirectory || root.canonicalFile != root) return emptyList()
        return root.listFiles().orEmpty().filter { file ->
            file.name.endsWith(".gguf", ignoreCase = true) && digestPattern.matches(file.name.substringBefore('-')) &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
    }

    fun import(name: String, source: InputStream, expectedBytes: Long, checkActive: () -> Unit,
        beforePublish: () -> Unit, catalogLock: Any, select: (String) -> Unit): String {
        val label = safeLabel(name)
        check(root.canonicalFile == root && (root.isDirectory || root.mkdirs() || root.isDirectory)) { "无法创建私有模型目录" }
        val pending = synchronized(activeCopies) {
            // Reclaim unfinished copies from an interrupted process before opening a new large file.
            root.listFiles { _, entry -> entry.startsWith(".incoming-") }?.forEach {
                if (it !in activeCopies && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)) it.delete()
            }
            val usable = root.usableSpace
            if (usable > 0 && expectedBytes > usable) throw IOException("存储空间不足以导入该模型")
            File.createTempFile(".incoming-", ".part", root).also { activeCopies.add(it) }
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(24)
            try {
                DataInputStream(source).readFully(header)
            } catch (truncated: EOFException) {
                throw IOException("GGUF 文件头不完整", truncated)
            }
            require(validHeader(header)) { "需要 GGUF v2 或 v3 的模型文件" }
            digest.update(header)
            var total = header.size.toLong()
            pending.outputStream().use { output ->
                output.write(header)
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    checkActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    total += count
                    require(expectedBytes < 0 || total <= expectedBytes) { "模型大小与选择的文件不符" }
                }
                require(total > header.size && (expectedBytes < 0 || total == expectedBytes)) { "GGUF 文件不完整" }
                output.fd.sync()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val id = PREFIX + hash
            synchronized(catalogLock) {
                beforePublish()
                val existing = find(id)
                val destination = existing ?: File(root, "$hash-$label")
                Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                try {
                    select(id)
                } catch (failure: Exception) {
                    if (existing == null) destination.delete()
                    throw failure
                }
            }
            return id
        } finally {
            synchronized(activeCopies) {
                pending.delete()
                activeCopies.remove(pending)
            }
        }
    }

    private fun safeLabel(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        require(name.endsWith(".gguf", ignoreCase = true)) { "请选择单文件 .gguf 语言模型" }
        require(isMainModelName(name)) {
            "请选择完整的单文件 GGUF 语言模型，分片和视觉投影需另行支持"
        }
        val base = name.dropLast(5).filter { it.isLetterOrDigit() || it in "-_. " }.trim().take(48)
        return base.ifEmpty { "model" } + ".gguf"
    }

    private fun hasHeader(file: File): Boolean = runCatching {
        if (file.length() <= 24) return false
        val header = ByteArray(24)
        DataInputStream(file.inputStream()).use { it.readFully(header) }
        validHeader(header)
    }.getOrDefault(false)

    private fun validHeader(header: ByteArray): Boolean {
        val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        // Little-endian magic "GGUF", version, tensor count, and metadata count.
        return bytes.int == 0x46554747 && bytes.int in 2..3 && bytes.long > 0 && bytes.long > 0
    }
}
