package com.clawmate.app

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.app.chat.ui.CopyMessageButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatCopyDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun copyButtonWritesEntireOriginalMarkdownToAndroidClipboard() {
        val fixture = "# 设备测试\n\n**原始 Markdown**\n\n```kotlin\nval text = \"fixture\"\n```\n" + "长消息正文".repeat(1200) + "\n完整结尾 😀"
        var clipboard: ClipboardManager? = null
        compose.setContent {
            clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            MaterialTheme { CopyMessageButton(fixture) }
        }
        var original: android.content.ClipData? = null
        compose.runOnIdle { original = clipboard!!.primaryClip }
        try {
            compose.onNodeWithContentDescription("复制整条消息").performClick()
            compose.runOnIdle {
                val copied = clipboard!!.primaryClip?.getItemAt(0)?.text?.toString()
                // Never print a pre-existing clipboard's contents on failure.
                assertTrue("Clipboard must contain the entire fixture including original Markdown and tail", copied == fixture)
            }
        } finally {
            compose.runOnIdle {
                val saved = original
                if (saved != null) clipboard!!.setPrimaryClip(saved)
                else if (Build.VERSION.SDK_INT >= 28) clipboard!!.clearPrimaryClip()
            }
        }
    }
}
