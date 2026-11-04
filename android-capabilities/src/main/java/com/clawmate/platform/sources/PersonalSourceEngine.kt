package com.clawmate.platform.sources

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Data owned by this install. No credentials are returned through the ArkTS bridge. */
class PersonalSourceEngine(private val context: Context) : AutoCloseable {
    private val prefs = context.getSharedPreferences("personal_sources", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "personal-sources").apply { mkdirs() }
    private val optionsPrefs = context.getSharedPreferences("personal_source_options", Context.MODE_PRIVATE)
    private fun calendarEnabled() = optionsPrefs.getBoolean("calendarEnabled", File(root, "calendar.json").exists())
    private fun calendarSelection(): JSONArray = JSONArray(optionsPrefs.getString("selected", null)
        ?: runCatching { JSONObject(File(root, "calendar.json").readText()).optJSONArray("selected")?.toString() }.getOrNull() ?: "[]")
    @Synchronized fun options(): String = JSONObject().put("calendarEnabled", calendarEnabled())
        .put("historyDays", optionsPrefs.getInt("historyDays", 90)).put("selected", calendarSelection())
        .put("maxMessages", optionsPrefs.getInt("maxMessages", 50)).toString()
    @Synchronized fun saveOptions(kind: String, raw: String) {
        val value = JSONObject(raw)
        when (kind) {
            "calendar" -> {
                val enabled = value.getBoolean("calendarEnabled")
                val days = value.getInt("historyDays"); require(days in listOf(0, 30, 90, 365))
                val selected = value.getJSONArray("selected"); require(selected.length() <= 100)
                for (i in 0 until selected.length()) require(selected.getLong(i) >= 0)
                val changed = enabled != calendarEnabled() || days != optionsPrefs.getInt("historyDays", 90) || selected.toString() != calendarSelection().toString()
                check(optionsPrefs.edit().putBoolean("calendarEnabled", enabled).putInt("historyDays", days)
                    .putString("selected", selected.toString()).commit())
                // A disabled/changed source must not remain available to chat from an older snapshot.
                if (changed || !enabled) clear("calendar")
            }
            "mail" -> {
                val limit = value.getInt("maxMessages"); require(limit in listOf(20, 50, 100))
                check(optionsPrefs.edit().putInt("maxMessages", limit).commit())
            }
            else -> error("未知来源设置")
        }
    }
    @Volatile private var transport: ImapTransport? = null
    @Volatile private var epoch = 0
    var oauthToken: (String, String) -> String = { _, _ -> error("请先完成邮箱OAuth授权") }
    @Synchronized fun configureOAuth(provider: String, user: String, client: String = "", tenant: String = "common") {
        require(provider in listOf("google", "microsoft")); ImapTransport.quoted(user)
        require(user.contains('@'))
        val host = if (provider == "google") "imap.gmail.com" else "outlook.office365.com"
        clear("mail")
        check(prefs.edit().clear().putString("mode", provider).putString("host", host).putInt("port", 993)
            .putString("user", user).putString("client", client).putString("tenant", tenant).putString("account", "$host:993:$user").commit())
    }
    fun calendarGranted() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    @Synchronized fun saveCredentials(host: String, port: Int, user: String, secret: String) {
        require(host.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,251}[A-Za-z0-9]")) && port in 1..65535 && user.length in 1..254)
        val old = prefs.getString("account", "")
        val account = "$host:$port:$user"
        val actual = if (secret.isEmpty()) { require(old == account) { "新邮箱需要授权码" }; decrypt() } else secret
        ImapTransport.quoted(actual); ImapTransport.quoted(user)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        check(prefs.edit().putString("mode", "password").putString("account", account).putString("host", host).putInt("port", port).putString("user", user)
            .putString("secret", Base64.encodeToString(cipher.doFinal(actual.toByteArray()), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
        close()
        // Saving configuration invalidates old results and query authorization, even for the same account.
        clear("mail")
    }
    fun settings(): String = JSONObject().put("host", prefs.getString("host", "imap.qq.com"))
        .put("port", prefs.getInt("port", 993)).put("username", prefs.getString("user", "")).put("mode", prefs.getString("mode", "password"))
        .put("client", prefs.getString("client", "")).put("tenant", prefs.getString("tenant", "common")).toString()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("clawmate_mail", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("clawmate_mail", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun decrypt(): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(prefs.getString("secret", ""), Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    fun execute(operation: String, argument: String): String {
        val generation = epoch
        val result = when (operation) {
            "calendar" -> calendar(argument)
            "mailOpen" -> {
                val t = ImapTransport(); synchronized(this) { check(generation == epoch); transport?.close(); transport = t }
                t.open(prefs.getString("host", "")!!, prefs.getInt("port", 993)); "{}"
            }
            "mailCommand" -> {
                val t = transport ?: error("邮箱未连接")
                val mode = prefs.getString("mode", "password")!!; val user = prefs.getString("user", "")!!
                if (argument == "@LOGIN" && mode != "password") JSONArray(t.authenticate(user, oauthToken(mode, user))).toString()
                else {
                    val command = if (argument == "@LOGIN") "LOGIN " + ImapTransport.quoted(user) + " " + ImapTransport.quoted(decrypt()) else argument
                    JSONArray(t.command(command)).toString()
                }
            }
            else -> error("未知来源操作")
        }
        check(generation == epoch) { "操作已取消" }; return result
    }
    private fun calendar(argument: String): String {
        check(calendarGranted()) { "请先授权读取系统日历" }
        val args = JSONObject(argument); val now = java.time.ZonedDateTime.now()
        val listOnly = args.optBoolean("listOnly", false)
        check(listOnly || calendarEnabled()) { "请先开启接入系统日历" }
        val start = now.toLocalDate().minusDays(optionsPrefs.getInt("historyDays", 90).toLong()).atStartOfDay(now.zone).toInstant().toEpochMilli()
        val end = now.plusDays(30).toInstant().toEpochMilli()
        val selected = if (listOnly) JSONArray() else calendarSelection()
        require(selected.length() <= 100)
        val ids = (0 until selected.length()).map { selected.getLong(it) }.toSet()
        val sources = JSONArray()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf("_id", "calendar_displayName"), null, null, "_id")?.use { c ->
            while (c.moveToNext()) sources.put(JSONObject().put("id", c.getLong(0)).put("name", c.getString(1) ?: "日历"))
        } ?: error("无法读取日历来源")
        val items = JSONArray()
        if (ids.isNotEmpty()) {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uri, start); ContentUris.appendId(uri, end)
            context.contentResolver.query(uri.build(), arrayOf("event_id", "calendar_id", "title", "description", "eventLocation", "begin", "end", "allDay", "eventTimezone"),
                "calendar_id IN (${ids.joinToString(",") { "?" }})", ids.map { it.toString() }.toTypedArray(), "begin ASC")?.use { c ->
                while (c.moveToNext()) {
                    check(!Thread.currentThread().isInterrupted && calendarGranted())
                    check(items.length() < 10000) { "日程过多，请减少选择的日历" }
                    items.put(JSONObject().put("id", "${c.getLong(1)}:${c.getLong(0)}:${c.getLong(5)}")
                        .put("title", (c.getString(2) ?: "未命名日程").take(500)).put("body", (c.getString(3) ?: "").take(3000))
                        .put("location", (c.getString(4) ?: "").take(500)).put("start", c.getLong(5)).put("end", c.getLong(6))
                        .put("allDay", c.getInt(7) != 0).put("timeZone", c.getString(8) ?: "").put("calendarId", c.getLong(1)))
                }
            } ?: error("日历查询失败")
        }
        check(calendarGranted())
        return JSONObject().put("sources", sources).put("items", items).put("syncedAt", System.currentTimeMillis())
            .put("windowStart", start).put("windowEnd", end).put("selected", selected).toString()
    }
    @Synchronized fun write(kind: String, value: String) {
        require(kind in listOf("calendar", "mail") && value.length < 12 * 1024 * 1024)
        if (kind == "calendar") check(calendarGranted() && calendarEnabled())
        JSONObject(value)
        val file = AtomicFile(File(root, "$kind.json")); val out = file.startWrite()
        try { out.write(value.toByteArray()); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    @Synchronized fun read(kind: String): String {
        require(kind in listOf("calendar", "mail"))
        if (kind == "calendar" && (!calendarGranted() || !calendarEnabled())) return "{}"
        val file = AtomicFile(File(root, "$kind.json"))
        if (!file.baseFile.exists()) return "{}"
        val text = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        if (kind != "mail") return text
        val snapshot = JSONObject(text)
        val cutoff = java.time.LocalDate.now().minusDays(2).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val original = snapshot.optJSONArray("items") ?: JSONArray()
        val retained = JSONArray()
        for (i in 0 until original.length()) if (original.getJSONObject(i).optLong("start") >= cutoff) retained.put(original.getJSONObject(i))
        if (retained.length() != original.length()) { snapshot.put("items", retained); write("mail", snapshot.toString()) }
        return snapshot.toString()
    }
    @Synchronized fun clear(kind: String) {
        require(kind in listOf("calendar", "mail")); close(); AtomicFile(File(root, "$kind.json")).delete()
    }
    @Synchronized fun forgetMail() { clear("mail"); check(prefs.edit().clear().commit()) }
    @Synchronized override fun close() { epoch++; transport?.close(); transport = null }
}
