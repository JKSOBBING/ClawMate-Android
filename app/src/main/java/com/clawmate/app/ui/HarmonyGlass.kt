package com.clawmate.app.ui

import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.clawmate.app.ui.theme.HarmonyTheme

/** Records content only; the glass and its sharp controls are separate siblings.
 * Android 12+ uses GPU backdrop blur. Older Android uses the tinted surface.
 * Never captures a bitmap, runs a second composition, or reads device screen data.
 */
@RequiresApi(31)
class HarmonyBackdrop internal constructor() {
    internal val node = RenderNode("ClawMate page backdrop")
    internal var origin = Offset.Zero
    internal var frame by mutableIntStateOf(0)
}

val LocalHarmonyBackdrop = compositionLocalOf<HarmonyBackdrop?> { null }

@Composable
fun rememberHarmonyBackdrop(key: Any? = Unit): HarmonyBackdrop? = remember(key) {
    if (Build.VERSION.SDK_INT >= 31) HarmonyBackdrop() else null
}

fun Modifier.recordBackdrop(backdrop: HarmonyBackdrop?): Modifier {
    if (Build.VERSION.SDK_INT < 31 || backdrop == null) return this
    // Separate layers prevent a glass redraw from recording unchanged content again.
    return graphicsLayer().onGloballyPositioned { backdrop.origin = it.positionInRoot() }.drawWithContent {
        val target = drawContext.canvas.nativeCanvas
        if (!target.isHardwareAccelerated || size.width < 1 || size.height < 1) {
            drawContent()
        } else {
            backdrop.node.setPosition(0, 0, size.width.toInt(), size.height.toInt())
            val recording = backdrop.node.beginRecording()
            val previous = drawContext.canvas
            try {
                drawContext.canvas = Canvas(recording)
                drawContent()
            } finally {
                drawContext.canvas = previous
                backdrop.node.endRecording()
            }
            target.drawRenderNode(backdrop.node)
            // Read by the glass draw scope only; this does not invalidate the recorder.
            Snapshot.withoutReadObservation { backdrop.frame++ }
        }
    }
}

@Composable
fun Modifier.harmonyGlass(backdrop: HarmonyBackdrop?, tint: Color, radiusDp: Float = 24f): Modifier {
    if (Build.VERSION.SDK_INT < 31 || backdrop == null || radiusDp <= 0f) return background(tint)
    val glass = remember { RenderNode("ClawMate glass") }
    val blur = with(LocalDensity.current) { radiusDp.dp.toPx() }
    val effect = remember(blur) { RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(glass) { onDispose { glass.discardDisplayList() } }
    return graphicsLayer().onGloballyPositioned { origin = it.positionInRoot() }.drawWithContent {
        backdrop.frame
        val canvas = drawContext.canvas.nativeCanvas
        if (canvas.isHardwareAccelerated && backdrop.node.hasDisplayList()) {
            glass.setPosition(0, 0, size.width.toInt(), size.height.toInt())
            glass.setRenderEffect(effect)
            val recorder = glass.beginRecording()
            recorder.translate(backdrop.origin.x - origin.x, backdrop.origin.y - origin.y)
            recorder.drawRenderNode(backdrop.node)
            glass.endRecording()
            canvas.drawRenderNode(glass)
        }
        drawRect(tint)
        drawContent()
    }
}

/** AppHeader 56 + content gap 16; scroll content can pass beneath the fixed title. */
@Composable
fun HarmonyPage(
    modifier: Modifier = Modifier,
    background: Color = HarmonyTheme.page,
    scrollOffset: () -> Float = { 8f },
    header: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val ownBackdrop = rememberHarmonyBackdrop()
    val backdrop = LocalHarmonyBackdrop.current ?: ownBackdrop
    val density = LocalDensity.current
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxSize().background(background)) {
        Box(Modifier.fillMaxSize().recordBackdrop(backdrop)) {
            content(PaddingValues(top = status + 72.dp, bottom = nav + 88.dp))
        }
        val opacity = (scrollOffset() / with(density) { 8.dp.toPx() }).coerceIn(0f, 1f)
        val titleTint = HarmonyTheme.glassTitle
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().harmonyGlass(backdrop, titleTint.copy(alpha = titleTint.alpha * opacity), 24f * opacity)) {
                Box(Modifier.padding(top = status)) { header() }
            }
            Box(Modifier.fillMaxWidth().height(32.dp).background(Brush.verticalGradient(
                listOf(titleTint.copy(alpha = titleTint.alpha * opacity), Color.Transparent),
            )))
        }
    }
}

/** Safe-area adapter for existing secondary pages while their own headers migrate. */
@Composable
fun InsetSecondaryPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(HarmonyTheme.page).statusBarsPadding()
        .navigationBarsPadding().padding(bottom = 88.dp)) { content() }
}
