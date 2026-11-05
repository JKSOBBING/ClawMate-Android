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
    companion object {
        /** 首页“为你提醒”快速浏览读取的未来天数。 */
        const val QUICK_DAYS = 7
    }
    private val prefs = context.getSharedPreferences("personal_sources", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "personal-sources").apply { mkdirs() }
    private val optionsPrefs = context.getSharedPreferences("personal_source_options", Context.MODE_PRIVATE)
    private fun calendarEnabled() = optionsPrefs.getBoolean("calendarEnabled", File(root, "calendar.json").exists())
    /** 常用节假日来源默认开启；与“接入系统日历”相互独立。 */
    private fun holidayEnabled() = optionsPrefs.getBoolean("holidayEnabled", true)
    private fun calendarSelection(): JSONArray = JSONArray(optionsPrefs.getString("selected", null)
        ?: runCatching { JSONObject(File(root, "calendar.json").readText()).optJSONArray("selected")?.toString() }.getOrNull() ?: "[]")
    /** 已停用的日历类别（生日/纪念日/倒计时等），与“本机日历”分开控制。 */
    private fun disabledGroups(): Set<String> = (optionsPrefs.getString("disabledGroups", "") ?: "")
        .split(',').filter { it.isNotBlank() }.toSet()
    @Volatile private var cachedCalendars: List<CalendarMeta> = emptyList()
    @Volatile private var cachedCalendarsAt: Long = 0L
    private data class CalendarMeta(
        val id: Long, val name: String, val accountType: String, val owner: String,
        val readOnly: Boolean, val visible: Boolean, val group: String, val label: String, val detail: String,
    )
    private fun calendars(): List<CalendarMeta> {
        val cached = cachedCalendars
        if (cached.isNotEmpty() && System.currentTimeMillis() - cachedCalendarsAt < 10 * 60 * 1000L) return cached
        val out = ArrayList<CalendarMeta>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf("_id", "calendar_displayName", "account_type", "ownerAccount", "calendar_access_level", "visible"),
            null, null, "_id",
        )?.use { c ->
            while (c.moveToNext()) {
                // 系统日历 id 必须为非负整数，才能与节假日的 -1 区分来源。
                val id = c.getLong(0)
                if (id < 0) continue
                val name = c.getString(1) ?: ""
                val type = c.getString(2) ?: ""
                val owner = c.getString(3) ?: ""
                val readOnly = c.getInt(4) <= CalendarContract.Calendars.CAL_ACCESS_READ
                val info = CalendarSourceLabels.describe(id, name, type, owner, readOnly)
                out.add(CalendarMeta(id, name, type, owner, readOnly, c.getInt(5) != 0, info.group, info.name, info.detail))
            }
        } ?: run { if (calendarEnabled()) error("无法读取日历来源") }
        cachedCalendars = out
        cachedCalendarsAt = System.currentTimeMillis()
        return out
    }
    /** 实际读取的日历：只取勾选且类别启用的系统日历。 */
    private fun effectiveIds(): Set<Long> {
        val disabled = disabledGroups()
        val all = calendars()
        return (0 until calendarSelection().length()).mapNotNull {
            val id = calendarSelection().getLong(it)
            all.firstOrNull { meta -> meta.id == id && meta.group !in disabled }?.id
        }.toSet()
    }
    /** 按类别汇总的来源（界面用中文名展示，逗号分隔具体日历）。 */
    private fun groupedSources(): JSONArray {
        val groups = calendars().groupBy { it.group }
        val disabled = disabledGroups()
        val array = JSONArray()
        for (group in CalendarSourceLabels.GROUP_ORDER) {
            val members = groups[group] ?: continue
            if (members.isEmpty()) continue
            val enabled = group !in disabled
            val ids = JSONArray()
            members.forEach { ids.put(it.id) }
            array.put(JSONObject().put("id", -2L - CalendarSourceLabels.GROUP_ORDER.indexOf(group))
                .put("name", CalendarSourceLabels.groupTitle(group))
                .put("kind", if (group == "local") "system" else "group")
                .put("group", group)
                .put("groupLabel", CalendarSourceLabels.groupTitle(group))
                .put("groupHint", CalendarSourceLabels.groupHint(group))
                .put("enabled", enabled)
                .put("members", ids)
                .put("memberNames", describeMembers(members))
                .put("count", members.size))
        }
        return array
    }
    private fun describeMembers(members: List<CalendarMeta>): String {
        // 同一类别里的日历常是同一含义的不同账户（如“生日提醒”“新建生日”），只显示一次。
        val labels = members.map { it.label.removeSuffix("新建").removeSuffix("提醒") }.distinct().filter { it.isNotBlank() }
        return if (labels.size <= 1) labels.joinToString("") else labels.joinToString("、")
    }
    private fun CalendarMeta.rawNameForApi(): String = name.ifBlank { label }
    @Synchronized fun options(): String = JSONObject().put("calendarEnabled", calendarEnabled())
        .put("holidayEnabled", holidayEnabled())
        .put("disabledGroups", JSONArray(disabledGroups().sorted()))
        .put("historyDays", optionsPrefs.getInt("historyDays", 90)).put("selected", calendarSelection())
        .put("maxMessages", optionsPrefs.getInt("maxMessages", 50)).toString()
    @Synchronized fun saveOptions(kind: String, raw: String) {
        val value = JSONObject(raw)
        when (kind) {
            "calendar", "holiday" -> {
                val enabled = value.getBoolean("calendarEnabled")
                val holiday = value.optBoolean("holidayEnabled", holidayEnabled())
                val days = value.getInt("historyDays"); require(days in listOf(0, 30, 90, 365))
                val selected = value.getJSONArray("selected"); require(selected.length() <= 100)
                for (i in 0 until selected.length()) require(selected.getLong(i) >= 0)
                val groups = value.optJSONArray("disabledGroups")
                val disabled = if (groups == null) disabledGroups() else (0 until groups.length())
                    .map { groups.getString(it) }
                    .filter { it in CalendarSourceLabels.GROUP_ORDER && it != "local" && it != "other" }
                    .toSet()
                val changed = enabled != calendarEnabled() || holiday != holidayEnabled() ||
                    days != optionsPrefs.getInt("historyDays", 90) || selected.toString() != calendarSelection().toString() ||
                    disabled.sorted() != disabledGroups().sorted()
                check(optionsPrefs.edit().putBoolean("calendarEnabled", enabled).putBoolean("holidayEnabled", holiday)
                    .putInt("historyDays", days).putString("selected", selected.toString())
                    .putString("disabledGroups", disabled.sorted().joinToString(",")).commit())
                // A disabled/changed source must not remain available to chat from an older snapshot.
                if (changed) clear("calendar")
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
    /** 非 OAuth2 的 Bearer 令牌邮箱（如交大 jAccount）：只在本次进程内持有，不落盘。 */
    @Volatile private var bearerToken: String = ""
    @Synchronized fun configureBearer(provider: String, user: String, host: String, token: String) {
        require(provider in listOf("jaccount")); require(host.length in 1..253 && ImapTransport.quoted(user).isNotEmpty())
        require(token.length in 8..16000 && token.none { it.code <= 32 || it.code == 127 })
        bearerToken = token
        clear("mail")
        check(prefs.edit().clear().putString("mode", provider).putString("host", host).putInt("port", 993)
            .putString("user", user).putString("account", "$host:993:$user").commit())
    }
    @Synchronized fun configureOAuth(provider: String, user: String, client: String = "", tenant: String = "common") {
        require(provider in listOf("google", "microsoft", "jaccount")); ImapTransport.quoted(user)
        require(user.contains('@'))
        val host = when (provider) {
            "google" -> "imap.gmail.com"
            "jaccount" -> "mail.sjtu.edu.cn"
            else -> "outlook.office365.com"
        }
        bearerToken = ""
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
            // 只读探测：不读取事件、不保存，只回答来源开关与系统日历授权状态，供首页卡片使用。
            "calendarStatus" -> JSONObject().put("calendarEnabled", calendarEnabled()).put("holidayEnabled", holidayEnabled())
                .put("granted", calendarGranted()).put("selected", calendarSelection().length()).put("effective", effectiveIds().size).toString()
            // 只读：节假日数据范围与依据，供设置页核对；不读取系统日历、不联网。
            "holiday" -> JSONObject().put("name", ChinaHolidays.SOURCE_NAME)
                .put("sections", JSONArray().put("2025年放假安排（国办发明电〔2024〕7号）").put("2026年放假安排（国办发明电〔2025〕6号）"))
                .put("note", "只收录官方通知中的放假日与调休上班日；未覆盖年份只显示常规周末。").toString()
            "mailOpen" -> {
                val t = ImapTransport(); synchronized(this) { check(generation == epoch); transport?.close(); transport = t }
                t.open(prefs.getString("host", "")!!, prefs.getInt("port", 993)); "{}"
            }
            "mailCommand" -> {
                val t = transport ?: error("邮箱未连接")
                val mode = prefs.getString("mode", "password")!!; val user = prefs.getString("user", "")!!
                if (argument == "@LOGIN" && mode in listOf("google", "microsoft")) JSONArray(t.authenticate(user, oauthToken(mode, user))).toString()
                else if (argument == "@LOGIN" && mode == "jaccount") {
                    val token = bearerToken
                    check(token.isNotEmpty()) { "请先在邮箱分析中完成jAccount授权" }
                    JSONArray(t.authenticateBearer(user, token, prefs.getString("host", "")!!, prefs.getInt("port", 993))).toString()
                } else {
                    val command = if (argument == "@LOGIN") "LOGIN " + ImapTransport.quoted(user) + " " + ImapTransport.quoted(decrypt()) else argument
                    JSONArray(t.command(command)).toString()
                }
            }
            else -> error("未知来源操作")
        }
        check(generation == epoch) { "操作已取消" }; return result
    }
    private fun calendar(argument: String): String {
        val args = JSONObject(argument); val now = java.time.ZonedDateTime.now()
        val listOnly = args.optBoolean("listOnly", false)
        // 快速浏览只返回未来7天的日历安排与节假日，供首页“为你提醒”使用：
        // 不读取正文、不写入日历记忆，也不要求勾选来源。
        if (args.optBoolean("quick", false)) return quickCalendar(now)
        val useSystem = calendarEnabled()
        val useHoliday = holidayEnabled()
        check(useSystem || useHoliday) { "请先开启系统日历或常用节假日来源" }
        if (useSystem) check(calendarGranted()) { "请先授权读取系统日历" }
        val historyDays = optionsPrefs.getInt("historyDays", 90)
        val start = now.toLocalDate().minusDays(historyDays.toLong()).atStartOfDay(now.zone).toInstant().toEpochMilli()
        val end = now.plusDays(30).toInstant().toEpochMilli()
        val selected = if (listOnly) JSONArray() else calendarSelection()
        require(selected.length() <= 100)
        val ids = if (listOnly) emptySet() else effectiveIds()
        val sources = JSONArray()
        if (useSystem) sources.put(JSONObject().put("id", ChinaHolidays.SOURCE_ID).put("name", ChinaHolidays.SOURCE_NAME).put("kind", "holiday"))
        // 来源按用户看得懂的类别汇总，同时附上每个类别的具体日历 id，读取仍逐日历进行。
        groupedSources().let { grouped -> for (i in 0 until grouped.length()) sources.put(grouped.getJSONObject(i)) }
        val items = JSONArray()
        if (useSystem && ids.isNotEmpty()) {
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
                        .put("allDay", c.getInt(7) != 0).put("timeZone", c.getString(8) ?: "").put("calendarId", c.getLong(1))
                        .put("source", "system"))
                }
            } ?: error("日历查询失败")
        }
        if (useHoliday && !listOnly) {
            ChinaHolidays.entries(java.time.Instant.ofEpochMilli(start).atZone(now.zone).toLocalDate(),
                java.time.Instant.ofEpochMilli(end).atZone(now.zone).toLocalDate()).forEach { h ->
                check(items.length() < 10000) { "日程过多，请减少选择的日历" }
                val dayStart = java.time.LocalDate.parse(h.start).atStartOfDay(now.zone).toInstant().toEpochMilli()
                val dayEnd = java.time.LocalDate.parse(h.end).plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
                items.put(JSONObject().put("id", h.id).put("title", h.title).put("body", h.description).put("location", "")
                    .put("start", dayStart).put("end", dayEnd).put("allDay", true).put("timeZone", now.zone.id)
                    .put("calendarId", ChinaHolidays.SOURCE_ID).put("source", "holiday").put("kind", ChinaHolidays.KIND))
            }
        }
        if (useSystem) check(calendarGranted())
        return JSONObject().put("sources", sources).put("items", items).put("syncedAt", System.currentTimeMillis())
            .put("windowStart", start).put("windowEnd", end).put("selected", selected)
            .put("systemEnabled", useSystem).put("holidayEnabled", useHoliday).toString()
    }
    /** 首页快速浏览：未来 [QUICK_DAYS] 天的系统日历日程（需已勾选来源且已授权）＋常用节假日。 */
    private fun quickCalendar(now: java.time.ZonedDateTime): String {
        val system = calendarEnabled() && calendarGranted()
        val holiday = holidayEnabled()
        val ids = if (system) effectiveIds() else emptySet()
        val today = now.toLocalDate()
        val startDate = today.minusDays(1)
        val endDate = today.plusDays(QUICK_DAYS.toLong())
        val windowStart = startDate.atStartOfDay(now.zone).toInstant().toEpochMilli()
        val windowEnd = endDate.atStartOfDay(now.zone).toInstant().toEpochMilli()
        val items = JSONArray()
        if (system && ids.isNotEmpty()) {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uri, windowStart); ContentUris.appendId(uri, windowEnd)
            context.contentResolver.query(uri.build(), arrayOf("event_id", "calendar_id", "title", "eventLocation", "begin", "end", "allDay"),
                "calendar_id IN (${ids.joinToString(",") { "?" }})", ids.map { it.toString() }.toTypedArray(), "begin ASC")?.use { c ->
                while (c.moveToNext()) {
                    check(!Thread.currentThread().isInterrupted && calendarGranted())
                    check(items.length() < 500) { "日程过多，请减少选择的日历" }
                    items.put(JSONObject().put("id", "${c.getLong(1)}:${c.getLong(0)}:${c.getLong(4)}")
                        .put("title", (c.getString(2) ?: "未命名日程").take(120)).put("body", "")
                        .put("location", (c.getString(3) ?: "").take(120)).put("start", c.getLong(4)).put("end", c.getLong(5))
                        .put("allDay", c.getInt(6) != 0).put("calendarId", c.getLong(1)).put("source", "system"))
                }
            }
        }
        if (holiday) {
            ChinaHolidays.entries(startDate, endDate).forEach { h ->
                val dayStart = java.time.LocalDate.parse(h.start).atStartOfDay(now.zone).toInstant().toEpochMilli()
                val dayEnd = java.time.LocalDate.parse(h.end).plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
                items.put(JSONObject().put("id", h.id).put("title", h.title).put("body", h.description).put("location", "")
                    .put("start", dayStart).put("end", dayEnd).put("allDay", true).put("calendarId", ChinaHolidays.SOURCE_ID)
                    .put("source", "holiday").put("kind", ChinaHolidays.KIND))
            }
        }
        val memoryFile = File(root, "calendar.json")
        val memorySyncedAt = if (memoryFile.exists()) {
            runCatching { JSONObject(AtomicFile(memoryFile).openRead().use { s -> s.readBytes().toString(Charsets.UTF_8) }).optLong("syncedAt", 0) }.getOrDefault(0)
        } else 0L
        return JSONObject().put("items", items).put("syncedAt", System.currentTimeMillis())
            .put("memorySyncedAt", memorySyncedAt)
            .put("windowStart", windowStart).put("windowEnd", windowEnd)
            .put("systemEnabled", calendarEnabled()).put("holidayEnabled", holiday)
            .put("systemGranted", calendarGranted()).put("selectedCount", ids.size).toString()
    }
    @Synchronized fun write(kind: String, value: String) {
        require(kind in listOf("calendar", "mail") && value.length < 12 * 1024 * 1024)
        if (kind == "calendar") check((calendarEnabled() || holidayEnabled()) && (holidayEnabled() || calendarGranted()))
        JSONObject(value)
        val file = AtomicFile(File(root, "$kind.json")); val out = file.startWrite()
        try { out.write(value.toByteArray()); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    @Synchronized fun read(kind: String): String {
        require(kind in listOf("calendar", "mail"))
        if (kind == "calendar" && !calendarEnabled() && !holidayEnabled()) return "{}"
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
    @Synchronized fun forgetMail() { clear("mail"); bearerToken = ""; check(prefs.edit().clear().commit()) }
    @Synchronized override fun close() { epoch++; transport?.close(); transport = null }
}
