package com.clawmate.app.localmodel

import java.io.File

/**
 * App-private model path validation.
 *
 * Model files must live under the application's private `filesDir` (mirroring
 * the HarmonyOS sandbox: `filesDir/model` for legacy models and
 * `filesDir/modelscope/{owner}__{repo}` for ModelScope repos). This validator
 * rejects anything that could escape or touch the outside filesystem — `..`
 * traversal, absolute paths outside `filesDir`, null bytes, directory
 * symlinks, hidden/temp dot-prefixed segments, and any path whose resolved,
 * normalized parent is not lexically inside `filesDir`.
 *
 * All helpers are pure and deterministic so the whole validation surface is
 * unit-testable without touching real storage. No function here deletes or
 * writes anything.
 */
object LocalModelPathValidator {

    /** Whitelisted top-level model folders under `filesDir`. */
    val ALLOWED_BASE_DIRS: List<String> = listOf("model", "modelscope")

    /** True when [path] is a non-empty, safe model-dir path under [filesDir]. */
    fun isValidModelDir(filesDir: String?, path: String?): Boolean {
        if (filesDir.isNullOrBlank() || path.isNullOrBlank()) return false
        if (path.contains('\u0000')) return false
        if (path == filesDir || path == filesDir.trimEnd('/') + "/") return false
        val normFiles = normalize(filesDir) ?: return false
        val normPath = normalize(path) ?: return false
        if (!normPath.startsWith(normFiles + "/")) return false
        val relative = normPath.substring(normFiles.length + 1)
        val segments = relative.split("/")
        if (segments.size < 1) return false
        val base = segments[0]
        if (base !in ALLOWED_BASE_DIRS) return false
        // Reject raw traversal / dot / dot-dot / dot-prefixed segments *in the
        // caller-supplied string* (before canonicalization could collapse them)
        // so `filesDir/model/./model` or `filesDir/model/../x` are never
        // accepted as a model dir even though the OS would resolve them within
        // filesDir.
        val raw = rawRelative(filesDir, path) ?: return false
        val rawSegments = raw.split("/")
        if (rawSegments.any { it == ".." || it == "." || it.isEmpty() }) return false
        if (rawSegments.any { it.startsWith(".") }) return false
        // no dot/empty segments in the canonical form either
        if (segments.any { it == ".." || it == "." || it.isEmpty() }) return false
        if (segments.any { it.startsWith(".") }) return false
        return true
    }

    /**
     * The literal path given by the caller with the filesDir prefix removed,
     * without any canonicalization. Returns null when [path] is not prefixed by
     * [filesDir] literally (i.e. it already escapes).
     */
    private fun rawRelative(filesDir: String, path: String): String? {
        val raw = separators(path).trimEnd('/')
        // Inventory paths may be host-absolute while filesDir was supplied in
        // Android form by a JVM caller. Preserve raw segments before resolving
        // them, so traversal and duplicate separators are still rejected.
        val bases = listOf(filesDir, File(filesDir).absolutePath)
        for (candidate in bases) {
            val base = separators(candidate).trimEnd('/')
            if (raw == base) return ""
            if (raw.startsWith(base + "/")) return raw.substring(base.length + 1)
        }
        return null
    }

    /** True when [path] is a *direct* child of `filesDir` (filesDir/model). */
    fun isLegacyModelDir(filesDir: String?, path: String?): Boolean {
        if (!isValidModelDir(filesDir, path)) return false
        val normFiles = normalize(filesDir) ?: return false
        val normPath = normalize(path) ?: return false
        val relative = normPath.substring(normFiles.length + 1)
        return !relative.contains("/")
    }

    /** Resolve a relative model id/folder into a validated absolute path, or
     *  null when the id would escape `filesDir`. Only safe ids are resolved. */
    fun resolveModelDir(filesDir: String?, id: String?): String? {
        if (filesDir.isNullOrBlank() || id.isNullOrBlank()) return null
        if (id.contains('\u0000')) return null
        val clean = id.trim().trim('/')
        if (clean.isEmpty() || clean == ".." || clean.startsWith("..") || clean.contains("../")) return null
        val candidate = File(filesDir, clean).path
        // Validate the literal path before canonicalization removes dot parts.
        return if (isValidModelDir(filesDir, candidate)) normalize(candidate) else null
    }

    /**
     * Reject any configured path that is a symlink or directory inside the
     * whitelist but points outside — used to harden the load/test path. When
     * real files are not available (unit tests) callers pass a directory
     * listing seam; here we check only the pieces we can without I/O.
     */
    fun hasEscapingSegment(relative: String?): Boolean {
        if (relative.isNullOrBlank()) return false
        val segments = relative.split("/")
        return segments.any { it == ".." || it == "." || it.isEmpty() || it.startsWith(".") }
    }

    private fun normalize(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val p = path.trimEnd('/')
        if (p.isEmpty()) return null
        if (p.contains('\u0000')) return null
        val canonical = runCatching { File(p).canonicalPath }.getOrElse { File(p).absolutePath }
        // canonicalPath may resolve symlinks; re-verify it stays under filesDir.
        return separators(canonical).trimEnd('/')
    }

    private fun separators(path: String): String = path.replace(File.separatorChar, '/')
}
