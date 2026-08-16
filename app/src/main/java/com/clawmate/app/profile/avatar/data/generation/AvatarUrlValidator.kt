package com.clawmate.app.profile.avatar.data.generation

import java.net.URI
import java.net.URISyntaxException

/**
 * Strict, deterministic URL + content-type validation for the Seedream avatar
 * provider. No network I/O.
 *
 * [validateBaseUrl] enforces that a configured base URL is an absolute
 * `http`/`https` URL with a real host — so generation is only ever sent to an
 * explicitly configured, well-formed endpoint and a typos/relative string can
 * never trigger a call. [validateDownloadUrl] is the same test applied to the
 * image URL returned by the provider so the app only ever downloads from a
 * validated absolute http(s) URL.
 */
object AvatarUrlValidator {

    /** Result of a URL check: valid + the normalized form, or a reason. */
    data class UrlCheck(val valid: Boolean, val normalized: String = "", val reason: String = "")

    /**
     * Validate an absolute http(s) base/endpoint URL. Strips surrounding
     * whitespace and a single trailing slash. Blank → invalid.
     */
    fun validateEndpointUrl(raw: String, label: String = "URL"): UrlCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UrlCheck(false, reason = "$label 为空")
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return UrlCheck(false, reason = "$label 必须是 http/https 地址")
        }
        val withoutScheme = trimmed.substringAfter("://")
        if (withoutScheme.isBlank() || withoutScheme.startsWith("/") || withoutScheme.contains(" ")) {
            return UrlCheck(false, reason = "$label 缺少有效主机")
        }
        val host = withoutScheme.substringBefore("/").substringBefore("?").substringBefore("#")
        if (host.isBlank() || host.contains(" ") || host == ".") {
            return UrlCheck(false, reason = "$label 缺少有效主机")
        }
        // Reject credentials embedded in the URL (no userinfo) — keys belong in
        // the Authorization header, never in a URL.
        if (host.contains("@")) return UrlCheck(false, reason = "$label 不允许内嵌凭据")
        return UrlCheck(
            valid = true,
            normalized = trimmed.trimEnd('/'),
        )
    }

    /** Alias for the base endpoint used by the provider. */
    fun validateBaseUrl(raw: String): UrlCheck = validateEndpointUrl(raw, "图片服务 URL")

    /** Alias for the downloadable image URL. */
    fun validateDownloadUrl(raw: String): UrlCheck = validateEndpointUrl(raw, "图片下载 URL")

    /** Validate the returned image URL is parseable as an absolute http(s) URI. */
    fun isWellFormedHttpUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
        return try {
            val uri = URI(trimmed)
            !uri.host.isNullOrBlank()
        } catch (e: URISyntaxException) {
            false
        }
    }

    /**
     * Enforce an acceptable image content type from an HTTP response `Content-Type`
     * header. A missing/blank header is treated as rejectable (the app only
     * downloads a provider image when the responder actually says it is an image).
     */
    fun acceptedContentType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(";")?.trim()?.lowercase() ?: return false
        return type in AvatarProviderLimits.ACCEPTED_IMAGE_CONTENT_TYPES
    }

    /** Map a foreign/incorrect content type to an honest reason. */
    fun contentTypeReason(contentType: String?): String =
        if (contentType.isNullOrBlank()) "响应未声明图片类型" else "响应类型不支持：$contentType"
}
