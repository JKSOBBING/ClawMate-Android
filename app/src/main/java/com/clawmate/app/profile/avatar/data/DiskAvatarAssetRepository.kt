package com.clawmate.app.profile.avatar.data

import android.content.Context
import android.graphics.BitmapFactory
import com.clawmate.app.profile.avatar.data.asset.AvatarAssetLimits
import com.clawmate.app.profile.avatar.data.asset.AvatarAssetStateModel
import com.clawmate.app.profile.avatar.data.asset.AvatarAssetStorageLogic
import com.clawmate.app.profile.avatar.data.asset.AvatarClearResult
import com.clawmate.app.profile.avatar.data.asset.AvatarImportResult
import com.clawmate.app.profile.avatar.data.asset.AvatarVersionRecord
import com.clawmate.app.profile.avatar.domain.AvatarGenerationMode
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * App-private, file-backed [AvatarAssetRepository].
 *
 * Layout mirrors the Harmony source `avatar/` root under the app's private
 * files dir:
 *   <files>/avatar/state.json                  - current [AvatarAssetStateModel]
 *   <files>/avatar/version-index.json          - ordered version-id index
 *   <files>/avatar/versions/<id>.json          - one [AvatarVersionRecord] each
 *   <files>/avatar/assets/<id>.<ext>           - imported local image bytes
 *   <files>/avatar/assets/reference-*          - transient reference photos
 *
 * Bounded file handling:
 *  - imports are byte-capped ([AvatarAssetLimits.DEFAULT_MAX_IMPORT_BYTES])
 *    and validated to actually decode (android BitmapFactory bounds) before any
 *    write — unmatched or corrupt input is rejected, never persisted;
 *  - reads tolerate a missing/malformed payload and fall back to the honest
 *    empty state / empty list;
 *  - the newest imported avatar is written atomically (temp file + rename) and
 *    stale extra reference-photo files are removed (mirrors the .ets policy).
 *
 * No cloud call, no API key, no image synthesis, no NFC/adb/hdc. Nothing is
 * ever fabricated.
 */
