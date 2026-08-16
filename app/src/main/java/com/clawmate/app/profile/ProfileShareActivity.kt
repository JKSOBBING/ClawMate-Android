package com.clawmate.app.profile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.clawmate.app.profile.avatar.data.DataStoreProfileAvatarRepository
import com.clawmate.app.profile.avatar.domain.localProfileCompleteness
import com.clawmate.app.profile.share.MAX_RECEIVED_PROFILE_BYTES
import com.clawmate.app.profile.share.ProfileShareErrorCode
import com.clawmate.app.profile.share.ProfileShareImporter
import com.clawmate.app.profile.share.ProfileShareParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Profile share entry point — receives `ACTION_VIEW` / `ACTION_SEND` intents
 * for `.mobi-profile` / `application/vnd.mobiinfra.profile+json` summary files
 * (the "profile knock share" flow). On modern Android the URI is a `content://`
 * URI; it is read through [android.content.ContentResolver].
 *
 * HarmonyOS source (reference, not copied): ShareKit knock-share + file-open
 * wants handled in
 *   entry/src/main/ets/utils/ProfileKnockShareService.ets
 *   entry/src/main/ets/pages/Index.ets (buildProfileShareSnapshot)
 *
 * Phase 1 shipped the intent shell. **Phase 17 (this)** implements the portable
 * import/merge pipeline around it:
 *  - the shared file is read through the ContentResolver with a **byte cap** so
 *    an oversized file is rejected without being buffered unboundedly;
 *  - the payload is parsed + validated ([ProfileShareImporter]) rejecting
 *    **malformed / oversized / unsupported-version / missing-field** inputs;
 *  - only the allowed local persona-domain summaries are merged into the
 *    device-persisted local avatar profile with deterministic **local-first
 *    precedence** (blank local fields are filled; existing local values are
 *    never overwritten);
 *  - an **honest success/error result** is show to the user.
 *
 * No NFC, no cloud affinity analysis, no secrets, no fake capability.
 */
class ProfileShareActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: DataStoreProfileAvatarRepository

    private var activeDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Semantic window only for the result dialog; the screen itself is empty.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        repository = DataStoreProfileAvatarRepository(applicationContext)

        val uri = resolveContentUri(intent)
        if (uri == null) {
            Log.w(TAG, "Profile share intent received without a resolvable content URI")
            showResult(
                ImportResult.Error(
                    ProfileShareErrorCode.MISSING_FIELD,
                    "未找到可导入的画像文件(缺少内容 URI)",
                ),
            )
            return
        }
        Log.i(TAG, "Profile share intent content URI: $uri")

        showProgress("正在读取并在设备内合并用户画像…")
        scope.launch {
            val result = runCatching { import(uri) }
                .getOrElse { thr ->
                    Log.e(TAG, "Profile import failed", thr)
                    ImportResult.Error(
                        ProfileShareErrorCode.MALFORMED,
                        "读取或解析画像文件失败：${thr.message}",
                    )
                }
            withContext(Dispatchers.Main) {
                activeDialog?.dismiss()
                showResult(result)
            }
        }
    }

    /** The import runs off the main thread; the data never leaves the device. */
    private suspend fun import(uri: Uri): ImportResult {
        val raw = readCapped(uri)
        if (raw == null) {
            return ImportResult.Error(
                ProfileShareErrorCode.OVERSIZED,
                "画像文件为空或超出大小上限($MAX_RECEIVED_PROFILE_BYTES B)",
            )
        }
        return when (val parsed = ProfileShareImporter.parseAndValidate(raw)) {
            is ProfileShareParseResult.Error ->
                ImportResult.Error(parsed.code, parsed.message)
            is ProfileShareParseResult.Success -> {
                val local = repository.snapshot()
                val outcome = ProfileShareImporter.mergeIntoLocal(local, parsed.envelope)
                val saved = repository.save(outcome.profile)
                ImportResult.Success(
                    importedDomains = outcome.importedDomains,
                    completeness = localProfileCompleteness(saved),
                )
            }
        }
    }

    /**
     * Read the shared URI as UTF-8 text while enforcing the import size cap, so
     * a huge / hostile file can never be buffered unboundedly. Returns null on
     * empty or unreadable content.
     */
    private fun readCapped(uri: Uri): String? {
        val sink = BoundedSink()
        val openResult = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (total < MAX_RECEIVED_PROFILE_BYTES) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val allowed = minOf(read, MAX_RECEIVED_PROFILE_BYTES - total)
                    sink.write(buffer, allowed)
                    total += allowed
                    if (allowed < read) break
                }
            }
        }
        openResult.onFailure { thr ->
            Log.e(TAG, "Failed to open/read content URI $uri", thr)
            return null
        }
        return sink.toUtf8()
    }

    private fun resolveContentUri(intent: Intent?): Uri? {
        intent?.data?.takeIf { it.scheme == "content" || it.scheme == "file" }?.let { return it }
        // Some senders deliver the body as an EXTRA_STREAM clip instead of data.
        intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { return it }
        intent?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                val u = clip.getItemAt(i)?.uri ?: continue
                if (u.scheme == "content" || u.scheme == "file") return u
            }
        }
        return null
    }

    private fun showProgress(message: String) {
        activeDialog?.dismiss()
        activeDialog = AlertDialog.Builder(this)
            .setTitle(getString(com.clawmate.app.R.string.profile_share_title))
            .setMessage(message)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun showResult(result: ImportResult) {
        val (title, message) = when (result) {
            is ImportResult.Error -> {
                val label = when (result.code) {
                    ProfileShareErrorCode.OVERSIZED -> "画像文件过大"
                    ProfileShareErrorCode.MALFORMED -> "画像文件格式错误"
                    ProfileShareErrorCode.UNSUPPORTED_VERSION -> "画像文件版本不受支持"
                    ProfileShareErrorCode.MISSING_FIELD -> "画像文件缺少必要字段"
                    ProfileShareErrorCode.UNRECOGNIZED -> "无法识别为 ClawMate 画像文件"
                }
                "导入失败" to "$label\n${result.message}"
            }
            is ImportResult.Success -> {
                val detail = if (result.importedDomains.isEmpty()) {
                    "对方画像中没有可合并的新内容。\n当前本地画像完整度：${result.completeness}%。"
                } else {
                    "已导入画像维度：${result.importedDomains.joinToString("、")}\n" +
                        "本地画像完整度更新为 ${result.completeness}%。\n" +
                        "(仅合并本地为空白的维度，已有内容不会被覆盖。)"
                }
                "导入成功" to detail
            }
        }
        activeDialog?.dismiss()
        activeDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (result is ImportResult.Error) "知道了" else "完成") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
            .also { it.show() }
    }

    /** Bounded in-memory sink so reading never exceeds the import cap. */
    private class BoundedSink {
        private val chunks = ArrayList<ByteArray>()
        private var size = 0

        fun write(buffer: ByteArray, length: Int) {
            val copy = buffer.copyOfRange(0, length)
            chunks += copy
            size += length
        }

        fun size(): Int = size

        fun toUtf8(): String? {
            if (size == 0) return null
            val out = java.io.ByteArrayOutputStream(size)
            chunks.forEach { out.write(it) }
            return try {
                String(out.toByteArray(), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Honest result of the import, shown to the user with no fabrication. */
    private sealed interface ImportResult {
        data class Success(
            val importedDomains: List<String>,
            val completeness: Int,
        ) : ImportResult

        data class Error(
            val code: ProfileShareErrorCode,
            val message: String,
        ) : ImportResult
    }

    companion object {
        private const val TAG = "ProfileShare"
    }
}
