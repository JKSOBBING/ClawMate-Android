package com.clawmate.app.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.chat.voice.DigitalAvatarColors
import com.clawmate.app.chat.voice.DigitalAvatarSize
import com.clawmate.app.chat.voice.DigitalAvatarVisualState
import com.clawmate.app.chat.voice.containerHeight
import com.clawmate.app.chat.voice.containerWidth
import com.clawmate.app.chat.voice.fallbackFontSize
import com.clawmate.app.chat.voice.fallbackRadius
import com.clawmate.app.chat.voice.fallbackSize
import com.clawmate.app.chat.voice.foregroundScale
import com.clawmate.app.chat.voice.frameHeight
import com.clawmate.app.chat.voice.frameWidth
import com.clawmate.app.chat.voice.highlightHeight
import com.clawmate.app.chat.voice.highlightRadius
import com.clawmate.app.chat.voice.highlightTranslateX
import com.clawmate.app.chat.voice.highlightTranslateY
import com.clawmate.app.chat.voice.highlightWidth
import com.clawmate.app.chat.voice.isAnimated
import com.clawmate.app.chat.voice.ringOpacity
import com.clawmate.app.chat.voice.ringScale
import com.clawmate.app.chat.voice.ringSize
import com.clawmate.app.chat.voice.shadowHeight
import com.clawmate.app.chat.voice.shadowRadius
import com.clawmate.app.chat.voice.shadowScale
import com.clawmate.app.chat.voice.shadowTranslateY
import com.clawmate.app.chat.voice.shadowWidth
import com.clawmate.app.chat.voice.waveBarGap
import com.clawmate.app.chat.voice.waveBarMaxHeight
import com.clawmate.app.chat.voice.waveBarRadius
import com.clawmate.app.chat.voice.waveBarScaleY
import com.clawmate.app.chat.voice.waveBarWidth
import com.clawmate.app.chat.voice.waveOpacity
import com.clawmate.app.chat.voice.waveTranslateX
import com.clawmate.app.chat.voice.waveTranslateY
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Parse a `#RRGGBB` / `#AARRGGBB` literal into a Compose [Color]. */
private fun avatarColor(hex: String): Color {
    val h = hex.removePrefix("#")
    val v = h.toLong(16)
    return Color(if (h.length == 6) (0xFF000000L or v) else v)
}

/**
 * The animated chat digital avatar — a faithful Compose port of HarmonyOS
 * `pages/chat/DigitalAvatarView.ets`.
 *
 * Layers (back → front): outer ring → ground shadow → foreground (a real image
 * when supplied, otherwise the fallback letter) → gloss highlight → voice-state
 * wave bars. Every size, color and pulse value is read from the pure
 * [com.clawmate.app.chat.voice] contract, so the visual is 1:1 with HarmonyOS.
 *
 * Truthfulness: the pulse loop (800 ms) runs **only** while [visualState] is a
 * genuinely animated state *and* the avatar has real content. An idle voice
 * renders a static avatar — no motion is ever faked.
 */
