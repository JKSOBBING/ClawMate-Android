package com.clawmate.shared

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import ohos.ace.adapter.capability.bridge.BridgePlugin
import ohos.stage.ability.adapter.StageActivity

/** Android APIs only; no account, service endpoint or vendor-specific dependency. */
class PlatformBridge(private val activity: StageActivity) :
    BridgePlugin(activity, "ClawMatePlatform", activity.bridgeManager) {
    fun platformInfo(): String = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"

    fun clearFocus(): Boolean = onUi {
        activity.currentFocus?.let { it.clearFocus(); true } ?: false
    }

    fun hideKeyboard(): Boolean = onUi {
        val focused = activity.currentFocus ?: return@onUi false
        val manager = activity.getSystemService(InputMethodManager::class.java)
        manager.hideSoftInputFromWindow(focused.windowToken, 0)
    }

    fun keyboardHeight(): Int = onUi {
        val root = activity.window.decorView
        val frame = Rect()
        root.getWindowVisibleDisplayFrame(frame)
        (root.rootView.height - frame.bottom).takeIf { it > root.rootView.height / 5 } ?: 0
    }

    fun setClipboard(text: String): Boolean = onUi {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ClawMate", text))
        true
    }

    fun openUrl(value: String): Boolean = onUi {
        val uri = Uri.parse(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid HTTPS URL" }
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    }

    private fun <T> onUi(action: () -> T): T {
        val work = FutureTask(action)
        if (Looper.myLooper() == Looper.getMainLooper()) work.run() else activity.runOnUiThread(work)
        return work.get(2, TimeUnit.SECONDS)
    }
}
