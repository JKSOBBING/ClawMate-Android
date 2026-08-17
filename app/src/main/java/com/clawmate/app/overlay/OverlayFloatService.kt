package com.clawmate.app.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.LinearLayout
import com.clawmate.app.log.AppLogger

/**
 * Lifecycle-safe float/edge overlay scaffold (Phase 20, migration row C1),
 * upgraded in **Phase 26** to render the live, bounded agent status.
 *
 * Migration of the HarmonyOS `SYSTEM_FLOAT_WINDOW` two-layer overlay
 * (`pages/FloatWindow.ets` + `pages/EdgeFlowWindow.ets`, owned by
 * `entryability/EntryAbility.ets`). Harmony renders real agent status + an
 * edge-glow effect above other apps. Android cannot provide that silently: it
 * needs the `SYSTEM_ALERT_WINDOW` runtime permission granted by the system
 * plus an explicit opt-in plus a real safe renderer. This service keeps every
 * safety gate:
 *
 *  - **It never starts silently.** On [onStartCommand] it derives the
 *    [OverlayGate] from the observed opt-in + observed system permission + the
 *    status-only renderer, applies [OverlayServicePolicy], and returns
 *    [START_NOT_STICKY] so the system never restarts it. When the decision is
 *    [OverlayServiceDecision.Stop] it records the honest state and [stopSelf]s
 *    immediately.
 *  - **It renders only the honest, bounded status surface** (Phase 26). On a
 *    READY gate it shows a single `TextView` that reflects the live
 *    [OverlayStateRegistry] agent status — a sanitized, ≤160-char line plus the
 *    coarse idle/running/tool/error/completed label. A refresh loop keeps it up
 *    to date *only while the explicitly started service is alive*, and re-checks
 *    the gate each tick: if the gate is no longer READY it stops itself.
 *  - **It never captures screens, never intercepts input, never executes
 *    commands, and never claims edge-flow support.** The status view uses
 *    `FLAG_NOT_TOUCHABLE` (cannot receive touch), no screen-capture flag, no
 *    shell/adb/hdc, and the status text is the registry's already-bounded value
 *    (no screenshots, prompts, tokens or secrets).
 *
 * The real availability decision is made by [OverlayGate] / [OverlayServicePolicy]
 * against observed state; this service is the compliant status-only surface the
 * gate authorises — not an executor and not the Harmony edge-flow shader/GUI
 * control.
 */
class OverlayFloatService : Service() {

    private val windowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }
    private var statusSurfaceView: ViewGroup? = null
    private var statusText: TextView? = null

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val refreshTickRunnable = object : Runnable {
        override fun run() {
            if (!refreshStatusSurface()) {
                // Gate is no longer READY (or surface was lost): stop now.
                stopSelf()
                return
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // NEVER restart silently: the boundary must be honestly READY to be
        // allowed to live, and even then only to render a bounded status surface.
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
                OverlayStateRegistry.onPermissionGranted()
                OverlayStateRegistry.onOverlayPresent()
                AppLogger.i(LOG_TAG, "overlay status surface rendered (bounded agent status, no capture/input/commands)")
                renderStatusSurface()
                // Keep refreshing only while this explicitly-started service is
                // alive; never let the system silently restart us afterwards.
                if (statusSurfaceView != null) {
                    mainHandler.postDelayed(refreshTickRunnable, REFRESH_INTERVAL_MS)
                }
                return START_NOT_STICKY
            }
        }
    }

    /**
     * Returns true if the surface is still live and the gate is still READY
     * (so the refresh should continue), false to stop. Reads the registry's
     * bounded status of plain text and pushes it onto the status line.
     */
    private fun refreshStatusSurface(): Boolean {
        val view = statusSurfaceView ?: return false
        val gate = OverlayGate(
            optedIn = OverlayCapabilityFlags.overlayOptedIn,
            overlayPermissionGranted = OverlayStateRegistry.isPermissionGranted(),
            rendererConfigured = OverlayCapabilityFlags.rendererConfigured,
        )
        if (gate.status != OverlayGateStatus.READY) {
            AppLogger.w(LOG_TAG, "overlay gate no longer READY; stopping status render")
            return false
        }
        statusText?.let {
            // Only the sanitized, bounded registry text + coarse label — never
            // screenshots, prompts, tokens or secrets.
            val detail = OverlayStateRegistry.agentStatusText()
            val label = OverlayStateRegistry.currentStatusLabel()
            it.text = if (detail.isBlank()) label else "$label · $detail"
        }
        return true
    }

    /**
     * Adds a single, genuinely un-touched status view via
     * `TYPE_APPLICATION_OVERLAY`. It carries only the sanitized bounded status
     * line and **no touch interception** (`FLAG_NOT_TOUCHABLE`), no screenshot
     * data, no user content and no dynamic content beyond the registry value.
     */
    private fun renderStatusSurface() {
        if (statusSurfaceView != null) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66101827.toInt())
            elevation = 8f
            setPadding(10, 6, 10, 6)
        }
        val text = TextView(this).apply {
            text = OverlayStateRegistry.currentStatusLabel()
            textSize = 11f
            setTextColor(0xFFF0F8FAFC.toInt())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
            includeFontPadding = false
        }
        statusText = text
        container.addView(
            text,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE: the surface never takes
            // focus and never intercepts any input.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 12
            y = 24
        }
        runCatching {
            windowManager.addView(container, params)
            statusSurfaceView = container
        }.onFailure { e ->
            AppLogger.e(LOG_TAG, "failed to add overlay status surface: $e")
            statusSurfaceView = null
            statusText = null
            OverlayStateRegistry.onOverlayGone()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshTickRunnable)
        statusSurfaceView?.let {
            runCatching { windowManager.removeView(it) }
            statusSurfaceView = null
        }
        statusText = null
        OverlayStateRegistry.onOverlayGone()
        AppLogger.i(LOG_TAG, "overlay status service destroyed; state cleared")
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "ClawMateOverlay"

        /** How often the live status line is refreshed from [OverlayStateRegistry]. */
        private const val REFRESH_INTERVAL_MS: Long = 500L
    }
}
