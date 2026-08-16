package com.clawmate.app.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.LinearLayout
import com.clawmate.app.log.AppLogger

/**
 * Lifecycle-safe float/edge overlay scaffold (Phase 20, migration row C1).
 *
 * Migration of the HarmonyOS `SYSTEM_FLOAT_WINDOW` two-layer overlay
 * (`pages/FloatWindow.ets` + `pages/EdgeFlowWindow.ets`, owned by
 * `entryability/EntryAbility.ets`). That source renders real agent status and
 * an edge-glow effect above other apps. Android cannot provide that silently:
 * it needs the `SYSTEM_ALERT_WINDOW` runtime permission granted by the system
 * plus an explicit opt-in plus a real safe renderer. This scaffold mirrors the
 * Phase 19 `AccessibilityAutoAgentService` restraint:
 *
 *  - **It never starts silently.** On [onStartCommand] it derives the
 *    [OverlayGate] from the observed opt-in + observed system permission + the
 *    (always-absent) renderer, applies [OverlayServicePolicy], and returns
 *    [START_NOT_STICKY] so the system never restarts it. When the decision is
 *    [OverlayServiceDecision.Stop] (the only outcome reachable in this build)
 *    it records the honest state and [stopSelf]s immediately.
 *  - **It renders only the honest neutral status surface.** Only a
 *    [OverlayServiceDecision.RenderNeutralSurface] outcome proceeds to add a
 *    single static `TextView` (no agent content, no captured screen, no
 *    gestures). This build ships no renderer, so that branch is unreachable.
 *  - **No capability is claimed.** It never fakes agent content, never captures
 *    or exfiltrates screens, never performs GUI actions, and never adds any
 *    overlay view with dynamic content.
 *
 * The real availability decision is made by [OverlayGate] / [OverlayServicePolicy]
 * against observed state; this service is the compliant overlay surface the
 * gate authorises — not an executor.
 */
class OverlayFloatService : Service() {

    private val windowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }
    private var neutralSurfaceView: ViewGroup? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // NEVER restart silently: the boundary must be honestly READY to be
        // allowed to live, and even then only to render a neutral surface.
        val gate = OverlayGate(
            optedIn = OverlayCapabilityFlags.overlayOptedIn,
            overlayPermissionGranted = OverlayStateRegistry.isPermissionGranted(),
            rendererConfigured = OverlayCapabilityFlags.rendererConfigured,
        )
        when (val decision = OverlayServicePolicy.decide(gate)) {
            is OverlayServiceDecision.Stop -> {
                AppLogger.i(LOG_TAG, "overlay refused to start: ${decision.reason}")
                OverlayStateRegistry.onOverlayGone()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            is OverlayServiceDecision.RenderNeutralSurface -> {
                // Only reachable when the gate is READY (opt-in + grant +
                // renderer). Render ONLY the static neutral status line.
                OverlayStateRegistry.onPermissionGranted()
                OverlayStateRegistry.onOverlayPresent()
                AppLogger.i(LOG_TAG, "overlay neutral status surface rendered (safe scaffold, no agent content)")
                renderNeutralSurface(decision.surfaceText)
                // Never let the system keep us alive silently after the surface
                // is shown; this scaffold holds no binder clients.
                return START_NOT_STICKY
            }
        }
    }

    /**
     * Adds a single, genuinely un-touched opaque-neutral status view via
     * `TYPE_APPLICATION_OVERLAY`. It carries only [surfaceText] (a static,
     * no-agent-content string) and nothing else: no screenshot data, no user
     * content, no touch interceptors beyond the view's own bounds.
     */
    private fun renderNeutralSurface(surfaceText: String) {
        if (neutralSurfaceView != null) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66101827.toInt())
            elevation = 8f
            setPadding(10, 6, 10, 6)
        }
        container.addView(
            TextView(this).apply {
                text = surfaceText
                textSize = 11f
                setTextColor(0xFFF0F8FAFC.toInt())
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 1
                includeFontPadding = false
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 12
            y = 24
        }
        runCatching {
            windowManager.addView(container, params)
            neutralSurfaceView = container
        }.onFailure { e ->
            AppLogger.e(LOG_TAG, "failed to add neutral overlay surface: $e")
            // If the window cannot be added, do not keep a half-drawn overlay.
            neutralSurfaceView = null
            OverlayStateRegistry.onOverlayGone()
        }
    }

    override fun onDestroy() {
        neutralSurfaceView?.let {
            runCatching { windowManager.removeView(it) }
            neutralSurfaceView = null
        }
        OverlayStateRegistry.onOverlayGone()
        AppLogger.i(LOG_TAG, "overlay scaffold destroyed; state cleared")
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "ClawMateOverlay"
    }
}
