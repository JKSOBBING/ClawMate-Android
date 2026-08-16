package com.clawmate.app.chat.agent

import com.clawmate.app.chat.cloud.ToolCallWire
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A registry of tools that a ReAct agent may call.
 *
 * Safety contract (AGENTS.md):
 *   - Only *explicit, safe* Android adapters are registered and executed.
 *   - **No arbitrary shell commands.** No `Runtime.exec` / `ProcessBuilder`
 *     with model-controlled strings; no blind UIAutomator reproduction.
 *   - Capabilities that are real in the HarmonyOS product but cannot exist
 *     on Android right now (GUI taps/swipes/text-input, device screenshots via
 *     HDC, system float overlay) are registered as **controlled-disabled**
 *     adapters: [executeToolCall] returns a [ToolResultKind.DISABLED] result
 *     whose message explains the required PC bridge / permission. The model is
 *     told the action needs a bridge rather than receiving a fake success.
 *
 * Ported behaviour from the source `ChatToolRegistry.ets`: argument JSON
 * validation, unknown-tool error payload, result truncation, and a
 * `terminalResultMessage` escape hatch for user-decision tools.
 */
class ToolRegistry {
    private val tools = LinkedHashMap<String, AgentTool>()

    /** Registers (or replaces) a tool by name. */
    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun hasTools(): Boolean = tools.isNotEmpty()

    fun toolNames(): List<String> = tools.keys.toList()

    /** The OpenAI `tools` declaration list for this registry. */
    fun openAiTools(): List<com.clawmate.app.chat.cloud.ToolDefinitionWire> = toOpenAiTools(tools.values.toList())

    /** Looks up a tool by name, or `null` when absent. */
    fun find(name: String): AgentTool? = tools[name]

    /**
     * Validates and executes a model-emitted [call]. Never throws for a
     * benign tool error: failures become a typed [ToolResultKind.ERROR] result
     * with a structured error payload returned to the model.
     */
    suspend fun execute(call: ToolCallWire, context: ToolExecutionContext): ToolExecutionResult {
        val toolName = call.function.name
        val argsText = toolCallArgumentsText(call)

        // 1. Validate arguments are a JSON object (mirrors source validateArguments).
        if (!isValidJsonObject(argsText)) {
            return errorResult(toolName, call.id, argsText, "Tool arguments must be a JSON object.")
        }

        // 2. Unknown tool -> structured error, not a crash.
        val tool = find(toolName)
        if (tool == null) {
            return errorResult(toolName, call.id, argsText, "Tool $toolName does not exist.")
        }

        // 3. Execute inside a try so adapter failures surface as typed results.
        return try {
            val raw = tool.execute(argsText, context)
            val content = truncateResult(raw)
            ToolExecutionResult(
                toolName = toolName,
                toolCallId = call.id,
                argumentsText = argsText,
                content = raw,
                truncatedContent = content,
                traceLine = buildTraceLine(toolName, argsText, content),
                success = true,
                kind = ToolResultKind.OK,
            )
        } catch (e: ControlledDisabledException) {
            // Safe adapter intentionally refused an on-device capability.
            val reason = e.message ?: "capability unavailable on Android"
            val payload = disabledJson(reason)
            ToolExecutionResult(
                toolName = toolName,
                toolCallId = call.id,
                argumentsText = argsText,
                content = payload,
                truncatedContent = truncateResult(payload),
                traceLine = buildTraceLine(toolName, argsText, payload),
                success = false,
                kind = ToolResultKind.DISABLED,
                disabledReason = reason,
                errorPayload = payload,
            )
        } catch (e: ToolTerminalException) {
            ToolExecutionResult(
                toolName = toolName,
                toolCallId = call.id,
                argumentsText = argsText,
                content = e.message ?: "Terminated by user",
                truncatedContent = truncateResult(e.message ?: ""),
                traceLine = buildTraceLine(toolName, argsText, e.message ?: ""),
                success = false,
                kind = ToolResultKind.TERMINAL,
                terminalMessage = e.message ?: "Cancelled by user",
                errorPayload = errorJson(e.message ?: "Cancelled by user"),
            )
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            errorResult(toolName, call.id, argsText, "Tool $toolName failed: $msg")
        }
    }

