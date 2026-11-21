package com.clawmate.platform.gui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.roundToInt

/** The home board publishes its current pending rows to the app-private store. */
object FloatingNotes {
    const val OPEN_HOME = "com.clawmate.action.OPEN_FLOATING_NOTE_HOME"
    private fun store(context: Context) = context.getSharedPreferences("floating-notes", Context.MODE_PRIVATE)
    fun enabled(context: Context) = store(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean("enabled", enabled).apply()
        GuiControlService.connected?.refreshFloatingNotes()
    }

    fun settings(activity: Activity) {
        val dialog = AlertDialog.Builder(activity, com.clawmate.platform.R.style.ClawMateDialog)
            .setTitle("后台悬浮窗")
        if (android.os.Build.VERSION.SDK_INT < 34) {
            dialog.setMessage("悬浮便签需要 Android 14 或更高版本。").setPositiveButton("知道了", null).show()
            return
        }
        val enabled = enabled(activity)
        val connected = GuiControlService.connected != null
        val state = when {
            !enabled -> "已关闭"
            connected -> "已开启"
            else -> "等待无障碍服务连接"
        }
        val help = if (connected) "单击图标看提醒，双击采集当前页。拖动松手后会贴到最近的屏幕边缘，闲置 3 秒后图标变为半透明。"
            else "请在系统无障碍设置中开启「ClawMate 按次界面控制」，以便在桌面和其他应用上显示图标。"
        dialog.setMessage("$state\n\n$help").setNegativeButton("取消", null)
        if (enabled) {
            dialog.setPositiveButton("关闭悬浮窗") { _, _ -> setEnabled(activity, false) }
            if (!connected) dialog.setNeutralButton("去系统设置") { _, _ -> GuiControlEntry.settings(activity) }
        } else {
            dialog.setPositiveButton("开启悬浮窗") { _, _ ->
                setEnabled(activity, true)
                if (GuiControlService.connected == null) GuiControlEntry.settings(activity)
            }
        }
        dialog.show()
    }

    fun update(context: Context, raw: String) {
        require(raw.length <= 12000)
        val items = JSONArray(raw)
        require(items.length() <= 32)
        store(context).edit().putString("rows", raw).apply()
        GuiControlService.connected?.refreshFloatingNotes()
    }

    internal fun lines(context: Context, now: Long): List<String> {
        val rows = JSONArray(store(context).getString("rows", "[]"))
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val until = row.optLong("until")
            row.optString("text").replace(Regex("\\s+"), " ").trim().take(90)
                .takeIf { it.isNotEmpty() && (until == 0L || until > now) }
        }
    }
}

internal class FloatingNoteWindow(context: Context, capture: () -> Unit) : FrameLayout(context) {
    private val style = GuiCollectionStyle(context)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val anchor = FloatingPosition(context)
    private val size = style.dp(48)
    private val surface = style.noteSurface()
    private val logo = floatingLogo(context, "ClawMate 便签，单击看提醒，双击采集当前页")
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(style.dp(12), 0, style.dp(12), style.dp(12))
    }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply { addView(rows) }
    private val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT)
        .apply { title = "ClawMate 悬浮便签" }
    private var screen = anchor.viewport()
    private var fullWidth = size
    private var fullHeight = size
    private var fraction = 0f
    private var open = false
    private var morph: ValueAnimator? = null

    init {
        background = surface
        clipToOutline = true
        content.setOnClickListener { openHome() }
        content.addView(style.label("提醒事项", 16, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, size, 0)
            setOnClickListener { openHome() }
        }, LinearLayout.LayoutParams(-1, size))
        content.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        addView(content, LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT))
        addView(logo, LayoutParams(size, size, Gravity.TOP or Gravity.RIGHT))
        logo.setOnClickListener { toggle() }
        anchor.gestures(logo, this, params, capture)
        render(0f)
        anchor.place(params, screen)
    }

    fun show() = manager.addView(this, params)

    fun close() {
        if (isAttachedToWindow) manager.removeViewImmediate(this)
    }

    private fun openHome() {
        if (!open) return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.action = FloatingNotes.OPEN_HOME
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(intent)
            toggle()
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "暂时无法打开 ClawMate 首页", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggle() {
        open = !open
        logo.contentDescription = if (open) "收起提醒，双击采集当前页" else "ClawMate 便签，单击看提醒，双击采集当前页"
        morph?.cancel()
        if (open) refresh()
        val target = if (open) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled() || fraction == target) {
            render(target)
            return
        }
        morph = ValueAnimator.ofFloat(fraction, target).apply {
            duration = (250 * abs(target - fraction)).toLong().coerceAtLeast(1)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { render(it.animatedValue as Float) }
            start()
        }
    }

    /** Animate the actual touchable window. Its gravity holds the same physical edge for every frame. */
    private fun render(value: Float) {
        fraction = value
        surface.cornerRadius = size / 2f + (style.dp(16) - size / 2f) * value
        surface.alpha = (255 * value).roundToInt()
        content.visibility = if (value == 0f) View.INVISIBLE else View.VISIBLE
        content.alpha = ((value - .25f) / .75f).coerceIn(0f, 1f)
        content.importantForAccessibility = if (value == 1f) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val width = size + ((fullWidth - size) * value).roundToInt()
        val height = size + ((fullHeight - size) * value).roundToInt()
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        anchor.place(params, screen)
        if (isAttachedToWindow) manager.updateViewLayout(this, params)
    }

    fun refresh() {
        screen = anchor.viewport()
        if (!open) return
        fullWidth = minOf(style.dp(236), screen.width)
        val lines = FloatingNotes.lines(context, System.currentTimeMillis())
        rows.removeAllViews()
        for (line in lines.ifEmpty { listOf("暂无待办提醒") }) {
            rows.addView(style.label(line, 13).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, style.dp(4), 0, style.dp(2))
                setOnClickListener { openHome() }
            })
        }
        val bodyWidth = (fullWidth - content.paddingLeft - content.paddingRight).coerceAtLeast(0)
        rows.measure(MeasureSpec.makeMeasureSpec(bodyWidth, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        val listLimit = (screen.height - size - content.paddingBottom).coerceAtLeast(0)
        scroll.layoutParams = scroll.layoutParams.apply { height = minOf(rows.measuredHeight, style.dp(120), listLimit) }
        content.measure(MeasureSpec.makeMeasureSpec(fullWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(screen.height, MeasureSpec.AT_MOST))
        fullHeight = content.measuredHeight
        content.layoutParams = content.layoutParams.apply { width = fullWidth; height = fullHeight }
        render(fraction)
    }

    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        refresh()
        anchor.place(params, screen)
        if (isAttachedToWindow) manager.updateViewLayout(this, params)
    }

    override fun onDetachedFromWindow() {
        morph?.cancel()
        super.onDetachedFromWindow()
    }
}
