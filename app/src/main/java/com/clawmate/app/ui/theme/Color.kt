package com.clawmate.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Color tokens mirrored from HarmonyOS resources and page styles.
val BlueViolet700 = ClawMateDesign.Colors.PrimaryStrong
val BlueViolet500 = ClawMateDesign.Colors.Accent
val BlueViolet300 = Color(0xFFA99BFF)
val BlueViolet200 = ClawMateDesign.Colors.PrimaryContainer
val Ink = ClawMateDesign.Colors.Text
val DeepInk = Color(0xFF121212)
val NearWhite = ClawMateDesign.Colors.PageBackground
val White = ClawMateDesign.Colors.Surface
val TabSelected = Color(0xFF5B5CE2)
val TabIndicator = Color(0xFFEEF0FF)
val MutedText = ClawMateDesign.Colors.TextSecondary

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
