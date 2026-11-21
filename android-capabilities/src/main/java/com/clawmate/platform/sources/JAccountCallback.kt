package com.clawmate.platform.sources

/**
 * 从 jAccount 授权回调 URL 中提取回调信息（纯 JVM 逻辑，便于单元测试）。
 *
 * 支持两种回调形态：
 *  - 自定义 Scheme Deep Link：`clawmate://jaccount?code=...&state=...`
 *  - 已登记服务网址：`https://<registered-host>/<path>?code=...&state=...`
 * 只接受查询串，不接受把授权码放在路径中的地址。
 */
object JAccountCallback {
    const val SCHEME = "clawmate"
    const val HOST = "jaccount"

    /** 是否为本应用用于 jAccount 的 Deep Link。 */
    fun isOwnLink(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("$SCHEME://$HOST") && (longLength(lower) == "$SCHEME://$HOST".length || lower.startsWith("$SCHEME://$HOST?") || lower.startsWith("$SCHEME://$HOST/"))
    }

    /** 返回 URL 的查询串（不含 `?`）；没有查询串时返回空串。 */
    fun queryOf(url: String): String {
        val index = url.indexOf('?')
        if (index < 0) return ""
        val end = url.indexOf('#', index).let { if (it < 0) url.length else it }
        return url.substring(index + 1, end)
    }

    private fun longLength(value: String): Int = value.length
}
