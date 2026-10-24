package com.clawmate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.TextUnit
import com.clawmate.app.ui.theme.HarmonyTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.ui.theme.ClawMateDesign

/**
 * Shared text input for form fields on every ClawMate page.
 *
 * Single source of truth for the input treatment, mirroring the HarmonyOS
 * settings field pattern (FieldLabel above the input, see
 * SettingsCenterControls.ets):
 *  - fill [ClawMateDesign.Colors.Input], [ClawMateDesign.Colors.InputDisabled]
 *    when disabled
 *  - Borderless source input fill
 *  - [ClawMateDesign.Shape.Control] (12dp) radius
 *  - 13sp text, 13sp muted label, placeholder in [ClawMateDesign.Colors.Disabled]
 *  - [ClawMateDesign.Size.InputHeight] (42dp) at font scale 1.0; the field
 *    uses the source 86dp multiline variant
 *
 * Pages must not restyle text fields per-screen; wrap this composable instead.
 */
@Composable
fun ClawMateInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "",
    placeholder: String = "",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    onFocus: (Boolean) -> Unit = {},
    fieldFocusRequester: FocusRequester? = null,
) {
    // The source bounds the complete field, including its internal text padding.
    val fieldModifier = Modifier
        .fillMaxWidth()
        .fillMaxSize()
        .padding(horizontal = 12.dp, vertical = if (singleLine) 8.dp else 10.dp)
        .onFocusChanged { onFocus(it.isFocused) }
        .then(if (fieldFocusRequester != null) Modifier.focusRequester(fieldFocusRequester) else Modifier)

    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = HarmonyTheme.text("#6F7684"),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(if (singleLine) 42.dp else 86.dp)
            .background(HarmonyTheme.background(if (enabled) "#F2F5FC" else "#E6EAF2"), RoundedCornerShape(ClawMateDesign.Shape.Control))
            .then(if (label.isNotEmpty()) Modifier.testTag("clawmate-field:$label") else Modifier)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            cursorBrush = SolidColor(HarmonyTheme.text("#4F68FF")),
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else maxLines,
            textStyle = TextStyle(
                color = HarmonyTheme.text(if (enabled) "#11182F" else "#7B8290"),
                fontSize = 13.sp,
                lineHeight = if (singleLine) TextUnit.Unspecified else 20.sp,
            ),
            modifier = fieldModifier,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 13.sp,
                            color = HarmonyTheme.mutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        }
    }
}
