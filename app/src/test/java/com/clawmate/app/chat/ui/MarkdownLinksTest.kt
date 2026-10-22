package com.clawmate.app.chat.ui

import org.junit.Assert.*
import org.junit.Test

class MarkdownLinksTest {
    @Test fun `web links include query fragments encoded paths and international hosts`() {
        listOf("https://example.com/a%20b?q=a%2Fb#section", "http://example.com:8080/page",
            "HTTPS://example.com", "https://例子.中国/中文", "https://[::1]/page",
        ).forEach { assertTrue(it, isAllowedMarkdownLink(it)) }
    }

    @Test fun `nonweb credentialed and malformed links cannot launch intents`() {
        listOf("", "javascript:alert(1)", "data:text/html,test", "file:///private/file", "content://photos/1",
            "intent://app/#Intent;end", "tel:123", "mailto:test@example.com", "//example.com", "#part",
            "https://user:password@example.com", "https://example.com@evil.example", "https://",
            "https://example.com\\@evil.example", "https://example.com\n/path", "https://example.com:99999",
            "https://exa mple.com", "https://" + "a".repeat(9000),
        ).forEach { assertFalse(it.take(100), isAllowedMarkdownLink(it)) }
    }
}
