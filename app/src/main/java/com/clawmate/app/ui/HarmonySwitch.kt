package com.clawmate.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clawmate.app.ui.theme.HarmonyTheme

/** Explicit 44×26 source switch. Platform thumb animation remains an Android approximation. */
@Composable
fun HarmonySwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, selectedColor: Color = HarmonyTheme.background("#276EF1"),
) {
    val left by animateDpAsState(if (checked) 20.dp else 2.dp, tween(180), label = "switch thumb")
    Box(modifier.size(44.dp, 26.dp).alpha(if (enabled) 1f else .6f)
        .background(if (checked) selectedColor else HarmonyTheme.background("#E5E7EB"), RoundedCornerShape(13.dp))
        .toggleable(checked, interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        Box(Modifier.padding(start = left, top = 2.dp).size(22.dp).background(Color.White, CircleShape))
    }
}
