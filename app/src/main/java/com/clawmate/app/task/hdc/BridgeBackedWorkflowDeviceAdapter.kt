package com.clawmate.app.task.hdc

/**
 * [WorkflowDeviceAdapter] backed by a [HdcBridgeClient]. It performs the safe
 * protocol actions only:
 *  - [health]                        -> live `GET /api/health`
 *  - [startApp] / [screenshot] / [guiAction] / [executeDeciderAction]
 *                                     -> `POST /api/workflow` protocol calls
 *
 * It never sends `/api/run_cmd` and never builds or runs a command string. It
 * holds no secrets and logs nothing. `isConfigured` becomes true only after a
 * live `health()` reply reports a connected, control-ready bridge — never
 * assumed. Until that succeeds, every action still routes through the bridge
 * but the adapter truthfully reports non-ok health as [HdcActionResult.BridgeError]
 * (or a [HdcActionResult.TransportError]).
 */
class BridgeBackedWorkflowDeviceAdapter(
    private val client: HdcBridgeClient,
) : WorkflowDeviceAdapter {

    @Volatile
    private var healthy: Boolean = false

    @Volatile
    private var lastHealth: HdcBridgeHealth? = null

    override val isConfigured: Boolean
        get() = healthy && lastHealth?.controlReady == true && lastHealth?.statusEnum == HdcBridgeStatus.OK

    /**
     * Runs a live health check. On an `ok` + control-ready reply it flags the
     * adapter configured; otherwise it records the honest failure. Returns a
     * typed result so the caller (settings health button or runner) can surface
     * it directly.
     */
    suspend fun refreshHealth(): HdcActionResult {
        return when (val result = client.health()) {
            is HdcBridgeResult.Health -> {
                lastHealth = result.value
                if (result.value.statusEnum == HdcBridgeStatus.OK && result.value.controlReady) {
                    healthy = true
                    HdcActionResult.Success(
                        HdcBridgeResponse(
                            status = result.value.status,
                            message = result.value.message,
                            action = "health",
                        )
                    )
                } else {
                    healthy = false
                    HdcActionResult.BridgeError(
                        HdcBridgeResponse(
                            status = result.value.status,
                            message = result.value.message ?: "HDC bridge reports not connected",
                            action = "health",
                        )
                    )
                }
            }
            is HdcBridgeResult.Response -> {
                healthy = false
                lastHealth = null
                HdcActionResult.BridgeError(result.value)
            }
            is HdcBridgeResult.Error -> {
                healthy = false
                lastHealth = null
                HdcActionResult.TransportError(result.kind, result.message)
            }
        }
    }

    override suspend fun health(): HdcActionResult = refreshHealth()

    override suspend fun startApp(appName: String?, packageName: String?, resetFirst: Boolean): HdcActionResult {
        val payload = buildMap {
            appName?.let { put("app_name", it) }
            packageName?.let { put("package_name", it) }
            put("reset_first", resetFirst)
        }
        return requestAction("app_start", payload)
    }

    override suspend fun screenshot(factor: Double): HdcActionResult {
        val payload = mapOf(
            "factor" to factor,
            "style" to (if (factor <= 0.25) "local" else "mobiagent"),
            "manage_overlay" to false,
        )
        return requestAction("screenshot", payload)
    }

    override suspend fun guiAction(payload: Map<String, Any?>): HdcActionResult =
        requestAction("gui_action", payload)

    override suspend fun executeDeciderAction(responseText: String, width: Int, height: Int): HdcActionResult {
        val payload = mapOf("response" to responseText, "width" to width, "height" to height)
        return requestAction("execute_decider_action", payload)
    }

    private suspend fun requestAction(action: String, payload: Map<String, Any?>): HdcActionResult =
        when (val result = client.workflow(action, payload)) {
            is HdcBridgeResult.Response -> {
                if (result.value.statusEnum == HdcBridgeStatus.OK) {
                    HdcActionResult.Success(result.value)
                } else {
                    HdcActionResult.BridgeError(result.value)
                }
            }
            is HdcBridgeResult.Health -> HdcActionResult.TransportError(
                HdcBridgeErrorKind.MALFORMED_JSON,
                "workflow action '$action' returned a health-shaped body",
            )
            is HdcBridgeResult.Error -> HdcActionResult.TransportError(result.kind, result.message)
        }
}