    private fun errorResult(
        toolName: String,
        toolCallId: String,
        argumentsText: String,
        message: String,
    ): ToolExecutionResult {
        val payload = errorJson(message)
        return ToolExecutionResult(
            toolName = toolName,
            toolCallId = toolCallId,
            argumentsText = argumentsText,
            content = payload,
            truncatedContent = truncateResult(payload),
            traceLine = buildTraceLine(toolName, argumentsText, payload),
            success = false,
            kind = ToolResultKind.ERROR,
            errorPayload = payload,
        )
    }
}

/**
 * Thrown by a safe adapter to refuse an on-device action that requires an
 * external bridge/permission (Harmony GUI/HDC/overlay). The registry converts
 * it into a controlled-disabled [ToolResultKind.DISABLED] result.
 */
class ControlledDisabledException(message: String) : Exception(message)

/** Thrown by an adapter to signal a user-terminal decision (cancel/refuse). */
class ToolTerminalException(message: String) : Exception(message)

/** Builds the single-line trace used in the chat UI. Port of `buildTraceLine`. */
fun buildTraceLine(toolName: String, argumentsText: String, content: String): String =
    "工具：$toolName\n参数：$argumentsText\n结果：$content"

// ---------------------------------------------------------------------------
// Safe default adapters
// ---------------------------------------------------------------------------

/** JSON schema helpers used by the default tools. */
object ToolSchemas {
    fun noParams() = JsonObject(emptyMap())
    fun simpleStringArg(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(description))
            })
        })
    }
}

/**
 * The safe built-in Android adapters for this slice. GUI automation adapters
 * are registered as controlled-disabled stubs (see class docstring).
 */
fun createDefaultToolRegistry(): ToolRegistry = ToolRegistry().apply {

    register(
        AgentTool(
            name = "get_current_datetime",
            description = "Return the current date and time on the Android device.",
            parameters = ToolSchemas.noParams(),
        ) { _, _ ->
            java.time.ZonedDateTime.now().toString()
        },
    )

    register(
        AgentTool(
            name = "get_device_info",
            description = "Return basic read-only information about the device (JVM-compatible so it is deterministically unit-testable).",
            parameters = ToolSchemas.noParams(),
        ) { _, _ ->
            buildJsonObject {
                val osName = System.getProperty("os.name") ?: "unknown"
                val javaVersion = System.getProperty("java.version") ?: "unknown"
                put("os_name", JsonPrimitive(osName))
                put("java_version", JsonPrimitive(javaVersion))
                put("available_processors", JsonPrimitive(Runtime.getRuntime().availableProcessors()))
            }.toString()
        },
    )

    // ------------------------------------------------------------------
    // Controlled-disabled adapters for Harmony GUI / HDC / overlay actions.
    // These are real capabilities in the HarmonyOS product but require a PC
    // bridge (hdc/adb + Appium/UIAutomator) or a runtime permission that is
    // not enabled in this build. They return a typed DISABLED result instead
    // of a fake success.
    // ------------------------------------------------------------------
    registerGuiDisabledTool(
        this,
        name = "gui_screen_tap",
        description = "Tap at normalized screen coordinates on the active app. Requires a PC bridge / HDC-like agent.",
    )
    registerGuiDisabledTool(
        this,
        name = "gui_type_text",
        description = "Type freeform text into the focused input. Requires a PC bridge / HDC-like agent.",
    )
    registerGuiDisabledTool(
        this,
        name = "gui_swipe",
        description = "Swipe between normalized coordinates. Requires a PC bridge / HDC-like agent.",
    )
    registerGuiDisabledTool(
        this,
        name = "capture_screen",
        description = "Capture the current screen to feed a visual model. Requires MediaProjection consent or a PC bridge.",
    )
    registerGuiDisabledTool(
        this,
        name = "open_app",
        description = "Launch another app on the device. Executing cross-app control requires a PC bridge / AccessibilityService.",
    )
    // Phase 20 — the float-overlay status tool reports the *live* overlay gate
    // (opt-in + observed SYSTEM_ALERT_WINDOW grant + renderer). It stays
    // controlled-disabled (no fake overlay is ever drawn), but its message now
    // reflects the actually-observed boundary instead of only a generic note.
    registerOverlayStatusTool(
        this,
        name = "float_overlay_status",
        description = "Show the agent's float overlay status. Requires the overlay (SYSTEM_ALERT_WINDOW) runtime permission observed + a safe renderer.",
    )
}

