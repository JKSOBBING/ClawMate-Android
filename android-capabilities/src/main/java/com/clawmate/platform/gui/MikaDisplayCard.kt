package com.clawmate.platform.gui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.clawmate.platform.R
import com.clawmate.platform.display.AgentDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The workflow retains its presentation while the same display moves between steps. */
internal class MikaPreviewState(var zoomed: Boolean = false, var hidden: Boolean = true)

internal class MikaDisplayCard(
    context: Context,
    private val target: String,
    private val display: AgentDisplay,
    private val scope: CoroutineScope,
    private val state: MikaPreviewState,
    steer: () -> Unit,
    stop: () -> Unit,
) : FrameLayout(context) {
    private val anchor = FloatingPosition(context)
    private val style = GuiCollectionStyle(context)
    private val wm = context.getSystemService(WindowManager::class.java)
    private val params = WindowManager.LayoutParams(
        0, 0, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
    ).apply { title = "ClawMate 虚拟屏预览" }

    private var aspect = display.width.toFloat() / display.height
    private var framesJob: Job? = null
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        isFocusable = true
        setOnClickListener {
            state.zoomed = !state.zoomed
            updatePresentation()
        }
    }
    private val preview = FrameLayout(context)
    private val motion = FloatingPanelMotion(preview, !state.hidden) { visible ->
        state.hidden = !visible
        updatePresentation()
    }
    private val hide = icon(R.drawable.mika_preview_hide, "隐藏虚拟屏") { motion.present(false) }
    private val reveal = floatingLogo(context, "展开虚拟屏").apply { setOnClickListener { motion.present(true) } }
    val status = style.label("正在启动", 12).apply {
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(style.dp(10), style.dp(10), style.dp(8), style.dp(2))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.TRANSPARENT, 0xC9212632.toInt(), 0xE6212632.toInt(),
        ))
        addView(status)
        addView(LinearLayout(context).apply {
            gravity = Gravity.END
            addView(action("插话", steer))
            addView(action("停止", stop))
        })
    }

    init {
        preview.addView(image, LayoutParams(-1, -1))
        preview.addView(controls, LayoutParams(-1, -2, Gravity.BOTTOM))
        preview.addView(hide, LayoutParams(style.dp(48), style.dp(48), Gravity.TOP or Gravity.RIGHT))
        addView(preview, LayoutParams(-1, -1))
        addView(reveal, LayoutParams(-1, -1))
        anchor.gestures(image, this, params)
        anchor.gestures(reveal, this, params)
        updatePresentation()
    }

    private fun icon(resource: Int, label: String, click: () -> Unit) = ImageButton(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(0, 0, 0, 0)
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(resource)
        contentDescription = label
        setOnClickListener { click() }
    }

    private fun action(label: String, click: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        minWidth = style.dp(52)
        minimumWidth = minWidth
        minHeight = style.dp(48)
        minimumHeight = minHeight
        setPadding(style.dp(8), 0, style.dp(8), 0)
        setOnClickListener { click() }
    }

    fun show() = wm.addView(this, params)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateFrames()
    }

    override fun onDetachedFromWindow() {
        framesJob?.cancel()
        framesJob = null
        image.setImageDrawable(null)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        position()
    }

    private fun updateFrames() {
        if (state.hidden) {
            framesJob?.cancel()
            framesJob = null
            image.setImageDrawable(null)
        } else if (isAttachedToWindow && framesJob == null) {
            framesJob = scope.launch {
                display.frames.filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect { frame ->
                    val next = frame.width.toFloat() / frame.height
                    if (next != aspect) {
                        aspect = next
                        position()
                    }
                    image.setImageBitmap(frame)
                }
            }
        }
    }

    private fun updatePresentation() {
        val visible = !state.hidden
        preview.visibility = if (visible) VISIBLE else GONE
        controls.visibility = if (visible && state.zoomed) VISIBLE else GONE
        reveal.visibility = if (visible) GONE else VISIBLE
        image.contentDescription = "$target · " + if (state.zoomed) "点按缩小虚拟屏" else "点按放大虚拟屏"
        background = if (visible) GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = style.dp(10).toFloat()
        } else null
        clipToOutline = visible
        position()
        updateFrames()
    }

    private fun position() {
        val metrics = resources.displayMetrics
        if (state.hidden) {
            params.width = style.dp(48)
            params.height = style.dp(48)
        } else {
            // Mika's zoom is bounded by both 300dp and 72% of the display width.
            val maxWidth = if (state.zoomed) minOf(style.dp(300), (metrics.widthPixels * .72f).roundToInt())
                else minOf(style.dp(136), (metrics.widthPixels * .33f).roundToInt())
            val maxHeight = (metrics.heightPixels * if (state.zoomed) .78f else .38f).roundToInt()
            params.width = minOf(maxWidth, (maxHeight * aspect).roundToInt()).coerceAtLeast(1)
            params.height = (params.width / aspect).roundToInt().coerceAtLeast(1)
        }
        anchor.place(params)
        if (isAttachedToWindow) wm.updateViewLayout(this, params)
    }

}
