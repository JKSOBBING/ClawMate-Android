package com.clawmate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 *  - 1dp [ClawMateDesign.Colors.Border] outline
 *  - [ClawMateDesign.Shape.Control] (12dp) radius
 *  - 13sp text, 13sp muted label, placeholder in [ClawMateDesign.Colors.Disabled]
 *  - [ClawMateDesign.Size.InputHeight] (42dp) at font scale 1.0; the field
 *    grows for multi-line content
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
) {
    // 12dp vertical padding + 18sp line height == 42dp (ClawMateDesign.Size.InputHeight)
    // at font scale 1.0; it stretches honestly with the user's font scale.
    val fieldModifier = Modifier
        .fillMaxWidth()
        .then(
            if (singleLine) {
                Modifier.horizontalScroll(rememberScrollState())
            } else {
                Modifier
            }
        )
        .background(
            if (enabled) ClawMateDesign.Colors.Input else ClawMateDesign.Colors.InputDisabled,
            RoundedCornerShape(ClawMateDesign.Shape.Control),
        )
        .border(1.dp, ClawMateDesign.Colors.Border, RoundedCornerShape(ClawMateDesign.Shape.Control))
        .padding(horizontal = 12.dp, vertical = 12.dp)
        .heightIn(min = ClawMateDesign.Size.InputHeight)

    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = ClawMateDesign.Colors.TextMuted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else maxLines,
            textStyle = TextStyle(
                color = if (enabled) ClawMateDesign.Colors.Text else ClawMateDesign.Colors.Disabled,
                fontSize = 13.sp,
                lineHeight = 18.sp,
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
                            color = ClawMateDesign.Colors.Disabled,
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
