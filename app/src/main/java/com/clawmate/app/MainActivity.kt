package com.clawmate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.clawmate.app.ui.ClawMateRoot
import com.clawmate.app.ui.theme.ClawMateTheme

/**
 * Single-activity Compose shell. Renders [ClawMateRoot], which owns the
 * bottom-navigation host and the launch-gate (onboarding).
 *
 * Harmonizes with the HarmonyOS source's `EntryAbility` which loads
 * `pages/Index` as the single application root.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.clawmate.app.log.AppLogger.debugLoggingEnabled = getSharedPreferences("clawmate-ui", MODE_PRIVATE).getBoolean("debug", false)
        enableEdgeToEdge()
        setContent {
            ClawMateTheme {
                ClawMateRoot()
            }
        }
    }
}
