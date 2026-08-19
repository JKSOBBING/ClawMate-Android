package com.clawmate.app.accessibility

/**
 * Phase 47 — a tiny, thread-safe holder for the *current* explicit AutoAgent
 * accessibility opt-in, so the agent's GUI tool boundary can build an honest
 * gate from the live fact without reaching for an Android settings store at
 * tool-execution time.
 *
 * It defaults to `false` (REJECTED) — matching the safe default of
 * [com.clawmate.app.settings.domain.AppSettings.accessibilityOptIn] — and is
 * set only when the Settings layer observes/persists the user's explicit
 * opt-in (see [com.clawmate.app.settings.ui.SettingsViewModel]).
 *
 * This is deliberately a *reflector* of a fact the Settings layer already owns
 * — on alone is never sufficient. Even when this returns `true`, the gate still
 * requires the system AccessibilityService to be connected and the foreground
 * package to be allowlisted before any action is attempted.
 */
object AccessibilityOptInHolder {

    @Volatile
    private var optedIn: Boolean = false

    /** Reflect the Settings layer's observed explicit opt-in. */
    fun set(value: Boolean) {
        synchronized(this) { optedIn = value }
    }

    /** Whether the user has explicitly opted in to the AutoAgent safe-action boundary. */
    fun isOptedIn(): Boolean = synchronized(this) { optedIn }

    /** Reset to the safe default (used in tests). */
    fun reset() {
        synchronized(this) { optedIn = false }
    }
}
