package com.clawmate.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Color tokens (light theme baseline).
val BlueViolet700 = Color(0xFF5361E0)
val BlueViolet500 = Color(0xFF6E7BEA)
val BlueViolet300 = Color(0xFF9BA5F0)
val BlueViolet200 = Color(0xFFD7DBF7)
val Ink = Color(0xFF151A28)
val DeepInk = Color(0xFF121212)
val NearWhite = Color(0xFFF7F8FC)
val White = Color(0xFFFFFFFF)

val ClawMateTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
)