/** Registers a tool whose execution always surfaces a controlled-disabled result. */
private fun ToolRegistry.registerGuiDisabledTool(registry: ToolRegistry, name: String, description: String) {
    val accessibilityNote = autonomousAvailabilityNote()
    registry.register(
        AgentTool(
            name = name,
            description = description,
            parameters = ToolSchemas.simpleStringArg("arguments for the requested device action (controlled-disabled)"),
        ) { _, _ ->
            throw ControlledDisabledException(
                "「$name」在 Android 端暂不可用：该操作需要 PC 侧桥接（adb/hdc + UIAutomator/Appium）" +
                    "或额外的运行时权限（截图/辅助功能/悬浮窗）。$accessibilityNote 请在电脑端完成该设备操作，或授予相应权限后重试。",
            )
        },
    )
}

/**
 * Builds the AutoAgent accessibility boundary note for the controlled-disabled
 * GUI tools (Phase 19). Reads the *actually-observed* service state from the
 * registry (the lifecycle scaffold records it), so the tool message reflects
 * the live availability instead of only the generic PC-bridge message. It
 * never claims an action ran — it only explains the boundary.
 */
internal fun autonomousAvailabilityNote(): String {
    val connected = com.clawmate.app.accessibility.AccessibilityStateRegistry.isServiceConnected()
    return if (connected) {
        "AutoAgent 辅助功能服务已连接，但当前构建没有安全动作执行器（provider 缺失），动作仍受控禁用。"
    } else {
        "AutoAgent 辅助功能服务未连接（NOT_CONFIGURED/BLOCKED），未执行任何跨应用操作。"
    }
}

/**
 * Registers the `float_overlay_status` tool (Phase 20, row C1) so it reflects
 * the *live* float/edge overlay gate. It never draws an overlay and never fakes
 * a surface — it always returns a controlled-disabled result, but the message
 * explains the actually-observed boundary ([overlayAvailabilityNote]).
 */
private fun ToolRegistry.registerOverlayStatusTool(registry: ToolRegistry, name: String, description: String) {
    registry.register(
        AgentTool(
            name = name,
            description = description,
            parameters = ToolSchemas.simpleStringArg("arguments for the requested float-overlay status (controlled-disabled)"),
        ) { _, _ ->
            // Evaluate the note at execution time so it reflects the live gate.
            val overlayNote = overlayAvailabilityNote()
            throw ControlledDisabledException(
                "「$name」在 Android 端暂不可用：不渲染任何智能体悬浮窗。$overlayNote 如需查看智能体状态，请使用 PC/HDC 桥接（已作为受控禁用状态暴露）。",
            )
        },
    )
}

/**
 * Builds the float/edge overlay boundary note for the controlled-disabled
 * `float_overlay_status` tool (Phase 20). Reads the *actually-observed* overlay
 * gate (opt-in seed + observed system grant from the overlay registry/scaffold,
 * with the always-absent renderer), so the message reflects the live boundary.
 * It never claims an overlay rendered — it only explains the honest gate.
 */
internal fun overlayAvailabilityNote(): String {
    val gate = com.clawmate.app.overlay.OverlayGate(
        optedIn = com.clawmate.app.overlay.OverlayCapabilityFlags.overlayOptedIn,
        overlayPermissionGranted = com.clawmate.app.overlay.OverlayStateRegistry.isPermissionGranted(),
        rendererConfigured = com.clawmate.app.overlay.OverlayCapabilityFlags.rendererConfigured,
    )
    return when (val surface = gate.surface()) {
        is com.clawmate.app.overlay.OverlaySurfaceResult.NotConfigured ->
            "悬浮窗边界未启用（NOT_CONFIGURED）：未渲染任何内容。"
        is com.clawmate.app.overlay.OverlaySurfaceResult.BlockedPermission ->
            "已启用悬浮窗边界，但未观察到系统悬浮窗授权（BLOCKED）：未渲染任何内容。"
        is com.clawmate.app.overlay.OverlaySurfaceResult.BlockedNoRenderer ->
            "系统已授予悬浮窗权限，但当前构建没有安全浮窗渲染器（BLOCKED）：不渲染任何智能体浮层。"
        is com.clawmate.app.overlay.OverlaySurfaceResult.ReadyNeutralSurface ->
            "悬浮窗边界就绪，但仅渲染中性状态浮层；当前没有安全渲染器，不渲染任何智能体内容。"
    }
}

