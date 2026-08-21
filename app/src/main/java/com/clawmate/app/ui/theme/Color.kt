package com.clawmate.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Color tokens mirrored from HarmonyOS resources and page styles.
val BlueViolet700 = Color(0xFF8066FF)
val BlueViolet500 = Color(0xFF6D56E8)
val BlueViolet300 = Color(0xFFA99BFF)
val BlueViolet200 = Color(0xFFE7E2FF)
val Ink = Color(0xFF151A28)
val DeepInk = Color(0xFF121212)
val NearWhite = Color(0xFFF4F8FF)
val White = Color(0xFFFFFFFF)
val TabSelected = Color(0xFF5B5CE2)
val TabIndicator = Color(0xFFEEF0FF)
val MutedText = Color(0xFF747B87)

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
