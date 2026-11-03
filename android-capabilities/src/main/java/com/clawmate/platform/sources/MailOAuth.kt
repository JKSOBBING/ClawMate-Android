package com.clawmate.platform.sources

import android.accounts.Account
import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Foreground, explicit authorization. Tokens never cross the bridge or survive process exit. */
class MailOAuth(
    private val activity: Activity,
    private val engine: PersonalSourceEngine,
    private val exchange: ((String, String, Map<String, String>) -> JSONObject)? = null,
    private val now: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).followRedirects(false).build()
    private var generation = 0
    private var state = "idle"
    private var message = ""
    private var provider = ""
    private var user = ""
    private var token = ""
    private var expires = 0L
    private var device = ""
    private var client = ""
    private var tenant = "common"
    private var code = ""
    private var uri = ""
    private var interval = 5L
    private var nextPoll = 0L
    private var deviceExpires = 0L
    private var polling = false
    private var googleResolution: Int? = null
    companion object { const val REQUEST = 7311; const val SCOPE = "https://outlook.office.com/IMAP.AccessAsUser.All" }

    init { engine.oauthToken = { p, u -> accessToken(p, u) } }
    @Synchronized private fun accessToken(p: String, u: String): String {
        check(p == provider && u == user && token.isNotEmpty() && now() < expires) { "请重新授权邮箱" }
        return token
    }
    @Synchronized fun start(kind: String, username: String, clientId: String, directory: String) {
        check(googleResolution == null) { "请先关闭上一次Google授权窗口" }
        require(kind in listOf("google", "microsoft"))
        if (kind == "microsoft") {
            require(clientId.matches(Regex("[a-fA-F0-9-]{36}"))) { "请填写应用客户端ID" }
            require(directory in listOf("common", "consumers", "organizations") || directory.matches(Regex("[a-fA-F0-9-]{36}")))
        }
        cancel(); engine.configureOAuth(kind, username, clientId, directory)
        provider = kind; user = username; client = clientId; tenant = directory
        state = "pending"; message = "等待邮箱授权"; val g = generation
        if (kind == "google") {
            activity.runOnUiThread {
                synchronized(this) {
                    if (g != generation) return@runOnUiThread
                    try {
                        val request = AuthorizationRequest.builder().setAccount(Account(username, "com.google"))
                            .setRequestedScopes(listOf(Scope("https://mail.google.com/"))).build()
                        Identity.getAuthorizationClient(activity).authorize(request).addOnSuccessListener { result ->
                            synchronized(this) {
                                if (g != generation) return@addOnSuccessListener
                                if (result.hasResolution()) {
                                    try { googleResolution = g; activity.startIntentSenderForResult(result.pendingIntent!!.intentSender, REQUEST, null, 0, 0, 0) }
                                    catch (_: Exception) { googleResolution = null; fail("无法打开Google授权窗口，请检查Google Play服务") }
                                } else accept(result.accessToken ?: "", 3000)
                            }
                        }.addOnFailureListener { synchronized(this) { if (g == generation) fail("Google授权失败：检查Play服务、包名/SHA-1注册、测试用户和授权范围") } }
                    } catch (_: Exception) { fail("Google授权不可用，请检查Google Play服务及应用注册") }
                }
            }
        } else executor.submit {
            try {
                val response = post(directory, "devicecode", mapOf("client_id" to clientId, "scope" to SCOPE))
                synchronized(this) {
                    if (g != generation) return@submit
                    if (response.has("error")) { fail("Microsoft授权初始化失败，请核对客户端ID、账户类型及公共客户端设置"); return@submit }
                    val target = response.getString("verification_uri")
                    require(target == "https://microsoft.com/devicelogin" || target == "https://www.microsoft.com/devicelogin" || target == "https://login.microsoftonline.com/common/oauth2/deviceauth")
                    device = response.getString("device_code"); code = response.getString("user_code"); uri = target
                    interval = response.optLong("interval", 5).coerceIn(5, 60)
                    deviceExpires = now() + response.getLong("expires_in").coerceIn(1, 1800) * 1000
                    nextPoll = now() + interval * 1000
                    message = "请打开Microsoft授权网页，输入本次设备码；登录与邮箱地址相同的账户。"
                }
            } catch (_: Exception) { synchronized(this) { if (g == generation) fail("无法连接Microsoft授权服务，请检查网络或重试") } }
        }
    }
    @Synchronized fun activityResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request != REQUEST) return false
        val g = googleResolution; googleResolution = null
        if (g != generation) return true
        if (provider != "google" || state != "pending") return true
        if (result != Activity.RESULT_OK || data == null) { fail("Google授权已取消"); return true }
        try { accept(Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken ?: "", 3000) }
        catch (_: Exception) { fail("Google授权未完成，请重新授权") }
        return true
    }
    @Synchronized fun openPage() {
        check(state == "pending" && uri.isNotEmpty())
        val target = uri
        activity.runOnUiThread { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) } }
    }
    @Synchronized fun status(): String {
        if (state == "pending" && device.isNotEmpty() && !polling) {
            if (now() >= deviceExpires) fail("设备码已过期，请重新授权")
            else if (now() >= nextPoll) poll()
        }
        if (state == "done" && now() >= expires) fail("授权令牌已过期，请重新授权")
        return JSONObject().put("state", state).put("message", message).put("code", code).put("uri", uri).toString()
    }
    private fun poll() {
        polling = true
        val g = generation; val d = device; val c = client; val t = tenant
        executor.submit {
            try {
                val response = post(t, "token", mapOf("client_id" to c, "device_code" to d, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
                synchronized(this) {
                    if (g != generation) return@submit
                    when (response.optString("error")) {
                        "" -> accept(response.getString("access_token"), response.getLong("expires_in"))
                        "authorization_pending" -> Unit
                        "slow_down" -> interval = (interval + 5).coerceAtMost(300)
                        "authorization_declined", "access_denied" -> fail("Microsoft授权被拒绝")
                        "expired_token" -> fail("设备码已过期，请重新授权")
                        else -> fail("Microsoft授权失败，请核对租户策略、IMAP权限及应用注册")
                    }
                }
            } catch (_: Exception) { synchronized(this) { if (g == generation) message = "授权轮询网络失败，将在下个间隔重试" } }
            finally { synchronized(this) { if (g == generation) { polling = false; nextPoll = now() + interval * 1000 } } }
        }
    }
    private fun post(directory: String, path: String, values: Map<String, String>): JSONObject {
        exchange?.let { return it(directory, path, values) }
        val body = FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build()
        return http.newCall(Request.Builder().url("https://login.microsoftonline.com/$directory/oauth2/v2.0/$path").post(body).build()).execute().use {
            val raw = it.body?.string() ?: error("Empty response"); require(raw.length <= 65536); JSONObject(raw)
        }
    }
    private fun accept(value: String, seconds: Long) {
        if (value.isBlank() || seconds <= 60) { fail("未获得有效授权令牌，请重新授权"); return }
        token = value; expires = now() + (seconds - 60).coerceIn(1, 3600) * 1000
        device = ""; code = ""; uri = ""; state = "done"; message = "OAuth授权完成，请读取邮件验证邮箱访问权限"
    }
    private fun fail(reason: String) { state = "error"; message = reason; token = ""; device = ""; code = ""; uri = "" }
    @Synchronized fun cancel() {
        generation++; http.dispatcher.cancelAll(); state = "idle"; token = ""; device = ""; code = ""; uri = ""; polling = false; message = "授权已取消或未开始"
    }
    override fun close() { cancel(); executor.shutdownNow() }
}
