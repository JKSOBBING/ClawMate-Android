package com.clawmate.app

import android.graphics.Color
import android.text.Spanned
import android.text.style.ClickableSpan
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.chat.ui.createChatMarkdown
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMarkdownDeviceTest {
    @Test fun rendersFormattedTextCodeAndOnlyExplicitWebLinks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var opened: String? = null
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val renderer = createChatMarkdown(context, Color.BLUE, Color.BLACK, Color.LTGRAY) { opened = it }
            val view = TextView(context)
            renderer.setMarkdown(view, "# 标题\n\n**重点** 与 `code`\n\n```kotlin\nval n = 1\n```\n\n[网页](https://example.com)\n\n![替代文本](https://example.invalid/image.png)")
            val text = view.text as Spanned
            assertTrue(text.contains("标题"))
            assertTrue(text.contains("重点"))
            assertTrue(text.contains("val n = 1"))
            assertTrue(text.contains("kotlin"))
            assertTrue(text.contains("替代文本"))
            assertFalse(text.contains("**"))
            assertFalse(text.contains("```"))
            assertTrue(text.getSpans(0, text.length, Any::class.java).any { it.javaClass.simpleName.contains("CodeBlock") })
            assertNull(opened) // Rendering is not navigation or image fetching.
            val links = text.getSpans(0, text.length, ClickableSpan::class.java)
            assertEquals(1, links.size)
            links.single().onClick(view)
            assertEquals("https://example.com", opened)
        }
    }

    @Test fun rendersTableWithoutWebView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val renderer = createChatMarkdown(context, Color.BLUE, Color.BLACK, Color.LTGRAY) {}
            val view = TextView(context)
            renderer.setMarkdown(view, "| 项目 | 状态 |\n| --- | --- |\n| 测试 | 完成 |")
            assertTrue(view.text is Spanned)
            assertTrue(view.text.length > 0)
            val text = view.text as Spanned
            assertTrue(text.getSpans(0, text.length, Any::class.java).any { it.javaClass.simpleName == "TableRowSpan" })
        }
    }

    @Test fun htmlAndImagesRemainInertText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            var opened = false
            val renderer = createChatMarkdown(context, Color.BLUE, Color.BLACK, Color.LTGRAY) { opened = true }
            val view = TextView(context)
            renderer.setMarkdown(view, "<script>alert(1)</script>\n\n![图片说明](https://example.invalid/image.png)")
            val text = view.text as Spanned
            assertTrue(text.contains("<script>alert(1)</script>"))
            assertTrue(text.contains("图片说明"))
            assertFalse(opened)
            assertTrue(text.getSpans(0, text.length, ClickableSpan::class.java).isEmpty())
            assertFalse(text.getSpans(0, text.length, Any::class.java).any { it.javaClass.simpleName.contains("AsyncDrawable") })
        }
    }
}
