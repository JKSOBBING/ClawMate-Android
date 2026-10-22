package com.clawmate.app.chat.ui

import java.net.IDN
import java.net.URI
import java.net.URL
import java.util.Locale

/** Model-authored links can open web pages only, without embedded credentials. */
internal fun isAllowedMarkdownLink(raw: String): Boolean {
    val link = raw.trim()
    if (link.isEmpty() || link.length > 8192 || link.any { it.isISOControl() || it == '\\' }) return false
    return runCatching {
        val url = URL(link)
        if (url.protocol.lowercase(Locale.ROOT) !in setOf("http", "https") || url.host.isNullOrBlank() ||
            url.userInfo != null || (url.port != -1 && url.port !in 1..65535)) return false
        if (url.host.startsWith("[")) URI(link).host != null
        else IDN.toASCII(url.host, IDN.USE_STD3_ASCII_RULES).isNotBlank()
    }.getOrDefault(false)
}
