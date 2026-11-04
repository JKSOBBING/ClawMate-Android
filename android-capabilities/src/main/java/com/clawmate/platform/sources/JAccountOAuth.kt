package com.clawmate.platform.sources

/**
 * jAccount（上海交通大学统一身份认证）OAuth2 授权的纯逻辑部分。
 *
 * 申请 jAccount 接口时，网络信息中心为每个应用分配 **clientId 与密钥**，并要求在交我办
 * 申请表单中填写应用服务网址，OAuth2 服务器会校验该网址与申请时一致；授权接口是标准
 * OAuth2 authorization code（见 https://jaccount.sjtu.edu.cn/oauth2/.well-known/openid-configuration）。
 *
 * 因此这里只接受“redirect_uri 属于已注册的网站域名”的配置，并把 redirect_uri 留空视为
 * 未配置：没有申请到 jAccount 接口的应用无法完成授权，不能假装可以登录。
 */
object JAccountOAuth {
    const val AUTHORIZE_ENDPOINT = "https://jaccount.sjtu.edu.cn/oauth2/authorize"
    const val TOKEN_ENDPOINT = "https://jaccount.sjtu.edu.cn/oauth2/token"
    const val DISCOVERY_ENDPOINT = "https://jaccount.sjtu.edu.cn/oauth2/.well-known/openid-configuration"
    const val SCOPE = "openid"
    const val MAIL_HOST = "mail.sjtu.edu.cn"
    const val MAIL_PORT = 993

    /** 交大邮箱用户名即 jAccount 账号；也接受完整 xxx@sjtu.edu.cn。 */
    const val EMAIL_DOMAIN = "@sjtu.edu.cn"

    /** 授权码授权使用的回调地址：必须是 HTTPS 且位于已注册域名下。 */
    fun validateRedirectUri(uri: String): String? {
        val value = uri.trim()
        if (value.isEmpty()) return "请填写 jAccount 接口申请时登记的服务网址（HTTPS）"
        if (!value.startsWith("https://")) return "回调地址必须为 HTTPS"
        if (value.any { it.code <= 32 || it.code == 127 }) return "回调地址包含非法字符"
        if (value.length > 512) return "回调地址过长"
        return null
    }

    /** 校验应用客户端ID（网络信息中心分配），不允许放密钥。 */
    fun validateClientId(clientId: String): String? {
        val value = clientId.trim()
        if (value.isEmpty()) return "请填写 jAccount 接口申请时分配的客户端ID"
        if (value.length !in 4..128) return "客户端ID长度不正确"
        if (value.none { it.isLetterOrDigit() }) return "客户端ID格式不正确"
        return null
    }

    fun matchesEmailDomain(user: String): Boolean = user.trim().lowercase().endsWith(EMAIL_DOMAIN)

    /**
     * 账号归一化：输入 jAccount 账号名或完整邮箱，返回 IMAP 登录名。
     * 非法输入返回 null，不做任何猜测。
     */
    fun normalizeAccount(raw: String): String? {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return null
        val at = value.indexOf('@')
        val name = if (at < 0) value else value.substring(0, at)
        val domain = if (at < 0) EMAIL_DOMAIN else value.substring(at)
        if (name.isEmpty() || name.length > 64) return null
        if (!Regex("[a-z0-9][a-z0-9._-]*").matches(name)) return null
        if (domain != EMAIL_DOMAIN) return null
        return name + EMAIL_DOMAIN
    }

    /**
     * jAccount 登录令牌对应的 IMAP 用户标识由学校安排；应用身份与登录账号必须同时明确，
     * 不能拿缺省值猜测。两个值都给出时返回 IMAP 登录名。
     *
     * @param clientId 应用客户端ID（网络信息中心分配）
     * @param loginName 用户登录的 jAccount 账号（归一化后）
     */
    fun imapLoginName(clientId: String, loginName: String): String? {
        if (clientId.isBlank()) return null
        return normalizeAccount(loginName)
    }

    /** 构造授权页面地址；state 每次授权随机生成，用于回调校验。 */
    fun authorizeUrl(clientId: String, redirectUri: String, state: String): String {
        require(validateClientId(clientId) == null)
        require(validateRedirectUri(redirectUri) == null)
        require(state.matches(Regex("[A-Za-z0-9-]{16,64}")))
        val encoded = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        return "$AUTHORIZE_ENDPOINT?response_type=code&client_id=" +
            java.net.URLEncoder.encode(clientId, "UTF-8") +
            "&redirect_uri=$encoded&scope=$SCOPE&state=$state"
    }

    /** 从授权回调（可能来自浏览器或 Deep Link）中取出 code 与 state 并校验 state。 */
    fun parseCallback(raw: String, expectedState: String): Result<String> {
        val query = raw.trim().let { if (it.startsWith("?")) it.substring(1) else it }
        val pairs = query.split('&').mapNotNull { part ->
            val i = part.indexOf('='); if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
        }.toMap()
        pairs["error"]?.let { return Result.failure(IllegalStateException("jAccount 授权被拒绝：$it")) }
        val state = pairs["state"]
        val code = pairs["code"]
        if (code.isNullOrBlank()) return Result.failure(IllegalStateException("授权回调中没有授权码"))
        if (state != expectedState) return Result.failure(IllegalStateException("授权回调状态不匹配，请重新授权"))
        if (code.length > 2048 || code.any { it.code <= 32 || it.code == 127 }) return Result.failure(IllegalStateException("授权码格式无效"))
        return Result.success(code)
    }

    /**
     * 授权码换取访问令牌（客户端密钥交换在后台完成，不经过前端）。
     * 只接受 JSON 里非空的 access_token；其它字段一律不落盘。
     */
    fun tokenPayload(code: String, clientId: String, clientSecret: String, redirectUri: String): Map<String, String> {
        require(code.isNotEmpty() && clientId.isNotBlank() && clientSecret.isNotBlank())
        require(validateRedirectUri(redirectUri) == null)
        return mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to clientId.trim(),
            "client_secret" to clientSecret,
            "redirect_uri" to redirectUri.trim(),
        )
    }

    /** 校验令牌响应并把 access_token 限制在可用格式。 */
    fun accessToken(response: Map<String, Any?>): String? {
        val token = response["access_token"] as? String ?: return null
        if (token.length !in 8..16000) return null
        if (token.any { it.code <= 32 || it.code == 127 }) return null
        return token
    }
}