@Composable
fun DigitalAvatar(
    modifier: Modifier = Modifier,
    size: DigitalAvatarSize = DigitalAvatarSize.MESSAGE,
    visualState: DigitalAvatarVisualState = DigitalAvatarVisualState.IDLE,
    useDigitalAvatar: Boolean = true,
    fallbackText: String = "助",
    fallbackColorHex: String = DigitalAvatarColors.DEFAULT_FALLBACK_HEX,
    foregroundPainter: Painter? = null,
) {
    val fallbackColor = avatarColor(fallbackColorHex)

    // Non-digital messages (user "我", notices "压", task "任") are a plain
    // static letter — matching HarmonyOS, which shows no ring/wave for them.
    if (!useDigitalAvatar) {
        Box(
            modifier = modifier
                .size(fallbackSize(size).dp)
                .background(fallbackColor, RoundedCornerShape(fallbackRadius(size).dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fallbackText,
                fontSize = fallbackFontSize(size).sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }

    val hasContent = foregroundPainter != null || fallbackText.isNotEmpty()
    val animated = hasContent && visualState.isAnimated

    // 800 ms pulse — restarts only when the animated flag flips; runs free across
    // thinking/listening/speaking so the breathing never stutters.
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(animated) {
        if (!animated) {
            pulse = false
            return@LaunchedEffect
        }
        while (true) {
            delay(800)
            pulse = !pulse
        }
    }

    // Smoothly track the pure-contract targets. Each [Animatable] re-targets from
    // its current value (mirroring HarmonyOS `.animation`), so the 800 ms pulse
    // breathes without a stutter and voice-state changes ease in rather than jump.
    val ringScaleA = remember { Animatable(ringScale(visualState, false)) }
    val ringOpacityA = remember { Animatable(ringOpacity(visualState, false)) }
    val fgScaleA = remember { Animatable(1f) }
    val shadowScaleA = remember { Animatable(1f) }
    val waveOpacityA = remember { Animatable(0f) }
    val wave0A = remember { Animatable(waveBarScaleY(size, visualState, false, 0)) }
    val wave1A = remember { Animatable(waveBarScaleY(size, visualState, false, 1)) }
    val wave2A = remember { Animatable(waveBarScaleY(size, visualState, false, 2)) }

    LaunchedEffect(visualState, pulse) {
        // Going to a non-animated state (idle / interrupted) snaps immediately so a
        // voice that goes quiet always settles — a still-running pulse animation can
        // otherwise no-op an in-flight re-target and leave the ring stuck expanded.
        val settle = !visualState.isAnimated
        val ringSpec = tween<Float>(760)
        val waveSpec = tween<Float>(360)
        launch {
            val t = ringScale(visualState, pulse)
            if (settle) ringScaleA.snapTo(t) else ringScaleA.animateTo(t, ringSpec)
        }
        launch {
            val t = ringOpacity(visualState, pulse)
            if (settle) ringOpacityA.snapTo(t) else ringOpacityA.animateTo(t, ringSpec)
        }
        launch {
            val t = foregroundScale(1f, visualState, pulse)
            if (settle) fgScaleA.snapTo(t) else fgScaleA.animateTo(t, ringSpec)
        }
        launch {
            val t = shadowScale(1f, visualState, pulse)
            if (settle) shadowScaleA.snapTo(t) else shadowScaleA.animateTo(t, ringSpec)
        }
        launch {
            val t = waveOpacity(visualState, pulse)
            if (settle) waveOpacityA.snapTo(t) else waveOpacityA.animateTo(t, waveSpec)
        }
        launch {
            val t = waveBarScaleY(size, visualState, pulse, 0)
            if (settle) wave0A.snapTo(t) else wave0A.animateTo(t, waveSpec)
        }
        launch {
            val t = waveBarScaleY(size, visualState, pulse, 1)
            if (settle) wave1A.snapTo(t) else wave1A.animateTo(t, waveSpec)
        }
        launch {
            val t = waveBarScaleY(size, visualState, pulse, 2)
            if (settle) wave2A.snapTo(t) else wave2A.animateTo(t, waveSpec)
        }
    }

    val ringScaleV = ringScaleA.value
    val ringOpacityV = ringOpacityA.value
    val foregroundScaleV = fgScaleA.value
    val shadowScaleV = shadowScaleA.value
    val waveOpacityV = waveOpacityA.value
    val wave0 = wave0A.value
    val wave1 = wave1A.value
    val wave2 = wave2A.value

    Box(
        modifier = modifier
            .size(
                width = containerWidth(size, true).dp,
                height = containerHeight(size, true).dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 1. Outer ring — normally hidden behind the frame, grows past it on the pulse.
        Box(
            modifier = Modifier
                .size(ringSize(size).dp)
                .background(avatarColor(DigitalAvatarColors.RING_HEX).copy(alpha = ringOpacityV), CircleShape)
                .scale(ringScaleV),
        )

        // 2. Ground shadow — dips to 0.92 while the avatar "lifts" on the pulse.
        Box(
            modifier = Modifier
                .width(shadowWidth(size).dp)
                .height(shadowHeight(size).dp)
                .offset(y = shadowTranslateY(size).dp)
                .background(avatarColor(DigitalAvatarColors.SHADOW_HEX), RoundedCornerShape(shadowRadius(size).dp))
                .scale(shadowScaleV),
        )

        // 3. Foreground — the real avatar image, or the fallback letter.
        Box(
            modifier = Modifier
                .then(
                    if (foregroundPainter != null) {
                        Modifier.size(width = frameWidth(size).dp, height = frameHeight(size).dp)
                    } else {
                        Modifier.size(fallbackSize(size).dp)
                    },
                )
                .scale(foregroundScaleV),
            contentAlignment = Alignment.Center,
        ) {
            val painter = foregroundPainter
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(fallbackColor, RoundedCornerShape(fallbackRadius(size).dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = fallbackText,
                        fontSize = fallbackFontSize(size).sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // 4. Gloss highlight — a static soft streak over the avatar.
        Box(
            modifier = Modifier
                .width(highlightWidth(size).dp)
                .height(highlightHeight(size).dp)
                .offset(x = highlightTranslateX(size).dp, y = highlightTranslateY(size).dp)
                .background(avatarColor(DigitalAvatarColors.HIGHLIGHT_HEX), RoundedCornerShape(highlightRadius(size).dp)),
        )

        // 5. Voice-state wave bars — visible only while speaking / interrupted.
        if (waveOpacityV > 0.001f) {
            Row(
                modifier = Modifier
                    .offset(x = waveTranslateX(size).dp, y = waveTranslateY(size).dp)
                    .alpha(waveOpacityV),
                horizontalArrangement = Arrangement.spacedBy(waveBarGap(size).dp),
            ) {
                val scales = listOf(wave0, wave1, wave2)
                for (s in scales) {
                    Box(
                        modifier = Modifier
                            .width(waveBarWidth(size).dp)
                            .height(waveBarMaxHeight(size).dp)
                            .background(avatarColor(DigitalAvatarColors.WAVE_HEX), RoundedCornerShape(waveBarRadius(size).dp))
                            .scale(1f, s),
                    )
                }
            }
        }
    }
}
