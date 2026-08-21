package com.clawmate.app.chat.voice

import com.clawmate.app.chat.model.ChatMessage
import com.clawmate.app.chat.model.ChatMessageKind
import com.clawmate.app.chat.model.ChatMessageRole

/**
 * Pure contract for the chat digital-avatar visual — a faithful port of the
 * HarmonyOS `pages/chat/DigitalAvatarTypes.ets` + `pages/chat/DigitalAvatarView.ets`.
 *
 * Every value and formula here is deterministic and Android-free, so the exact
 * HarmonyOS sizing, voice-state mapping and pulse behaviour are unit-testable
 * without Compose. The Compose renderer (`chat/ui/DigitalAvatar.kt`) only *reads*
 * from here and applies the returned targets — it never re-derives the numbers.
 *
 * Truthfulness: the visual state is always derived from a *real*
 * [VoiceInteractionStatus] (see [DigitalAvatarVisualState.fromVoiceStatus]).
 * There is no animated state that is not backed by an actual ASR/TTS session —
 * an idle voice simply renders a static avatar (no fake motion).
 */
enum class DigitalAvatarVisualState {
    IDLE, LISTENING, THINKING, SPEAKING, INTERRUPTED;

    companion object {
        /**
         * Map a real [VoiceInteractionStatus] to a visual state — port of HarmonyOS
         * `digitalAvatarVisualStateForVoiceStatus`.
         */
        fun fromVoiceStatus(status: VoiceInteractionStatus): DigitalAvatarVisualState = when (status) {
            VoiceInteractionStatus.LISTENING, VoiceInteractionStatus.TRANSCRIBING -> DigitalAvatarVisualState.LISTENING
            VoiceInteractionStatus.THINKING, VoiceInteractionStatus.SYNTHESIZING -> DigitalAvatarVisualState.THINKING
            VoiceInteractionStatus.SPEAKING -> DigitalAvatarVisualState.SPEAKING
            VoiceInteractionStatus.PAUSED, VoiceInteractionStatus.ERROR -> DigitalAvatarVisualState.INTERRUPTED
            VoiceInteractionStatus.IDLE -> DigitalAvatarVisualState.IDLE
        }
    }
}

/** Render scale: small in-line avatar next to a bubble, or the welcome "hero". */
enum class DigitalAvatarSize { MESSAGE, HERO }

/**
 * Animated (pulsing) states — port of HarmonyOS `isAnimatedVoiceState`:
 * only thinking / listening / speaking pulse; idle and interrupted are static.
 */
val DigitalAvatarVisualState.isAnimated: Boolean
    get() = this == DigitalAvatarVisualState.THINKING ||
        this == DigitalAvatarVisualState.LISTENING ||
        this == DigitalAvatarVisualState.SPEAKING

/**
 * Per-message visual state — port of HarmonyOS `digitalAvatarVisualStateForMessageVoice`:
 * only the message that the voice session is currently active on animates; every
 * other message stays [DigitalAvatarVisualState.IDLE] (no shared, ungrounded motion).
 */
fun visualStateForMessage(
    status: VoiceInteractionStatus,
    activeMessageId: String,
    messageId: String,
): DigitalAvatarVisualState =
    if (activeMessageId.isEmpty() || activeMessageId != messageId) {
        DigitalAvatarVisualState.IDLE
    } else {
        DigitalAvatarVisualState.fromVoiceStatus(status)
    }

// ---------------------------------------------------------------------------
// Geometry (dp) — the exact HarmonyOS constants, keyed by [DigitalAvatarSize].
// ---------------------------------------------------------------------------

