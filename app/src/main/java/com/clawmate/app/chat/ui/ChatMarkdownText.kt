package com.clawmate.app.chat.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin
import org.commonmark.node.Image
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline

/** Native styled text: no WebView, HTML script execution or automatic image loads. */
internal fun createChatMarkdown(
    context: Context,
    linkColor: Int,
    codeColor: Int,
    codeBackground: Int,
    onLink: (String) -> Unit,
): Markwon = Markwon.builder(context)
    .usePlugin(TablePlugin.create(context))
    .usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureTheme(builder: MarkwonTheme.Builder) {
            builder.linkColor(linkColor).codeTextColor(codeColor).codeBlockTextColor(codeColor)
                .codeBackgroundColor(codeBackground).codeBlockBackgroundColor(codeBackground)
                .codeTypeface(Typeface.MONOSPACE).codeBlockTypeface(Typeface.MONOSPACE)
        }

        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            builder.syntaxHighlight { info, code ->
                val label = info.orEmpty().trim().take(80)
                if (label.isEmpty()) code else SpannableStringBuilder().append(label).append('\n').append(code).apply {
                    setSpan(RelativeSizeSpan(0.8f), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            builder.linkResolver { _, link ->
                if (isAllowedMarkdownLink(link)) onLink(link.trim())
                else Toast.makeText(context, "仅支持打开不含登录信息的 http/https 网页链接", Toast.LENGTH_SHORT).show()
            }
        }

        override fun configureVisitor(builder: MarkwonVisitor.Builder) {
            // Render image alternative text only. Chat rendering never fetches
            // URLs merely because an untrusted model placed them in Markdown.
            builder.on(Image::class.java) { visitor, image -> visitor.visitChildren(image) }
            builder.on(HtmlInline::class.java) { visitor, html -> visitor.builder().append(html.literal) }
            builder.on(HtmlBlock::class.java) { visitor, html ->
                visitor.blockStart(html)
                visitor.builder().append(html.literal)
                visitor.blockEnd(html)
            }
        }
    }).build()

@Composable
internal fun ChatMarkdownText(markdown: String, modifier: Modifier = Modifier, foreground: Color = Color.Unspecified) {
    val context = LocalContext.current
    val textColor = (if (foreground == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else foreground).toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val codeColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val codeBackground = MaterialTheme.colorScheme.surface.toArgb()
    val renderer = remember(context, linkColor, codeColor, codeBackground) {
        createChatMarkdown(context, linkColor, codeColor, codeBackground) { link ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(context, "没有可用的浏览器，链接可从消息原文复制", Toast.LENGTH_SHORT).show()
            }
        }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx -> TextView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textColor)
            setLineSpacing(0f, 1.15f)
            includeFontPadding = false
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
        } },
        update = { view ->
            view.setTextColor(textColor)
            val contentKey = markdown to renderer
            if (view.tag != contentKey) {
                view.tag = contentKey
                renderer.setMarkdown(view, markdown)
            }
        },
    )
}
