package com.clawmate.app.accessibility

/**
 * Phase 22 — the *safe semantic* AutoAgent device-action allowlist.
 *
 * This is the Android AccessibilityService executor boundary that corresponds to
 * the HarmonyOS `MobileGuiTask` / `AgentAction` device-action surface
 * (`utils/MobileGuiTaskTool.ets`, `utils/AgentActionParser.ets`). Where the
 * Harmony side bridges its `click` / `swipe` / `open_app` actions out to HDC for
 * arbitrary coordinates and cross-app control, Android gets a **tightly
 * controlled, on-device** executor for a minimal, explicit allowlist of
 * *semantic* actions that can be performed safely without gestures, screenshots,
 * shell or raw key injection:
 *
 *  - [SafeAccessibilityActionId.CLICK_TEXT] — click a window node by its text.
 *  - [SafeAccessibilityActionId.CLICK_CONTENT_DESCRIPTION] — click a node by its
 *    content description.
 *  - [SafeAccessibilityActionId.BACK] — semantic back (node/global back
 *    navigation) within the allowlisted foreground app.
 *  - [SafeAccessibilityActionId.SCROLL_FORWARD] / [SCROLL_BACKWARD] — scroll a
 *    scrollable node forward / backward.
 *  - [SafeAccessibilityActionId.GLOBAL_BACK] / [GLOBAL_HOME] —
 *    `AccessibilityService.performGlobalAction` back / home (safe, framework
 *    sanctioned, no arbitrary keys).
 *
 * Safety contract (Phase 22 objective):
 *  - the allowlist is closed and explicit — anything else is rejected;
 *  - text / content-description / package inputs are strictly length-bounded
 *    ([SafeAccessibilityActionContract]);
 *  - this file is **declarative only**: it never performs anything by itself.
 *    Execution is driven exclusively through the gated, package-validating
 *    executor ([ControlledSafeAccessibilityDispatcher]) in
 *    [SafeAccessibilityExecutor]; the real service performs a node/global action
 *    only when invoked through that typed executor, and only after the foreground
 *    package is validated on every invocation.
 *
 * No arbitrary coordinate injection, no raw key events, no shell / adb / hdc, no
 * screenshots, no hidden input and no cross-app data extraction are represented
 * here.
 */

/** Closed set of safe, semantic actions the executor is willing to perform. */
enum class SafeAccessibilityActionId(val wireName: String) {
    /** Click a node whose `text` equals (or contains) [SafeAccessibilityAction.targetText]. */
    CLICK_TEXT("click_text"),
    /** Click a node whose content-description equals [SafeAccessibilityAction.targetContentDescription]. */
    CLICK_CONTENT_DESCRIPTION("click_content_description"),
    /** Semantic back navigation within the allowlisted foreground app. */
    BACK("back"),
    /** Scroll the active scrollable node forward (toward later content). */
    SCROLL_FORWARD("scroll_forward"),
    /** Scroll the active scrollable node backward (toward earlier content). */
    SCROLL_BACKWARD("scroll_backward"),
    /** `performGlobalAction(GLOBAL_ACTION_BACK)` — framework back as a global action. */
    GLOBAL_BACK("global_back"),
    /** `performGlobalAction(GLOBAL_ACTION_HOME)` — framework home as a global action. */
    GLOBAL_HOME("global_home");

    companion object {
        private val BY_NAME: Map<String, SafeAccessibilityActionId> =
            entries.associateBy { it.wireName }

        /** True only when [name] is one of the closed allowlisted ids. */
        fun contains(name: String): Boolean = BY_NAME.containsKey(name.trim())

        /** The closed wire-name → id map (used to recognise/disallow by string name). */
        fun byNameOrDefault(name: String): SafeAccessibilityActionId? = BY_NAME[name.trim()]
    }
}

/**
 * A validated, semantically-typed action request for the safe executor.
 *
 * [targetText] and [targetContentDescription] are mutually exclusive and are
 * meaningful only for [SafeAccessibilityActionId.CLICK_TEXT] and
 * [SafeAccessibilityActionId.CLICK_CONTENT_DESCRIPTION] respectively. Every
 * request may optionally carry a [targetPackage]; when present it must be on the
 * allowlist and equal the *current foreground package*, which is validated again
 * immediately before an action is performed.
 */
data class SafeAccessibilityAction(
    val id: SafeAccessibilityActionId,
    val targetText: String? = null,
    val targetContentDescription: String? = null,
    val targetPackage: String? = null,
) {

    /**
     * True when this action targets text/content-description content (needs a
     * matched node) as opposed to a framework/global or scroll action.
     */
    val isNodeTargeted: Boolean
        get() = id == SafeAccessibilityActionId.CLICK_TEXT ||
            id == SafeAccessibilityActionId.CLICK_CONTENT_DESCRIPTION

    /** A stable, human-readable "what would happen" description (honest). */
    val performedDescription: String
        get() = when (id) {
            SafeAccessibilityActionId.CLICK_TEXT -> "点击文本节点[${targetText.orEmpty()}]"
            SafeAccessibilityActionId.CLICK_CONTENT_DESCRIPTION ->
                "点击内容描述节点[${targetContentDescription.orEmpty()}]"
            SafeAccessibilityActionId.BACK -> "返回"
            SafeAccessibilityActionId.SCROLL_FORWARD -> "向前滚动"
            SafeAccessibilityActionId.SCROLL_BACKWARD -> "向后滚动"
            SafeAccessibilityActionId.GLOBAL_BACK -> "全局返回"
            SafeAccessibilityActionId.GLOBAL_HOME -> "回到桌面"
        }
}