class DiskAvatarAssetRepository(
    private val context: Context,
    private val maxImportBytes: Int = AvatarAssetLimits.DEFAULT_MAX_IMPORT_BYTES,
) : AvatarAssetRepository {

    private val avatarRoot: File
        get() = File(context.filesDir, "avatar")
    private val assetsRoot: File
        get() = File(avatarRoot, "assets")
    private val versionsRoot: File
        get() = File(avatarRoot, "versions")
    private val stateFile: File
        get() = File(avatarRoot, "state.json")
    private val versionIndexFile: File
        get() = File(avatarRoot, "version-index.json")

    private val saveSequence = AtomicLong(System.currentTimeMillis() % 100_000)

    // FileIo seam operates on paths *relative to the avatar root*, so the pure
    // logic and the production store agree on "assets/<id>.ext" locations.
    private val io = object : AvatarAssetStorageLogic.FileIo {
        override fun list(dirRelative: String): List<String> {
            val dir = File(avatarRoot, dirRelative)
            return dir.list()?.sorted() ?: emptyList()
        }
        override fun exists(relativePath: String): Boolean = File(avatarRoot, relativePath).exists()
        override fun delete(relativePath: String): Boolean = File(avatarRoot, relativePath).delete()
        override fun sizeOf(relativePath: String): Long =
            File(avatarRoot, relativePath).takeIf { it.isFile }?.length() ?: 0L
    }

    override suspend fun listVersions(): List<AvatarVersionRecord> = runCatching {
        val records = readVersionIndexIds()
            .mapNotNull { id -> readVersion(id) }
            .toMutableList()
        // Also include version JSON files that are not (yet) in the index — the
        // .ets `listVersions` embraces both sources for robustness.
        io.list("versions")
            .filter { it.endsWith(".json") }
            .forEach { name ->
                val id = name.removeSuffix(".json")
                if (records.none { it.versionId == id }) readVersion(id)?.let { records.add(it) }
            }
        records.distinctBy { it.versionId }
    }.getOrDefault(emptyList()).let(AvatarAssetStorageLogic::versionsNewestFirst)

    override suspend fun loadState(): AvatarAssetStateModel = runCatching {
        readJson(stateFile)
    }.getOrElse { AvatarAssetStorageLogic.emptyState() }
        .let(AvatarAssetStorageLogic::normalizeState)

    override suspend fun currentVersion(): AvatarVersionRecord? {
        val state = loadState()
        if (state.currentVersionId.isBlank()) return null
        return findVersion(state.currentVersionId)
    }

    override suspend fun importLocalImage(
        bytes: ByteArray,
        displayName: String,
        sourceMimeType: String,
    ): AvatarImportResult {
        // Bounded input gate #1 — byte cap before anything is buffered further.
        if (bytes.isEmpty()) {
            return AvatarImportResult(loadState(), errorMessage = "所选图片为空")
        }
        if (bytes.size > maxImportBytes) {
            return AvatarImportResult(
                loadState(),
                errorMessage = "所选图片过大（超过 ${maxImportBytes / 1024} KB 限制）",
            )
        }
        // Bounded validation gate #2 — must actually decode as a real image.
        val decode = decodeBounds(bytes)
        if (!decode.valid) {
            return AvatarImportResult(loadState(), errorMessage = "所选文件不是可识别的图片")
        }

        val extension = AvatarAssetStorageLogic.referencePhotoExtension(sourceMimeType)
        val versionId = "local-$saveSequence.incrementAndGet()"
        val now = nowIsoLocal()
        val relativeImagePath = AvatarAssetStorageLogic.relativeAssetPath(versionId, extension)

        val state = loadState()
        val record = AvatarVersionRecord(
            versionId = versionId,
            createdAt = now,
            imageRelativePath = relativeImagePath,
            mode = AvatarGenerationMode.IMAGE_TO_IMAGE,
            provider = "local-import",
            model = "n/a",
            promptSnapshot = displayName.trim(),
            profileSnapshotHash = state.profileSnapshotHash,
        )
        if (AvatarAssetStorageLogic.isRecordOversized(record)) {
            return AvatarImportResult(state, errorMessage = "导入元数据超出安全上限")
        }

        if (!writeBytesAtomic(resolveAssetRelative(relativeImagePath), bytes)) {
            return AvatarImportResult(state, errorMessage = "导入失败：无法写入本地存储")
        }
        writeVersion(record)
        writeVersionIndex(record.versionId)

        val newState = AvatarAssetStorageLogic.stateAfterImport(state, record)
        writeJson(stateFile, newState)
        return AvatarImportResult(newState, record)
    }

    override suspend fun persistGeneratedImage(
        bytes: ByteArray,
        outputFormat: String,
        mode: com.clawmate.app.profile.avatar.domain.AvatarGenerationMode,
        model: String,
        prompt: String,
        profileHash: String,
    ): AvatarImportResult {
        // Bounded + validated local asset result path: empty/oversized bytes are
        // rejected before any write; the bytes come from a real provider.
        if (bytes.isEmpty()) {
            return AvatarImportResult(loadState(), errorMessage = "生成结果为空")
        }
        if (bytes.size > maxImportBytes) {
            return AvatarImportResult(
                loadState(),
                errorMessage = "生成图片过大（超过 ${maxImportBytes / 1024} KB 限制）",
            )
        }
        val extension = AvatarAssetStorageLogic.assetExtension(outputFormat)
        val versionId = "seedream-${saveSequence.incrementAndGet()}"
        val now = nowIsoLocal()
        val relativeImagePath = AvatarAssetStorageLogic.relativeAssetPath(versionId, extension)
        val state = loadState()
        val record = AvatarAssetStorageLogic.generatedVersionRecord(
            versionId = versionId,
            createdAt = now,
            relativeImagePath = relativeImagePath,
            mode = mode,
            model = model,
            prompt = prompt,
            profileHash = profileHash,
        )
        if (AvatarAssetStorageLogic.isRecordOversized(record)) {
            return AvatarImportResult(state, errorMessage = "版本元数据超出安全上限")
        }
        if (!writeBytesAtomic(resolveAssetRelative(relativeImagePath), bytes)) {
            return AvatarImportResult(state, errorMessage = "保存失败：无法写入本地存储")
        }
        writeVersion(record)
        writeVersionIndex(record.versionId)
        val newState = AvatarAssetStorageLogic.stateAfterImport(state, record)
        writeJson(stateFile, newState)
        return AvatarImportResult(newState, record)
    }

    override suspend fun deleteVersion(versionId: String): Boolean {
        if (versionId.isBlank()) return false
        val ids = readVersionIndexIds().filter { it != versionId }
        writeVersionIndexes(ids)
        io.delete("versions/$versionId.json")
        io.delete("assets/$versionId.jpg")
        io.delete("assets/$versionId.png")
        io.delete("assets/$versionId.webp")
        // Re-point current state away from a deleted version.
        val state = loadState()
        if (state.currentVersionId == versionId) {
            val next = readVersionIndexIds().firstOrNull()?.let { findVersion(it) }
            val updated = if (next == null) {
                AvatarAssetStorageLogic.emptyState()
            } else {
                AvatarAssetStorageLogic.stateAfterImport(state, next)
            }
            writeJson(stateFile, updated)
        }
        return true
    }

    override suspend fun clearAvatar(): AvatarClearResult {
        val vRoot = versionsRoot
        vRoot.mkdirs()
        val removed = vRoot.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { readVersion(it.name.removeSuffix(".json"))?.versionId }
            ?.count()
            ?: 0
        vRoot.listFiles()?.forEach { it.delete() }
        assetsRoot.listFiles()?.forEach { it.delete() }
        stateFile.delete()
        versionIndexFile.delete()
        return AvatarClearResult(AvatarAssetStorageLogic.emptyState(), clearedVersionCount = removed)
    }

    override fun absoluteAssetPath(relativePath: String): String? {
        val rel = relativePath.trim()
        if (rel.isBlank()) return null
        return File(avatarRoot, rel).absolutePath
    }

    // --- JSON helpers -------------------------------------------------------

    private fun writeVersion(record: AvatarVersionRecord): Boolean {
        val file = File(versionsRoot, "${record.versionId}.json")
        val payload = json(record)
        return writeBytesToFile(file, payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun readVersion(versionId: String): AvatarVersionRecord? {
        val file = File(versionsRoot, "$versionId.json")
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText(StandardCharsets.UTF_8)
            parseVersion(text)
        }.getOrNull()
    }

    private fun findVersion(versionId: String): AvatarVersionRecord? =
        readVersion(versionId) ?: readVersionIndexIds().firstOrNull { it == versionId }?.let(::readVersion)

    private fun readVersionIndexIds(): List<String> {
        val file = versionIndexFile
        if (!file.isFile) return emptyList()
        return runCatching { parseIdIndex(file.readText(StandardCharsets.UTF_8)) }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
    }

    private fun writeVersionIndex(newId: String) {
        writeVersionIndexes(AvatarAssetStorageLogic.appendVersionId(readVersionIndexIds(), newId))
    }

    private fun writeVersionIndexes(ids: List<String>) {
        val body = ids.joinToString(",") { "\"${escape(it)}\"" }
        val payload = "{\"version_ids\":[$body]}"
        writeBytesToFile(versionIndexFile, payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun readJson(file: File): AvatarAssetStateModel {
        if (!file.isFile) return AvatarAssetStorageLogic.emptyState()
        return runCatching { parseState(file.readText(StandardCharsets.UTF_8)) }
            .getOrElse { AvatarAssetStorageLogic.emptyState() }
    }

    private fun writeJson(file: File, state: AvatarAssetStateModel): Boolean {
        val latest = state.status.name.lowercase(Locale.US)
        val bytes = (
            "{\"current_version_id\":\"${escape(state.currentVersionId)}\"," +
                "\"profile_snapshot_hash\":\"${escape(state.profileSnapshotHash)}\"," +
                "\"status\":\"$latest\"," +
                "\"last_error_code\":\"${escape(state.lastErrorCode)}\"," +
                "\"last_error_message\":\"${escape(state.lastErrorMessage)}\"," +
                "\"updated_at\":\"${escape(state.updatedAt)}\"," +
                "\"reference_photo_path\":\"${escape(state.referencePhotoPath)}\"}"
            ).toByteArray(StandardCharsets.UTF_8)
        return writeBytesToFile(file, bytes)
    }

    // --- parse / serialize (minimal, stable, defensive) ---------------------

    private fun parseState(text: String): AvatarAssetStateModel {
        val fields = splitFields(text)
        return AvatarAssetStateModel(
            currentVersionId = fields["current_version_id"].orEmpty(),
            profileSnapshotHash = fields["profile_snapshot_hash"].orEmpty(),
            status = com.clawmate.app.profile.avatar.domain.AvatarStatusToken.fromToken(fields["status"].orEmpty()),
            lastErrorCode = fields["last_error_code"].orEmpty(),
            lastErrorMessage = fields["last_error_message"].orEmpty(),
            updatedAt = fields["updated_at"].orEmpty(),
            referencePhotoPath = fields["reference_photo_path"].orEmpty(),
        )
    }

    private fun parseVersion(text: String): AvatarVersionRecord {
        val fields = splitFields(text)
        return AvatarVersionRecord(
            versionId = fields["version_id"].orEmpty(),
            createdAt = fields["created_at"].orEmpty(),
            imageRelativePath = fields["image_relative_path"].orEmpty(),
            thumbnailRelativePath = fields["thumbnail_relative_path"].orEmpty(),
            mode = AvatarGenerationMode.fromToken(fields["mode"].orEmpty()),
            provider = fields["provider"].orEmpty(),
            model = fields["model"].orEmpty(),
            promptSnapshot = fields["prompt_snapshot"].orEmpty(),
            profileSnapshotHash = fields["profile_snapshot_hash"].orEmpty(),
        )
    }

    private fun parseIdIndex(text: String): List<String> =
        parseStringArray(text, "version_ids")

    private fun json(record: AvatarVersionRecord): String =
        "{\"version_id\":\"${escape(record.versionId)}\"," +
            "\"created_at\":\"${escape(record.createdAt)}\"," +
            "\"image_relative_path\":\"${escape(record.imageRelativePath)}\"," +
            "\"thumbnail_relative_path\":\"${escape(record.thumbnailRelativePath)}\"," +
            "\"mode\":\"${record.mode.name.lowercase(Locale.US)}\"," +
            "\"provider\":\"${escape(record.provider)}\"," +
            "\"model\":\"${escape(record.model)}\"," +
            "\"prompt_snapshot\":\"${escape(record.promptSnapshot)}\"," +
            "\"profile_snapshot_hash\":\"${escape(record.profileSnapshotHash)}\"}"

    // Split a flat {"a":"b","c":"d"} JSON object into a map (defensive; does not
    // rely on a JSON parser so the pure/repository stays dependency-light and the
    // handful of fields we persist round-trip deterministically).
    private fun splitFields(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return out
        val body = trimmed.substring(1, trimmed.length - 1)
        var i = 0
        while (i < body.length) {
            i = skipWs(body, i)
            if (i >= body.length) break
            val (k, kEnd) = readString(body, i)
            i = skipWs(body, kEnd)
            if (i < body.length && body[i] == ':') i++
            i = skipWs(body, i)
            val (v, vEnd) = readScalar(body, i)
            out[k] = v
            i = vEnd
            if (i < body.length && body[i] == ',') i++
        }
        return out
    }

    private fun parseStringArray(text: String, key: String): List<String> {
        val fields = splitFields(text)
        val raw = fields[key].orEmpty()
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < raw.length) {
            i = skipWs(raw, i)
            if (i < raw.length && raw[i] == '[') i++
            i = skipWs(raw, i)
            if (i >= raw.length || raw[i] != '"') break
            val (v, end) = readString(raw, i)
            out.add(v)
            i = skipWs(raw, end)
            if (i < raw.length && raw[i] == ']') break
            if (i < raw.length && raw[i] == ',') i++
        }
        return out
    }

    private fun readScalar(text: String, start: Int): Pair<String, Int> {
        var i = skipWs(text, start)
        if (i >= text.length) return "" to i
        return when (text[i]) {
            '"' -> readString(text, i)
            '[' -> {
                // consume the whole array literal as one scalar for scalar-ish keys
                var j = i + 1
                var depth = 1
                while (j < text.length && depth > 0) {
                    when (text[j]) {
                        '[' -> depth++
                        ']' -> depth--
                    }
                    j++
                }
                text.substring(i, j) to j
            }
            else -> {
                var j = i
                while (j < text.length && text[j] != ',' && text[j] != '}') j++
                text.substring(i, j).trim() to j
            }
        }
    }

    private fun readString(text: String, start: Int): Pair<String, Int> {
        var i = start
        if (i >= text.length || text[i] != '"') return "" to i
        i++
        val sb = StringBuilder()
        while (i < text.length && text[i] != '"') {
            if (text[i] == '\\' && i + 1 < text.length) {
                val n = text[i + 1]
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    else -> {
                        sb.append('\\'); sb.append(n)
                    }
                }
                i += 2
            } else {
                sb.append(text[i]); i++
            }
        }
        return sb.toString() to (i + 1)
    }

    private fun skipWs(text: String, i: Int): Int {
        var j = i
        while (j < text.length && (text[j] == ' ' || text[j] == '\n' || text[j] == '\t' || text[j] == '\r')) j++
        return j
    }

    private fun escape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    // --- atomic file IO -----------------------------------------------------

    private fun writeBytesAtomic(file: File, bytes: ByteArray): Boolean =
        writeBytesToFile(file, bytes)

    private fun writeBytesToFile(file: File, bytes: ByteArray): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp" + System.nanoTime())
        tmp.apply {
            writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                // rename can fail on some providers; fall back to direct write only if allowed
                if (!file.exists() || file.delete()) file.writeBytes(bytes)
            }
        }
        true
    }.getOrDefault(false)

    private fun resolveAssetRelative(relativePath: String): File = File(avatarRoot, relativePath)

    private fun decodeBounds(bytes: ByteArray): DecodeResult {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return DecodeResult(false, 0, 0)
        if (w < AvatarAssetLimits.MIN_DECODE_DIMENSION || h < AvatarAssetLimits.MIN_DECODE_DIMENSION) {
            return DecodeResult(false, w, h)
        }
        if (w > AvatarAssetLimits.MAX_IMPORT_DIMENSION || h > AvatarAssetLimits.MAX_IMPORT_DIMENSION) {
            return DecodeResult(false, w, h)
        }
        return DecodeResult(true, w, h)
    }

    private data class DecodeResult(val valid: Boolean, val width: Int, val height: Int)

    private fun nowIsoLocal(): String =
        java.time.LocalDateTime.now().toString().replace("T", " ")
}
