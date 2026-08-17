package com.clawmate.app.overlay

/**
 * Thread-safe holder of the live float/edge overlay state (Phase 20). Mirrors
 * the Phase 19 `AccessibilityStateRegistry` manual-DI pattern: the
 * lifecycle-safe [OverlayFloatService] records what it has *actually*
 * observed/been granted into this holder so the rest of the app can build an
 * honest [OverlayGate] — never from assumption.
 *
 * The registry stores only observable facts: whether the backend observed the
 * overlay permission as granted, whether the scaffold observed the overlay
 * service present/connected, and (Phase 26) a **bounded, sanitized agent-status
 * text**. It deliberately holds **no screenshot data, no chat/tool inputs, no
 * tokens, no plaintext secrets and no window state** — the status surface only
 * ever renders what the agent chat loop chooses to publish here, truncated to
 * [MAX_STATUS_TEXT_LENGTH] and scrubbed of control characters.
 *
 * State is cleared on [reset] so tests and service disconnects start clean.
 */
object OverlayStateRegistry {

    /**
     * Maximum number of characters retained for the live agent-status text.
     * The value is deliberately small so the single-line overlay surface cannot
     * be used to exfiltrate long prompts, tool arguments, tokens or secrets.
     */
    const val MAX_STATUS_TEXT_LENGTH: Int = 160

    /** Sanitized, bounded plain-text status line for the agent-status surface. */
    private const val DEFAULT_STATUS_TEXT: String = ""

    @Volatile
    private var permissionGranted: Boolean = false

    @Volatile
    private var overlayPresent: Boolean = false

    @Volatile
    private var agentState: OverlayAgentState = OverlayAgentState.IDLE

    @Volatile
    private var agentStatusText: String = DEFAULT_STATUS_TEXT

    /** Whether the system overlay permission has been observed as granted. */
    fun isPermissionGranted(): Boolean = synchronized(this) { permissionGranted }

    /** Whether the lifecycle-safe overlay scaffold currently reports present/connected. */
    fun isOverlayPresent(): Boolean = synchronized(this) { overlayPresent }

    /** The current bounded agent-status state published by the chat/agent loop. */
    fun agentState(): OverlayAgentState = synchronized(this) { agentState }

    /**
     * The current sanitized, bounded agent-status text (never longer than
     * [MAX_STATUS_TEXT_LENGTH] characters, control characters removed). A caller
     * may render this on a single-line status surface; it never contains
     * screenshots, prompts, tokens, or secrets.
     */
    fun agentStatusText(): String = synchronized(this) { agentStatusText }

    /**
     * Deterministic one-line status label for the current [agentState] (used by
     * the overlay surface when the status text is empty). Pure and device-free.
     */
    fun currentStatusLabel(): String = when (agentState()) {
        OverlayAgentState.IDLE -> "空闲"
        OverlayAgentState.RUNNING -> "智能体运行中…"
        OverlayAgentState.TOOL -> "执行工具…"
        OverlayAgentState.ERROR -> "智能体出错"
        OverlayAgentState.COMPLETED -> "已回复完成"
    }

    /** Called when the system overlay permission is observed granted. */
    fun onPermissionGranted() {
        synchronized(this) { permissionGranted = true }
    }

    /** Called by the overlay service on start (guarded) and on bind/connect. */
    fun onOverlayPresent() {
        synchronized(this) { overlayPresent = true }
    }

    /** Called by the overlay service on stop/destroy, and by tests. */
    fun onOverlayGone() {
        synchronized(this) { overlayPresent = false }
    }

    /**
     * Publishes a sanitized agent-status update from the chat/agent loop. The
     * [state] is the coarse live state; [text] is an optional bounded plain
     * status line. [text] is sanitized (control characters collapsed) and capped
     * to [MAX_STATUS_TEXT_LENGTH] characters before being stored, so it can
     * never carry a long prompt, token, screenshot or secret onto the overlay.
     */
    fun updateAgentStatus(state: OverlayAgentState, text: String = "") {
        synchronized(this) {
            agentState = state
            agentStatusText = sanitizeStatusText(text)
        }
    }

    /**
     * Pure escaping/bounding for a status line: collapses all runs of control
     * characters / newlines to a single space, trims, and caps to
     * [MAX_STATUS_TEXT_LENGTH] characters. Returns an empty string for blank
     * input. Device-free and deterministic (unit-tested).
     */
    fun sanitizeStatusText(text: String): String {
        if (text.isBlank()) return ""
        val collapsed = text.fold(StringBuilder(text.length)) { acc, c ->
            if (c.isISOControl()) {
                if (acc.isNotEmpty() && acc.last() != ' ') acc.append(' ')
            } else {
                acc.append(c)
            }
            acc
        }.toString().trim()
        return if (collapsed.length > MAX_STATUS_TEXT_LENGTH) {
            collapsed.substring(0, MAX_STATUS_TEXT_LENGTH)
        } else {
            collapsed
        }
    }

    /** Reset to the safe default (used in tests). */
    fun reset() {
        synchronized(this) {
            permissionGranted = false
            overlayPresent = false
            agentState = OverlayAgentState.IDLE
            agentStatusText = DEFAULT_STATUS_TEXT
        }
    }
}

/**
 * Coarse, bounded agent-status state rendered by the live overlay status
 * surface (Phase 26). Distinct from the real-time loop internals; it is a small
 * closed enum so the surface never needs freeform content.
 */
enum class OverlayAgentState {
    /** No active agent turn; the overlay shows the idle status line. */
    IDLE,

    /** An agent turn has started and the model is streaming/reasoning. */
    RUNNING,

    /** A tool is being executed during the agent loop. */
    TOOL,

    /** The agent turn failed; an honest error line is shown. */
    ERROR,

    /** The agent turn produced a reply and completed. */
    COMPLETED,
}