fun frameWidth(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 120 else 42

fun frameHeight(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 150 else 54

fun fallbackSize(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 56 else 34

fun containerWidth(size: DigitalAvatarSize, hasFrame: Boolean): Int =
    if (!hasFrame) fallbackSize(size) else if (size == DigitalAvatarSize.HERO) 150 else 56

fun containerHeight(size: DigitalAvatarSize, hasFrame: Boolean): Int =
    if (!hasFrame) fallbackSize(size) else if (size == DigitalAvatarSize.HERO) 196 else 72

fun fallbackFontSize(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 20 else 13

fun fallbackRadius(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 18 else 13

fun ringSize(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 104 else 40

fun ringRadius(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 52 else 20

fun shadowWidth(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 92 else 32

fun shadowHeight(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 16 else 8

fun shadowRadius(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 8 else 4

fun shadowTranslateY(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 57 else 19

fun highlightWidth(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 22 else 10

fun highlightHeight(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 86 else 34

fun highlightRadius(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 11 else 5

fun highlightTranslateX(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 28 else 10

fun highlightTranslateY(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) -12 else -2

fun waveBarWidth(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 5 else 3

fun waveBarGap(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 5 else 3

fun waveBarRadius(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 3 else 2

fun waveTranslateX(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 55 else 19

fun waveTranslateY(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) -10 else -2

fun waveBarMaxHeight(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 28 else 15

fun waveBarSmallHeight(size: DigitalAvatarSize): Int = if (size == DigitalAvatarSize.HERO) 12 else 7

fun tapLiftY(size: DigitalAvatarSize): Float = if (size == DigitalAvatarSize.HERO) -16f else -10f

fun tapPeakScale(size: DigitalAvatarSize): Float = if (size == DigitalAvatarSize.HERO) 1.16f else 1.28f

fun tapSettleScale(size: DigitalAvatarSize): Float = if (size == DigitalAvatarSize.HERO) 1.04f else 1.08f

// ---------------------------------------------------------------------------
// Voice-state driven animation targets — the exact HarmonyOS formulas.
// ---------------------------------------------------------------------------

/** Foreground breathing scale while animated+pulsing — HarmonyOS `voicePulseScale`. */
fun voicePulseScale(state: DigitalAvatarVisualState): Float = when (state) {
    DigitalAvatarVisualState.LISTENING -> 1.03f
    DigitalAvatarVisualState.SPEAKING -> 1.07f
    else -> 1.04f // thinking
}

/** Outer ring scale — HarmonyOS `ringScale`. */
fun ringScale(state: DigitalAvatarVisualState, pulse: Boolean): Float = when {
    state == DigitalAvatarVisualState.INTERRUPTED -> 1.04f
    !state.isAnimated -> 0.92f
    state == DigitalAvatarVisualState.SPEAKING -> if (pulse) 1.26f else 0.94f
    else -> if (pulse) 1.18f else 0.92f // listening / thinking
}

/** Outer ring opacity — HarmonyOS `ringOpacity`. */
fun ringOpacity(state: DigitalAvatarVisualState, pulse: Boolean): Float = when {
    state == DigitalAvatarVisualState.INTERRUPTED -> 0.42f
    !state.isAnimated -> 0.22f
    state == DigitalAvatarVisualState.LISTENING -> if (pulse) 0.48f else 0.20f
    state == DigitalAvatarVisualState.SPEAKING -> if (pulse) 0.66f else 0.24f
    else -> if (pulse) 0.58f else 0.22f // thinking
}

/**
 * Scale-Y for the [index]-th of the 3 wave bars (0-based) — HarmonyOS
 * `waveBarScaleY`. Returns 0 when the state shows no bars; the renderer clamps
 * to 0.01 so a `scaleY(0f)` (degenerate) transform is never produced.
 */
fun waveBarScaleY(
    size: DigitalAvatarSize,
    state: DigitalAvatarVisualState,
    pulse: Boolean,
    index: Int,
): Float {
    val small = waveBarSmallHeight(size)
    val large = waveBarMaxHeight(size)
    val target = when {
        state == DigitalAvatarVisualState.INTERRUPTED -> small
        state == DigitalAvatarVisualState.SPEAKING ->
            if (index == 1) (if (pulse) large else small) else (if (pulse) small else large)
        else -> 0
    }
    return if (target > 0) target.toFloat() / large else 0.01f
}

/** Wave-bar group opacity — HarmonyOS `waveOpacity` (bars only show speaking/interrupted). */
fun waveOpacity(state: DigitalAvatarVisualState, pulse: Boolean): Float = when {
    state == DigitalAvatarVisualState.SPEAKING -> if (pulse) 1f else 0.72f
    state == DigitalAvatarVisualState.INTERRUPTED -> 0.5f
    else -> 0f
}

/** Foreground scale = tap scale × (animated pulse breathing) — HarmonyOS `foregroundScale`. */
fun foregroundScale(tapScale: Float, state: DigitalAvatarVisualState, pulse: Boolean): Float =
    tapScale * (if (state.isAnimated && pulse) voicePulseScale(state) else 1f)

/** Ground shadow scale — dips to 0.92 while animated+pulsing — HarmonyOS `shadowScale`. */
fun shadowScale(tapShadowScale: Float, state: DigitalAvatarVisualState, pulse: Boolean): Float =
    (if (state.isAnimated && pulse) 0.92f else 1f) * tapShadowScale

// ---------------------------------------------------------------------------
// Per-message avatar model — port of HarmonyOS `chatAvatarRenderModel`.
// ---------------------------------------------------------------------------

data class DigitalAvatarRenderModel(
    val useDigitalAvatar: Boolean,
    val fallbackText: String,
    val fallbackColorHex: String,
    val visualState: DigitalAvatarVisualState,
)

/** HarmonyOS `useDigitalAvatarForMessage`: only assistant CHAT messages animate. */
fun useDigitalAvatarForMessage(message: ChatMessage): Boolean =
    message.role == ChatMessageRole.ASSISTANT && message.kind == ChatMessageKind.CHAT

/**
 * Decide the avatar look for a message — port of HarmonyOS `chatAvatarRenderModel`:
 * user → static "我"; context notice → "压"; task result/trace → "任"; otherwise an
 * assistant chat message → the animated digital avatar ("助" fallback letter).
 */
fun chatAvatarRenderModel(
    message: ChatMessage,
    modeMark: String,
    visualState: DigitalAvatarVisualState,
): DigitalAvatarRenderModel {
    if (message.role == ChatMessageRole.USER) {
        return DigitalAvatarRenderModel(false, "我", "#111827", visualState)
    }
    if (message.kind == ChatMessageKind.CONTEXT_NOTICE) {
        return DigitalAvatarRenderModel(false, "压", "#F59E0B", visualState)
    }
    if (message.kind == ChatMessageKind.TASK_RESULT) {
        return DigitalAvatarRenderModel(false, "任", "#059669", visualState)
    }
    if (message.kind == ChatMessageKind.TASK_TRACE) {
        return DigitalAvatarRenderModel(false, "任", "#7C3AED", visualState)
    }
    val assistantText = if (modeMark.isNotEmpty()) modeMark else "助"
    return DigitalAvatarRenderModel(
        useDigitalAvatar = useDigitalAvatarForMessage(message),
        fallbackText = assistantText,
        fallbackColorHex = "#5968FF",
        visualState = visualState,
    )
}

/** Static layer colors — the exact HarmonyOS `#AARRGGBB` constants. */
object DigitalAvatarColors {
    const val RING_HEX = "#335968FF"
    const val TAP_HALO_HEX = "#445968FF"
    const val SHADOW_HEX = "#35222B46"
    const val HIGHLIGHT_HEX = "#33FFFFFF"
    const val WAVE_HEX = "#5968FF"
    const val DEFAULT_FALLBACK_HEX = "#5968FF"
}