/** Closed, length-bounded shape contract for the safe action domain. */
object SafeAccessibilityActionContract {

    /** Hard cap on a clickable `text` target length (bounded input). */
    const val MAX_TEXT_LENGTH: Int = 200

    /** Hard cap on a clickable content-description target length (bounded input). */
    const val MAX_CONTENT_DESCRIPTION_LENGTH: Int = 200

    /** Hard cap on a target package identifier length (bounded input). */
    const val MAX_PACKAGE_LENGTH: Int = 512

    /** The length bounds per action (used for the bounded-input rejection message). */
    fun boundInfo(id: SafeAccessibilityActionId): String = when (id) {
        SafeAccessibilityActionId.CLICK_TEXT -> "text ≤ $MAX_TEXT_LENGTH chars"
        SafeAccessibilityActionId.CLICK_CONTENT_DESCRIPTION ->
            "content_description ≤ $MAX_CONTENT_DESCRIPTION_LENGTH chars"
        else -> "targetPackage ≤ $MAX_PACKAGE_LENGTH chars"
    }
}

/** Outcome of validating a raw action request against the safe allowlist + bounds. */
sealed class SafeAccessibilityActionValidation {
    /** The request is well-formed, allowlisted and within every bound. */
    data class Valid(val action: SafeAccessibilityAction) : SafeAccessibilityActionValidation()

    /** Unknown action id (not on the closed safe allowlist). */
    data class RejectedNotAllowlisted(val requested: String) : SafeAccessibilityActionValidation()

    /** The id is recognised but a payload field is absent/unknown/oversized. */
    data class RejectedUnsupportedPayload(
        val id: SafeAccessibilityActionId,
        val reason: String,
    ) : SafeAccessibilityActionValidation()

    companion object {
        /**
         * Parses a raw action (wire name + params) into a validated
         * [SafeAccessibilityAction] or an explicit rejection. Pure and
         * deterministic — no platform/network. Params map keys: `text`,
         * `content_description`, `package_name`.
         */
        fun validate(
            name: String,
            params: Map<String, Any?>,
        ): SafeAccessibilityActionValidation {
            val id = SafeAccessibilityActionId.byNameOrDefault(name)
                ?: return RejectedNotAllowlisted(name)

            val allowedKeys = setOf("text", "content_description", "package_name")
            val unknown = params.keys.filterNot { it in allowedKeys }
            if (unknown.isNotEmpty()) {
                return RejectedUnsupportedPayload(
                    id,
                    "safe action '${id.wireName}' does not allow payload fields: ${unknown.sorted().joinToString(",")}",
                )
            }
            if (params.size > 3) {
                return RejectedUnsupportedPayload(
                    id,
                    "safe action '${id.wireName}' payload exceeds the bound of 3 fields",
                )
            }

            val text = params["text"] as? String
            val contentDescription = params["content_description"] as? String
            val targetPackage = params["package_name"] as? String

            if (text != null && text.length > SafeAccessibilityActionContract.MAX_TEXT_LENGTH) {
                return RejectedUnsupportedPayload(
                    id,
                    "safe action '${id.wireName}' text exceeds ${SafeAccessibilityActionContract.MAX_TEXT_LENGTH} chars",
                )
            }
            if (contentDescription != null &&
                contentDescription.length > SafeAccessibilityActionContract.MAX_CONTENT_DESCRIPTION_LENGTH
            ) {
                return RejectedUnsupportedPayload(
                    id,
                    "safe action '${id.wireName}' content_description exceeds " +
                        SafeAccessibilityActionContract.MAX_CONTENT_DESCRIPTION_LENGTH + " chars",
                )
            }
            if (targetPackage != null && targetPackage.length > SafeAccessibilityActionContract.MAX_PACKAGE_LENGTH) {
                return RejectedUnsupportedPayload(
                    id,
                    "safe action '${id.wireName}' package_name exceeds ${SafeAccessibilityActionContract.MAX_PACKAGE_LENGTH} chars",
                )
            }

            return when (id) {
                SafeAccessibilityActionId.CLICK_TEXT ->
                    if (text.isNullOrBlank()) {
                        RejectedUnsupportedPayload(id, "click_text requires a non-empty 'text'")
                    } else {
                        Valid(SafeAccessibilityAction(id, targetText = text.trim(), targetPackage = targetPackage))
                    }

                SafeAccessibilityActionId.CLICK_CONTENT_DESCRIPTION ->
                    if (contentDescription.isNullOrBlank()) {
                        RejectedUnsupportedPayload(id, "click_content_description requires a non-empty 'content_description'")
                    } else {
                        Valid(
                            SafeAccessibilityAction(
                                id,
                                targetContentDescription = contentDescription.trim(),
                                targetPackage = targetPackage,
                            ),
                        )
                    }

                else -> Valid(SafeAccessibilityAction(id, targetPackage = targetPackage))
            }
        }
    }
}
