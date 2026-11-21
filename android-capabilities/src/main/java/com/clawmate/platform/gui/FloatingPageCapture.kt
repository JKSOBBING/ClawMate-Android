package com.clawmate.platform.gui

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** One explicit gesture captures one focused application window, using the OS secure-window check. */
@RequiresApi(34)
internal class FloatingPageCapture(private val service: AccessibilityService) {
    private data class Page(val id: Int, val pkg: String, val root: AccessibilityNodeInfo, val bounds: Rect, val content: Rect) {
        fun sameWindow(other: Page) = id == other.id && pkg == other.pkg && bounds == other.bounds && content == other.content

        fun cropContent(image: Bitmap): Bitmap {
            val region = content
            val window = bounds
            val scaleX = image.width.toDouble() / window.width()
            val scaleY = image.height.toDouble() / window.height()
            val left = kotlin.math.ceil((region.left - window.left) * scaleX).toInt()
            val top = kotlin.math.ceil((region.top - window.top) * scaleY).toInt()
            val right = ((region.right - window.left) * scaleX).toInt().coerceAtMost(image.width)
            val bottom = ((region.bottom - window.top) * scaleY).toInt().coerceAtMost(image.height)
            check(right > left && bottom > top) { "当前页面没有可采集的区域" }
            return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        }
    }
    private fun page(): Page {
        check(!service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "请先解锁手机" }
        val windows = service.windowsOnAllDisplays[Display.DEFAULT_DISPLAY].orEmpty()
        val window = windows.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?: error("请先打开要采集的应用页面")
        val root = window.root ?: error("当前页面暂时无法读取")
        val pkg = root.packageName?.toString() ?: error("当前应用暂时无法读取")
        val bounds = Rect().also(window::getBoundsInScreen)
        val metrics = service.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val content = Rect(metrics.bounds).apply { inset(insets) }
        check(content.intersect(bounds)) { "当前页面没有可采集的区域" }
        check(windows.none { other -> other.layer > window.layer && other.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            Rect.intersects(content, Rect().also(other::getBoundsInScreen)) }) { "请收起键盘、通知或系统弹窗后再采集" }
        val pm = service.packageManager
        val isLaunchable = pm.getLaunchIntentForPackage(pkg) != null || pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName == pkg
        check(pkg !in setOf("com.android.settings", "com.android.systemui", "android") &&
            !pkg.contains("permissioncontroller") && isLaunchable) {
            "请在要采集的应用中双击悬浮窗"
        }
        MikaAppAccess.requireAllowed(service, pkg)
        return Page(window.id, pkg, root, bounds, content)
    }

    private suspend fun snapshot(id: Int): Bitmap = suspendCancellableCoroutine { continuation ->
        service.takeScreenshotOfWindow(id, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                result.hardwareBuffer.use { buffer ->
                    if (!continuation.isActive) return
                    runCatching {
                        check(buffer.width.toLong() * buffer.height in 1L..16_000_000L) { "页面图像过大" }
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("当前页面无法截图")
                        try { wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: error("页面图像不可读") }
                        finally { wrapped.recycle() }
                    }.onSuccess { bitmap -> continuation.resume(bitmap) { bitmap.recycle() } }
                        .onFailure { continuation.resumeWithException(it) }
                }
            }
            override fun onFailure(errorCode: Int) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("当前页面受保护或暂时无法采集"))
            }
        })
    }

    private fun canUseOcr(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var inspected = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (++inspected > 512) return false
            if (!node.isVisibleToUser) continue
            if (node.isPassword || node.isEditable || node.isAccessibilityDataSensitive) return false
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return true
    }

    suspend fun save(scope: CoroutineScope, stillIdle: () -> Boolean) {
        val before = page()
        val image = snapshot(before.id)
        val record = try {
            val current = page()
            check(stillIdle() && before.sameWindow(current)) { "页面已切换，请重试" }
            val label = service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(current.pkg, 0)).toString()
            val visible = GuiCollection.read(scope, current.root, current.content, current.pkg, label, null).record
            if (visible.lines.isNotEmpty()) visible else {
                check(canUseOcr(current.root)) { "当前页面没有可采集的公开文字" }
                val text = withContext(Dispatchers.IO) {
                    val cropped = current.cropContent(image)
                    try {
                        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                        try { Tasks.await(recognizer.process(InputImage.fromBitmap(cropped, 0)), 10, TimeUnit.SECONDS).text }
                        finally { recognizer.close() }
                    } finally { if (cropped !== image) cropped.recycle() }
                }
                val lines = text.take(6000).lines().map { it.trim().take(600) }.filter { it.isNotEmpty() }.distinct()
                visible.copy(lines = lines.take(80), partial = text.length > 6000 || lines.size > 80)
            }
        } finally { image.recycle() }
        check(record.lines.isNotEmpty()) { "当前页没有读到可保存的文字" }
        val finalPage = page()
        check(stillIdle() && before.sameWindow(finalPage)) { "页面已切换，请重试" }
        val summarized = record.copy(summary = floatingSummary(record.lines))
        withContext(Dispatchers.IO) { GuiCollectionHistory.save(service, summarized) }
    }
}

internal fun floatingSummary(lines: List<String>): String {
    val fragments = lines.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }.distinct()
    return fragments.filter { it.length >= 4 }.ifEmpty { fragments }.take(4).joinToString(" · ") { it.take(72) }
}
