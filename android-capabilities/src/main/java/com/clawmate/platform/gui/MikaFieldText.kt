package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.ambiguousFieldReadback

/** Mika placeholderOf uses the displayed hint first; hintText can be a different suggestion. */
internal fun mikaInputPlaceholder(shown: String?, hint: String?, showingHint: Boolean): String? =
    if (!showingHint) null else shown?.trim()?.ifEmpty { null } ?: hint?.trim()?.ifEmpty { null }

/** Port of Mika ScreenReader.fieldText; sensitive fields never expose their content. */
internal fun mikaFieldText(text: String?, showingHint: Boolean, sensitive: Boolean,
    placeholder: String?, typing: String?,
): String? {
    if (sensitive) return null
    if (showingHint) return ""
    val value = text ?: return ""
    val shown = value.trim()
    if (typing != null && shown == typing.trim()) return value
    if (placeholder != null && shown == placeholder.trim()) return ""
    if (ambiguousFieldReadback(shown, placeholder, typing)) return null
    return value
}
