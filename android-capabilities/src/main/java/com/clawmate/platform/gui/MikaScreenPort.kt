package com.clawmate.platform.gui

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.DOUBLE_TAP_GAP_MS
import com.wangzan101.mika.core.uistate.Relocation
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.normalize
import com.wangzan101.mika.core.uistate.relocate
import com.wangzan101.mika.core.visual.FrameGrid
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** 单次点按的手势时长；够短，系统会当成一次点击而不是长按。 */
private const val TAP_MS = 60L

@RequiresApi(34)
internal class MikaScreenPort(
    private val service: GuiControlService,
    private val run: MikaPhoneRun,
    private val target: String,
    private val label: String,
) : ScreenPort {
    private val ids = NodeIds()
    private val excerpts = linkedSetOf<String>()
    private var excerptChars = 0

    override fun publishReadExcerpt(lines: List<String>) {
        if (run.workflow != null) return
        val previous = excerpts.size
        for (line in lines) {
            val text = line.trim().take(600)
            if (text.isNotEmpty() && excerptChars + text.length + 2 <= 15_800 && excerpts.add(text)) {
                excerptChars += text.length + 2
            }
        }
        if (excerpts.size > previous) run.onEvidence(label + "\n\n" + excerpts.joinToString("\n"))
    }
    internal var lastObservedWindow: Int? = null
        private set

    override suspend fun checkAppAccess() {
        awaitReadyWindow { service.mikaWindow(run) }
    }
    override suspend fun listApps() = listOf(target to label)
    override suspend fun isProtected(): Boolean = readFrame().screen.protected
    override suspend fun screenshotPng(): ByteArray? = captureFrame(readFrame())

    override suspend fun frameGrid(): FrameGrid? {
        val before = readFrame()
        if (before.screen.protected) return null
        val grid = service.readMikaWindowImage(run, before.window) { bitmap ->
            FrameGrid.sample(bitmap.width, bitmap.height, bitmap::getPixel)
                ?.takeIf { sample -> sample.luma.any { it > 8 } }
        } ?: return null
        return grid.takeIf { stillOnFrame(before) }
    }

    private suspend fun captureFrame(before: MikaScreenFrame): ByteArray? {
        if (before.screen.protected) return null
        val png = service.mikaScreenshot(run, before.window) ?: return null
        val after = readFrame()
        return png.takeIf { sameViewport(before, after) && !after.screen.protected }
    }

    private suspend fun stillOnFrame(before: MikaScreenFrame): Boolean {
        val after = readFrame()
        return !after.screen.protected && sameObservation(before, after)
    }

    override suspend fun readVisibleText(): List<VisibleTextLine>? {
        val before = readFrame()
        if (before.screen.protected) return null
        return StillFrameRead.read(SystemClock::elapsedRealtime, { delay(it) },
            frame = { frameSignature(before) }) {
            val bitmap = service.readMikaWindowImage(run, before.window) { it.copy(Bitmap.Config.ARGB_8888, false) }
                ?: return@read null
            val lines = recognize(bitmap) ?: return@read null
            val after = readFrame()
            lines.takeIf { sameViewport(before, after) && !after.screen.protected }
        }
    }

    /** OCR a verified sub-region locally. Preserve the original screen coordinates for row matching. */
    internal suspend fun readTextRegion(left: Int, top: Int, right: Int, bottom: Int, darkInk: Boolean): List<VisibleTextLine> {
        require(left in 0 until right && right <= 999 && top in 0 until bottom && bottom <= 999)
        val before = readFrame()
        if (before.screen.protected) return emptyList()
        val bitmap = service.readMikaWindowImage(run, before.window) { src ->
            val x = left * src.width / 999
            val y = top * src.height / 999
            val w = (right * src.width / 999 - x).coerceAtLeast(1)
            val h = (bottom * src.height / 999 - y).coerceAtLeast(1)
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, x, y, w, h)
            if (darkInk) for (i in pixels.indices) {
                val color = pixels[i]
                val shade = maxOf(color shr 16 and 255, color shr 8 and 255, color and 255)
                pixels[i] = if (shade < 134) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            val crop = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            Bitmap.createScaledBitmap(crop, w * 2, h * 2, true).also { crop.recycle() }
        } ?: return emptyList()
        val lines = recognize(bitmap).orEmpty()
        if (!stillOnFrame(before)) return emptyList()
        return lines.map { l ->
            val b = l.bounds
            VisibleTextLine(l.text, listOf(left + b[0] * (right-left) / 999, top + b[1] * (bottom-top) / 999,
                left + b[2] * (right-left) / 999, top + b[3] * (bottom-top) / 999))
        }
    }

    internal suspend fun verifyPrintedTitle(line: VisibleTextLine, guess: String): String {
        val before = readFrame()
        if (before.screen.protected) return guess
        val b = line.bounds
        val bitmap = service.readMikaWindowImage(run, before.window) { src ->
            val left = ((b[0]-3).coerceAtLeast(191) * src.width / 999).coerceIn(0,src.width-1)
            val right = ((b[2]+3).coerceAtMost(751) * src.width / 999).coerceIn(left+1,src.width)
            val top = ((b[1]-2).coerceAtLeast(0) * src.height / 999).coerceIn(0,src.height-1)
            val bottom = ((b[3]+2).coerceAtMost(999) * src.height / 999).coerceIn(top+1,src.height)
            Bitmap.createBitmap(src,left,top,right-left,bottom-top)
        } ?: return guess
        val title = try { withContext(Dispatchers.Default) { OrderTitleInk.correct(bitmap,guess) } }
            finally { bitmap.recycle() }
        return if (stillOnFrame(before)) title else guess
    }

    private suspend fun recognize(bitmap: Bitmap): List<VisibleTextLine>? {
        val width = bitmap.width
        val height = bitmap.height
        val recognizer = runCatching { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
            .getOrElse { bitmap.recycle(); return null }
        val task = runCatching { recognizer.process(InputImage.fromBitmap(bitmap, 0)) }.getOrElse {
            recognizer.close()
            bitmap.recycle()
            return null
        }
        return suspendCancellableCoroutine { result ->
            task.addOnCompleteListener { done ->
                try {
                    if (result.isActive) {
                        val lines = if (done.isSuccessful) done.result.textBlocks.flatMap { block -> block.lines }
                            .mapNotNull { line ->
                                val bounds = line.boundingBox ?: return@mapNotNull null
                                VisibleTextLine(line.text, listOf(normalize(bounds.left, width), normalize(bounds.top, height),
                                    normalize(bounds.right, width), normalize(bounds.bottom, height)))
                            } else null
                        result.resume(lines)
                    }
                } finally {
                    recognizer.close()
                    bitmap.recycle()
                }
            }
        }
    }

    override suspend fun snapshot(): ScreenSnapshot {
        val frame = readFrame()
        if (lastObservedWindow != frame.window || frame.screen.protected) ids.clear()
        lastObservedWindow = frame.window
        if (!frame.screen.protected) ids.put(frame.snapshot)
        if (run.workflow?.collectingImages == true) observeWorkflowImage(frame)
        return frame.screen
    }

    internal suspend fun captureFinalWorkflowImage() = observeWorkflowImage(readFrame())

    private suspend fun observeWorkflowImage(frame: MikaScreenFrame) {
        run.workflow?.observedImage(workflowJpeg(frame))
    }

    internal suspend fun standaloneScreenshot(): Triple<ByteArray, Int, Int> {
        repeat(3) {
            val frame = readFrame()
            check(!frame.screen.protected) { "目标窗口受保护" }
            val jpeg = workflowJpeg(frame)
            if (jpeg != null) return Triple(jpeg, frame.screen.width, frame.screen.height)
            delay(150)
        }
        error("无法取得当前目标窗口的截图")
    }

    internal suspend fun workflowSwipe(x1: Int, y1: Int, x2: Int, y2: Int): ActionResult {
        val before = readFrame()
        check(!before.screen.protected) { "目标窗口受保护" }
        return service.mikaGesture(run, before.window, listOf(listOf(x1 to y1, x2 to y2)), 450).let { result ->
            if (result !is ActionResult.Ok) result else {
                waitStable(1500)
                result.copy(changed = !sameObservation(before, readFrame()))
            }
        }
    }

    private suspend fun workflowJpeg(frame: MikaScreenFrame): ByteArray? {
        val bitmap = if (frame.screen.protected) null else service.readMikaWindowImage(run, frame.window) {
            it.copy(Bitmap.Config.ARGB_8888, false)
        }
        return bitmap?.let {
            try {
                if (!stillOnFrame(frame)) null else withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().use { out ->
                        if (it.compress(Bitmap.CompressFormat.JPEG, 82, out) && out.size() <= WorkflowResultImages.MAX_IMAGE_BYTES)
                            out.toByteArray() else null
                    }
                }
            } finally { it.recycle() }
        }
    }
    override suspend fun peek() = readFrame().screen

    private suspend fun readFrame(): MikaScreenFrame = awaitReadyWindow { readCurrentFrame() }

    // During a scroll or activity transition ColorOS may briefly publish a window without a root.
    // Keep checking the same grant and display. Denials and disconnections propagate immediately.
    private suspend fun <T> awaitReadyWindow(read: () -> T): T {
        val deadline = SystemClock.elapsedRealtime() + 1_500
        while (true) {
            try {
                return read()
            } catch (pending: GuiControlService.MikaWindowNotReady) {
                if (SystemClock.elapsedRealtime() >= deadline) throw pending
                service.clearCache()
                delay(100)
            }
        }
    }

    private fun readCurrentFrame(): MikaScreenFrame {
        service.clearCache()
        val window = service.mikaWindow(run)
        val bounds = service.mikaBounds(window)
        val root = window.root ?: throw GuiControlService.MikaWindowNotReady()
        return MikaScreenReader.read(window, root, bounds, target, snapshotId(), includeViewIds = run.workflow?.collection != null || run.weiboQuery != null)
    }

    // Adapted from Mika AndroidScreen.resolve at 9fd0be4. Compare against the freshly
    // read approved window so the direct path and relocation share the same access checks.
    private fun resolve(ref: String, snapshotId: String, now: MikaScreenFrame): Pair<NodeEntry, String?>? {
        val (entry, snapshot) = when (val lookup = ids.lookup(ref, snapshotId)) {
            is NodeIds.Lookup.Current -> {
                if (lookup.snapshot.window != now.window) return null
                val unchanged = now.nodes.singleOrNull {
                    it.node == lookup.entry.node && it.locator == lookup.entry.locator
                }
                if (unchanged != null) return unchanged to null
                lookup.entry to lookup.snapshot
            }
            is NodeIds.Lookup.Old -> lookup.entry to lookup.snapshot
            NodeIds.Lookup.Unknown -> return null
        }
        if (snapshot.window != now.window) return null
        val shortSide = minOf(now.screen.width, now.screen.height)
        return when (val result = relocate(entry.locator, snapshot.locators, now.snapshot.locators, shortSide)) {
            is Relocation.Found -> now.nodes[result.index].let { it to it.element.name }
            else -> null
        }
    }

    private suspend fun act(ref: String?, snapshotId: String?, action: Int, args: Bundle? = null,
        coordinateFallback: Boolean = false, verifyText: String? = null,
        eligible: (UiElement) -> Boolean,
    ): ActionResult {
        val current = readFrame()
        if (current.screen.protected) {
            ids.clear()
            return ActionResult.Failed("target_unavailable")
        }
        val (match, relocatedTo) = if (ref == null) {
            val focused = current.nodes.singleOrNull { eligible(it.element) }
                ?: return ActionResult.Failed("target_missing_or_ambiguous")
            focused to null
        } else {
            resolve(ref, snapshotId.orEmpty(), current) ?: return ActionResult.Failed("ref_expired")
        }
        if (!eligible(match.element) || !match.node.isEnabled) return ActionResult.Failed("target_unavailable")
        val perform = {
            if (match.node.actionList.any { it.id == action }) {
                service.mikaAction(run, current.window, match.node, action, args)
            } else ActionResult.Failed("action_not_supported")
        }
        val result = if (verifyText != null) {
            // AndroidScreen.typeField: establish focus and verify the editor, not just dispatch.
            val textsBefore = current.screen.elements.map { it.name }.toSet()
            val placeholder = mikaInputPlaceholder(match.node.text?.toString(),
                match.element.placeholder, match.element.hint)
            if (!match.node.isFocused) {
                service.mikaAction(run, current.window, match.node, AccessibilityNodeInfo.ACTION_FOCUS)
                if (match.node.refresh() && !match.node.isFocused) {
                    val point = match.element.centerX to match.element.centerY
                    service.mikaGesture(run, current.window, listOf(listOf(point, point)), TAP_MS)
                    delay(200)
                }
            }
            var dispatched: ActionResult = ActionResult.Failed("action_rejected")
            val io = object : com.wangzan101.mika.core.uistate.TextField {
                override fun read(): String? {
                    if (service.mikaWindow(run).id != current.window || !match.node.refresh() ||
                        !match.node.isVisibleToUser) return null
                    return mikaFieldText(match.node.text?.toString(), match.node.isShowingHintText,
                        match.node.isPassword || match.node.isAccessibilityDataSensitive,
                        placeholder, verifyText)
                }
                override fun write(text: String): Boolean {
                    dispatched = service.mikaAction(run, current.window, match.node, action,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
                    return dispatched is ActionResult.Ok
                }
            }
            var frozen = false
            val verification = com.wangzan101.mika.core.uistate.typeAndVerify(io, verifyText,
                settle = { delay(200) },
                reacted = {
                    val fresh = readCurrentFrame()
                    val wanted = verifyText.filterNot(Char::isWhitespace)
                    (fresh.window == current.window && !fresh.screen.protected && wanted.isNotEmpty() &&
                        (fresh.screen.elements.map { it.name }.toSet() - textsBefore).any {
                            wanted in it.filterNot(Char::isWhitespace)
                        }).also { frozen = it }
                })
            (dispatched as? ActionResult.Ok)?.copy(verification = verification,
                hint = if (frozen) "The field keeps reporting the same text; the screen reacted to the input, but readback could not verify it." else null) ?: dispatched
        } else if (coordinateFallback) {
            val element = match.element
            val point = element.centerX to element.centerY
            val fallback: suspend () -> ActionResult = {
                val fresh = readFrame()
                if (!sameObservation(current, fresh)) ActionResult.Failed("stale_snapshot")
                else service.mikaGesture(run, fresh.window, listOf(listOf(point, point)), TAP_MS)
            }
            val safeToRepeat = (element.name.isNotBlank() || element.resourceId != null) &&
                !match.node.isCheckable && !ClickFallback.mayBeOutward(element.name, element.resourceId)
            verifiedClick(current, safeToRepeat, fallback) { perform() }
        } else perform()
        if (result !is ActionResult.Ok) return result
        val accepted = if (relocatedTo != null) result.copy(relocated = true, relocatedTo = relocatedTo) else result
        return accepted
    }

    override suspend fun tapRef(ref: String, snapshotId: String) =
        act(ref, snapshotId, AccessibilityNodeInfo.ACTION_CLICK, coordinateFallback = true) { it.meaningful }

    /** 坐标点按：与读屏同一坐标系里的像素点，交给无障碍手势执行（浮窗、内容区域由服务端把关）。 */
    override suspend fun tapXY(x: Int, y: Int): ActionResult {
        val before = readFrame()
        if (before.screen.protected || lastObservedWindow?.let { it != before.window } == true) {
            return ActionResult.Failed("stale_snapshot")
        }
        fun px(normalized: Int, size: Int): Int = (normalized * (size - 1) / 999.0).roundToInt()
        val width = before.screen.width
        val height = before.screen.height
        val candidate = ClickFallback.pickTarget(x, y, width, height, before.nodes.mapNotNull { match ->
            val element = match.element
            if (!element.clickable || match.node.isCheckable ||
                element.name.isBlank() && element.resourceId == null) return@mapNotNull null
            val (l, t, r, b) = element.bounds
            ClickFallback.Candidate(match, px(l, width), px(t, height), px(r, width), px(b, height),
                element.name, element.resourceId, element.scrollable, element.role == "WebView")
        })
        val fallback: (suspend () -> ActionResult)? = candidate?.let { match ->
            suspend {
                val fresh = readFrame()
                if (!sameObservation(before, fresh)) ActionResult.Failed("stale_snapshot")
                else service.mikaAction(run, fresh.window, match.node, AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        val point = x to y
        return verifiedClick(before, allowRepeat = fallback != null, fallback = fallback) {
            service.mikaGesture(run, before.window, listOf(listOf(point, point)), TAP_MS)
        }
    }

    override suspend fun doubleTapXY(x: Int, y: Int): ActionResult {
        val first = tapPoint(x, y)
        if (first !is ActionResult.Ok) return first
        delay(DOUBLE_TAP_GAP_MS)
        return tapPoint(x, y)
    }

    private suspend fun tapPoint(x: Int, y: Int): ActionResult =
        service.mikaGesture(run, lastObservedWindow, listOf(listOf(x to y, x to y)), TAP_MS)

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult =
        service.mikaGesture(run, lastObservedWindow, listOf(listOf(x1 to y1, x2 to y2)), durationMs)

    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult =
        service.mikaGesture(run, lastObservedWindow, paths, durationMs)

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
        if (!MikaInput.allowed(run.goal, text)) return ActionResult.Failed("invalid_input_text")
        val data = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (ref != null) {
            return act(ref, snapshotId, AccessibilityNodeInfo.ACTION_SET_TEXT, data, verifyText = text) { it.editable }
        }
        // 对齐 AndroidScreen.focusedField：等待可见编辑框取得焦点后输入。
        val deadline = SystemClock.elapsedRealtime() + 2_000
        var last: ActionResult = ActionResult.Failed("target_missing_or_ambiguous")
        while (SystemClock.elapsedRealtime() < deadline) {
            val frame = readFrame()
            if (!frame.screen.protected) {
                val picked = pickEditableTarget(frame.nodes.map { it.element })
                if (picked != null) {
                    val result = act(
                        null, null, AccessibilityNodeInfo.ACTION_SET_TEXT, data,
                        verifyText = text,
                    ) { it.editable && it.focused }
                    if (result != ActionResult.Failed("target_missing_or_ambiguous")) return result
                    last = result
                }
            }
            delay(120)
        }
        return last
    }

    override suspend fun clearText(ref: String?, snapshotId: String?) = typeText(ref, snapshotId, "")

    /** 点一个点再输入:先点、等焦点就位,再走上面的输入路径(不立刻在点完的同一瞬间读树)。 */
    override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult =
        tapXY(x, y).let { result -> if (result is ActionResult.Ok) typeText(null, null, text) else result }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
        // Mika AndroidScreen.scroll: resolve a supplied element's scrollable ancestor, or use
        // a scrollable container; a tree without one still supports a center swipe.
        // Host adaptation: exclude horizontal tab strips/ViewPagers and prefer vertical content.
        val frame = readFrame()
        if (frame.screen.protected) return ActionResult.Failed("target_unavailable")
        val resolved = if (ref != null) resolve(ref, snapshotId.orEmpty(), frame)
            ?: return ActionResult.Failed("ref_expired") else null
        var node = resolved?.first?.node ?: verticalScrollTarget(frame.nodes.filter { it.element.scrollable }
            .map { scrollCandidate(it.node, it.element.bounds) })
        var depth = 0
        fun vertical(n: AccessibilityNodeInfo) = n.isScrollable && scrollCandidate(n, listOf(0, 0, 1, 1)).acceptsVertical
        while (node != null && !vertical(node) && depth++ < 80) node = node.parent
        val result = if (node == null || !vertical(node)) {
            val x = frame.screen.width / 2
            val low = frame.screen.height * 3 / 4
            val high = frame.screen.height / 4
            swipe(x, if (down) low else high, x, if (down) high else low)
        } else {
            service.mikaAction(run, frame.window, node,
                if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        return if (result is ActionResult.Ok && resolved?.second != null)
            result.copy(relocated = true, relocatedTo = resolved.second) else result
    }

    override suspend fun idleScroll(fraction: Double): Boolean {
        // Ported from Mika AndroidScreen.idleScroll (f881d83f). AppDwell decides when to call;
        // this is never a substitute for the model choosing to read another page.
        if (!fraction.isFinite() || !service.mikaCanIdleScroll(run)) return false
        val frame = readFrame()
        if (frame.screen.protected || frame.nodes.any { it.element.editable && it.element.focused }) return false
        val window = service.mikaWindow(run)
        if (window.id != frame.window) return false
        val viewport = service.mikaBounds(window)
        val area = frame.nodes.filter { it.element.scrollable && scrollCandidate(it.node, it.element.bounds).acceptsVertical }.mapNotNull { entry ->
            Rect().also(entry.node::getBoundsInScreen).takeIf { it.intersect(viewport) }
        }.maxByOrNull { it.width().toLong() * it.height() } ?: return false
        if (area.width().toLong() * area.height() < frame.screen.width.toLong() * frame.screen.height * 0.5) return false
        val distance = (frame.screen.height * kotlin.math.abs(fraction)).roundToInt().coerceAtMost(area.height() / 2)
        if (distance < 8) return false
        area.offset(-viewport.left, -viewport.top)
        val x = area.centerX()
        val mid = area.centerY()
        val (from, to) = if (fraction > 0) mid + distance / 2 to mid - distance / 2
            else mid - distance / 2 to mid + distance / 2
        return service.mikaGesture(run, frame.window, listOf(listOf(x to from, x to to)), 900) is ActionResult.Ok
    }

    private fun scrollCandidate(node: AccessibilityNodeInfo, bounds: List<Int>): MikaScrollCandidate<AccessibilityNodeInfo> {
        val actions = node.actionList.map { it.id }
        return MikaScrollCandidate(node, node.className?.toString().orEmpty().substringAfterLast('.'), bounds,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in actions ||
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id in actions,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id in actions ||
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id in actions)
    }

    override suspend fun back(): ActionResult {
        val current = readFrame()
        if (current.screen.protected) return ActionResult.Failed("target_unavailable")
        val result = service.mikaKey(run, current.window, KeyEvent.KEYCODE_BACK)
        return if (result == ActionResult.Failed("key_not_available_on_main_screen")) {
            service.mikaAction(run, current.window, null, AccessibilityService.GLOBAL_ACTION_BACK)
        } else result
    }

    override suspend fun pressKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> back()
        KeyEvent.KEYCODE_ENTER -> submitFocusedField()
        else -> ActionResult.Failed("unsupported_key")
    }

    private suspend fun submitFocusedField(): ActionResult {
        val current = readFrame()
        val result = act(null, null, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) {
            it.focused && it.editable
        }
        return if (result == ActionResult.Failed("action_not_supported") || result == ActionResult.Failed("action_rejected")) {
            val fresh = readFrame()
            if (!sameObservation(current, fresh)) ActionResult.Failed("stale_snapshot")
            else service.mikaKey(run, fresh.window, KeyEvent.KEYCODE_ENTER)
        } else result
    }

    override suspend fun openApp(packageName: String, cold: Boolean) =
        if (packageName != target) ActionResult.Failed("app_not_approved")
        else if (cold) {
            val result = service.mikaRelaunch(run, target)
            if (result is ActionResult.Ok) {
                ids.clear()
                lastObservedWindow = null
            }
            result
        } else {
            service.mikaWindow(run)
            ActionResult.Ok(changed = false)
        }

    override suspend fun waitStable(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0)
        var last = readFrame()
        var unchangedSince = SystemClock.elapsedRealtime()
        while (deadline - SystemClock.elapsedRealtime() >= 160) {
            delay(160)
            val current = readFrame()
            val now = SystemClock.elapsedRealtime()
            if (!sameObservation(last, current)) unchangedSince = now
            if (now - unchangedSince >= 500 && now - service.mikaLastUiEventAt() >= 500) return true
            last = current
        }
        return false
    }

    // AndroidScreen wait/event contract, restricted to this session's display and approved app.
    override suspend fun awaitChange(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
        service.mikaUiEvents.first { it.packageName == target }
        service.mikaWindow(run)
        true
    } ?: false

    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean {
        if (condition == "stable") return waitStable(timeoutMs)
        if (condition !in setOf("appear", "disappear", "activity") || value.isNullOrBlank()) return false
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0)
        do {
            val frame = readFrame()
            if (frame.screen.protected) return false
            val matches = if (condition == "activity") service.mikaActivityName?.contains(value, true) == true
                else frame.screen.elements.any { it.name.contains(value, true) } == (condition == "appear")
            if (matches) return true
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return false
            // Also re-read after a short timeout: some custom views omit accessibility events.
            awaitChange(remaining.coerceAtMost(250))
        } while (true)
    }

    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long) =
        watchEvents(kind, packageName, timeoutMs, 1).firstOrNull()

    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<com.wangzan101.mika.core.uistate.ScreenEvent> {
        if (kind == "notification" || packageName != null && packageName != target || maxEvents <= 0) return emptyList()
        val out = mutableListOf<com.wangzan101.mika.core.uistate.ScreenEvent>()
        withTimeoutOrNull(durationMs.coerceAtLeast(0)) {
            service.mikaUiEvents.filter { event ->
                event.packageName == target && when (kind) {
                    "window" -> event.type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                        event.type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    "content" -> event.type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                        event.type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    else -> true
                }
            }.take(maxEvents.coerceAtMost(100)).collect { event ->
                service.mikaWindow(run)
                out += com.wangzan101.mika.core.uistate.ScreenEvent(
                    android.view.accessibility.AccessibilityEvent.eventTypeToString(event.type), event.packageName, null)
            }
        }
        return out
    }

    private suspend fun frameSignature(before: MikaScreenFrame): IntArray? = withTimeoutOrNull(240) {
        val current = try { readCurrentFrame() } catch (_: GuiControlService.MikaWindowNotReady) { return@withTimeoutOrNull null }
        if (!sameViewport(before, current) || current.screen.protected) return@withTimeoutOrNull null
        service.readMikaWindowImage(run, current.window) { bitmap ->
            if (FrameMath.isBlack(bitmap.width, bitmap.height, bitmap::getPixel)) null
            else FrameMath.lumaGrid(bitmap.width, bitmap.height, bitmap::getPixel)
        }
    }

    /** Mika's two click channels share a single effects check and at most one fallback. */
    private suspend fun verifiedClick(before: MikaScreenFrame, allowRepeat: Boolean,
        fallback: (suspend () -> ActionResult)?, perform: suspend () -> ActionResult,
    ): ActionResult {
        val beforeFrame = frameSignature(before)
        var actionAt = SystemClock.elapsedRealtime()
        val initial = perform()
        val accepted = if (initial == ActionResult.Failed("action_not_supported") ||
            initial == ActionResult.Failed("action_rejected")) fallback?.invoke() ?: initial else initial
        if (accepted !is ActionResult.Ok) return accepted
        var latest: IntArray? = null
        val retry: (suspend () -> Boolean)? = if (initial is ActionResult.Ok && allowRepeat &&
            beforeFrame != null && fallback != null) suspend {
            if (latest == null) false else {
                actionAt = SystemClock.elapsedRealtime()
                fallback() is ActionResult.Ok
            }
        } else null
        val changed = try {
            ChangeWait.awaitStableChange(SystemClock::elapsedRealtime, { delay(it) },
                frame = { frameSignature(before).also { latest = it } },
                quietForMs = { SystemClock.elapsedRealtime() - maxOf(actionAt, service.mikaLastUiEventAt()) },
                treeChanged = {
                    val current = try { readCurrentFrame() } catch (_: GuiControlService.MikaWindowNotReady) { null }
                    current != null && !sameObservation(before, current)
                }, beforeFrame = beforeFrame, fallback = retry)
        } catch (_: GuiControlService.MikaWindowNotReady) {
            // The gesture already completed. During an activity transition its old
            // window may disappear between reading the tree and sampling its pixels.
            return accepted.copy(changed = null, settled = false,
                hint = "The tap was dispatched. Read the destination before taking another action.")
        }
        return accepted.copy(changed = if (changed || beforeFrame != null && latest != null) changed else null)
    }

    private fun sameViewport(before: MikaScreenFrame, after: MikaScreenFrame): Boolean =
        before.window == after.window && before.screen.width == after.screen.width && before.screen.height == after.screen.height

    private fun sameObservation(before: MikaScreenFrame, after: MikaScreenFrame): Boolean =
        sameViewport(before, after) && before.screen.protected == after.screen.protected &&
            before.screen.elements == after.screen.elements
}

/**
 * 对齐原版 focusedField：无引用输入仅使用已取得输入焦点的可编辑控件。
 */
internal fun pickEditableTarget(elements: List<UiElement>): UiElement? {
    val editables = elements.filter { it.editable }
    return editables.singleOrNull { it.focused }
}
