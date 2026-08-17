package com.clawmate.app.datamanager

import java.io.File

/**
 * Bounded, atomic JSON file store for the Phase-30 local consolidation/index
 * artifacts. Writes go to a temp sibling that is renamed into place so a crash
 * mid-write never leaves a truncated artifact at the final path; reads tolerate a
 * missing or corrupt file and recover to a defensive default. All writes are
 * size-bounded so a hostile artifact cannot balloon the app-private storage.
 *
 * This is a pure `java.io.File` utility (no Android deps) so it is unit-testable
 * on the JVM over a temp dir.
 */
class AtomicJsonStore(
    private val maxFileBytes: Long = MAX_FILE_BYTES,
) {

    /**
     * Write [value] atomically to [target]. If [target] already exists it is
     * replaced; the parent directory is created only on write (never on read).
     * Returns the target on success, or null when serializing/writing fails (never
     * throws).
     */
    fun <T> writeJson(target: File, value: T, serializer: (T) -> String): File? {
        val text = runCatching { serializer(value) }.getOrNull() ?: return null
        if (text.toByteArray().size.toLong() > maxFileBytes) return null
        return runCatching {
            target.absoluteFile.parentFile?.mkdirs()
            val temp = File(target.absoluteFile.parentFile, ".${target.name}.tmp")
            temp.writeText(text)
            // Atomic-ish replace on the same filesystem.
            if (target.exists()) {
                val backup = File(temp.parentFile, ".${target.name}.bak")
                moveReplace(temp, target, backup)
            } else {
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
            target
        }.getOrNull()
    }

    /** Read [target] as JSON with [deserialize]; returns null on missing/corrupt. */
    fun <T> readJson(target: File, deserialize: (String) -> T?): T? {
        val file = target.absoluteFile
        if (!file.isFile || file.length() > maxFileBytes) return null
        return runCatching {
            val text = file.readText()
            deserialize(text)
        }.getOrNull()
    }

    private fun moveReplace(temp: File, target: File, backup: File) {
        if (target.renameTo(backup)) {
            val ok = temp.renameTo(target)
            if (ok) backup.delete() else backup.renameTo(target)
        } else {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        /** 4 MiB defensive cap on any single local artifact. */
        const val MAX_FILE_BYTES: Long = 4L * 1024L * 1024L
    }
}
