package com.clawmate.app.accessibility

/**
 * The *allowlisted* AutoAgent device-action domain (Phase 19, migration row B1).
 *
 * This mirrors the GUI / HDC actions that exist in the HarmonyOS product
 * (`AutoAgentAbility.ets`, `MobileGuiTaskTool.ets`) as *typed, safe request
 * models* that an Android controlled-accessibility boundary may *consider*.
 *
 * Safety contract (AGENTS.md / Phase 19 objective):
 *  - the allowlist is closed and explicit — only these action ids are ever
 *    recognised; anything else is rejected as [RejectedNotAllowlisted];
 *  - the [params] map is strictly bounded to the shape each action declares —
 *    no free-form command strings, no `adb`/`hdc`/`Runtime.exec` payloads, no
 *    hidden-input or document-of-arbitrary-codes;
 *  - recognition alone never *executes* anything: dispatch is performed by the
 *    [AccessibilityGate], which returns an honest typed result
 *    (NOT_CONFIGURED / BLOCKED / rejected) and never fabricates success.
 *
 * No server bridge, no shell, no VLM call, no screenshot payload is carried
 * here — this is only the declarative domain used to make rejections and
 * availability decisions testable on the host.
 */

/** The closed set of actions the boundary is willing to consider. */
enum class AccessibilityActionId(val wireName: String) {
    /** Tap at normalized screen coordinates. */
    TAP("tap"),
    /** Type text into a focused input. */
    TYPE_TEXT("type_text"),
    /** Swipe between two normalized points. */
    SWIPE("swipe"),
    /** Launch another app (cross-app control — gated, never auto-run). */
    OPEN_APP("open_app"),
    /** Capture the current screen (screenshot — gated, never auto-exfiltrated). */
    CAPTURE_SCREEN("capture_screen"),
}

/**
 * A recognised action with *bounded* parameters.
 *
 * [params] is allowed to contain only the fields each action declares (see
 * [AccessibilityActionValidation]); an attempt to attach unknown / exotic
 * fields makes the action invalid and it is rejected before any dispatch.
 */
data class AccessibilityAction(
    val id: AccessibilityActionId,
    val params: Map<String, Any?> = emptyMap(),
)

/** The wire-name → id map used to recognise/disallow by string name. */
object AccessibilityActionAllowlist {
    val BY_NAME: Map<String, AccessibilityActionId> =
        AccessibilityActionId.entries.associateBy { it.wireName }

    /** True only when [name] is one of the closed allowlisted ids. */
    fun contains(name: String): Boolean = BY_NAME.containsKey(name.trim())
}

/** Outcome of validating a raw action request against the allowlist + shape. */
sealed class AccessibilityActionValidation {
    /** The request is a well-formed allowlisted action. */
    data class Valid(val action: AccessibilityAction) : AccessibilityActionValidation()

    /** Unknown action id (not on the closed allowlist). */
    data class RejectedNotAllowlisted(val requested: String) : AccessibilityActionValidation()

    /** The id is recognised but carries an unsupported/oversized payload. */
    data class RejectedUnsupportedPayload(val id: AccessibilityActionId, val reason: String) :
        AccessibilityActionValidation()

    companion object {
        /**
         * Parses a raw action (wire name + params) into a validated
         * [AccessibilityAction] or an explicit rejection. Pure and deterministic —
         * no platform/network.
         */
        fun validate(name: String, params: Map<String, Any?>): AccessibilityActionValidation {
            val id = AccessibilityActionAllowlist.BY_NAME[name.trim()]
                ?: return RejectedNotAllowlisted(name)
            val allowed = AccessibilityActionShape.allowedFields(id)
            val unknown = params.keys.filterNot { it in allowed }
            if (unknown.isNotEmpty()) {
                return RejectedUnsupportedPayload(
                    id,
                    "action '${id.wireName}' does not allow payload fields: ${unknown.sorted().joinToString(",")}",
                )
            }
            if (params.size > AccessibilityActionShape.MAX_PARAM_FIELDS) {
                return RejectedUnsupportedPayload(
                    id,
                    "action '${id.wireName}' payload exceeds the bound of ${AccessibilityActionShape.MAX_PARAM_FIELDS} fields",
                )
            }
            return Valid(AccessibilityAction(id, params))
        }
    }
}

/** Per-id allowed parameter-field sets (the closed, safe shape). */
object AccessibilityActionShape {
    private val TAP_FIELDS = setOf("x", "y")
    private val TYPE_FIELDS = setOf("text")
    private val SWIPE_FIELDS = setOf("from_x", "from_y", "to_x", "to_y")
    private val OPEN_APP_FIELDS = setOf("package_name")
    private val CAPTURE_FIELDS = emptySet<String>() // no payload — never carries screenshot bytes

    fun allowedFields(id: AccessibilityActionId): Set<String> = when (id) {
        AccessibilityActionId.TAP -> TAP_FIELDS
        AccessibilityActionId.TYPE_TEXT -> TYPE_FIELDS
        AccessibilityActionId.SWIPE -> SWIPE_FIELDS
        AccessibilityActionId.OPEN_APP -> OPEN_APP_FIELDS
        AccessibilityActionId.CAPTURE_SCREEN -> CAPTURE_FIELDS
    }

    /** Hard cap on the number of params to keep the shape genuinely bounded. */
    const val MAX_PARAM_FIELDS: Int = 4
}
