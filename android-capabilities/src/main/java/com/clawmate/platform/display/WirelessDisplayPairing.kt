package com.clawmate.platform.display

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.wangzan101.mika.core.adb.AdbConnection
import com.wangzan101.mika.core.adb.AdbConnectionException
import com.wangzan101.mika.core.adb.AdbKeyState
import com.wangzan101.mika.core.adb.AdbKeyUnreadableException
import com.wangzan101.mika.core.adb.AdbPairing
import com.wangzan101.mika.core.adb.AdbPairingException
import com.wangzan101.mika.core.adb.AdbServiceFinder
import com.wangzan101.mika.core.adb.AdbServiceTypes
import java.io.Closeable
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** User-initiated wireless setup for the display helper installed in this app's APK. */
object WirelessDisplayPairing {
    data class State(val supported: Boolean, val paired: Boolean, val connected: Boolean,
                     val helperAvailable: Boolean, val lastConnectPort: Int?)

    private val setup = Mutex()
    private val taskReconnect = Mutex()
    @Volatile private var session: HelperSession? = null

    private fun prefs(context: Context) = context.getSharedPreferences("wireless_display_pairing", Context.MODE_PRIVATE)

    fun status(context: Context): State {
        val settings = prefs(context)
        val paired = settings.getBoolean("paired", false) && WirelessDisplayIdentity(context).file.isFile
        val available = BackgroundDisplay.available
        return State(Build.VERSION.SDK_INT >= 34, paired,
            available && session?.let { it.owner == context.packageName && it.alive } == true,
            available, settings.getInt("port", 0).takeIf { paired && it in 1..65535 })
    }

    suspend fun autoSetup(context: Context, code: String?, progress: (String) -> Unit) {
        requireSupported()
        autoSetup(status(context), code, LocalAdbDiscovery(context), progress,
            { port, secret -> pair(context, port, secret) }, { port -> connect(context, port) })
    }

    internal suspend fun autoSetup(state: State, code: String?, discovery: AdbServiceFinder,
        progress: (String) -> Unit, pairLocal: suspend (Int, String) -> Unit, connectLocal: suspend (Int) -> Unit) {
        if (state.helperAvailable) return
        if (!state.paired) {
            require(code != null && code.matches(Regex("[0-9]{6}"))) { "请输入系统当前显示的六位配对码" }
            progress("正在查找本机配对端口，请保持系统配对弹窗打开…")
            val ports = discovery.findPorts(AdbServiceTypes.PAIRING, 10_000)
            val port = ports.singleOrNull() ?: throw IOException(if (ports.isEmpty())
                "未发现本机配对端口。请确认手机已连接 Wi-Fi，保持系统配对弹窗打开后重试，或使用手动设置。"
                else "发现多条本机配对记录。请使用手动设置，输入系统弹窗当前显示的端口。")
            progress("正在与本机配对…")
            pairLocal(port, code)
        }
        progress("已保存配对，正在查找连接端口并启动助手…")
        connectSaved(state.lastConnectPort, discovery, connectLocal)
    }

    private suspend fun connectSaved(lastPort: Int?, discovery: AdbServiceFinder, connectLocal: suspend (Int) -> Unit) {
        val ports = discovery.findPorts(AdbServiceTypes.CONNECT, 10_000)
        val candidates = (ports + listOfNotNull(lastPort)).distinct()
        if (candidates.isEmpty()) throw IOException("配对已保存。请确认无线调试已开启，再点自动连接；也可手动输入连接端口。")
        var last: IOException? = null
        for (port in candidates) {
            try { connectLocal(port); return }
            catch (e: IOException) { last = e }
        }
        throw last ?: IOException("请返回无线调试页核对连接状态后重试。")
    }

    /** A foreground task can reuse an existing pairing. A new pairing stays in the setup UI. */
    suspend fun prepareForTask(context: Context): String? {
        if (BackgroundDisplay.available) return null
        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            taskReconnect.withLock {
                val state = status(app)
                val enabled = try {
                    Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", -1)
                } catch (_: SecurityException) { -1 }
                prepareForTask(state, enabled, { BackgroundDisplay.available }, {
                    connectSaved(state.lastConnectPort, LocalAdbDiscovery(app)) { port -> connect(app, port) }
                }, {
                    // A separately started USB helper can still attach while the task is opening.
                    withTimeoutOrNull(8_000) { while (!BackgroundDisplay.available) delay(250) }
                })
            }
        }
    }

    internal suspend fun prepareForTask(state: State, wifiSetting: Int, available: () -> Boolean,
        reconnect: suspend () -> Unit, awaitExternal: suspend () -> Unit): String? {
        if (available()) return null
        if (state.paired && wifiSetting != 0) {
            val failure = try {
                withTimeoutOrNull(24_000) { reconnect() }
                "虚拟屏助手未连接。配对已保存，请确认系统「开发者选项 → 无线调试」已开启后重试。"
            } catch (e: CancellationException) { throw e }
            catch (e: IOException) { "虚拟屏助手未连接。配对已保存。${e.message.orEmpty()}" }
            catch (_: IllegalStateException) { "虚拟屏助手未连接，请在「界面采集与规划 → 无线配对」核对连接状态。" }
            return failure.takeUnless { available() }
        }
        awaitExternal()
        if (available()) return null
        return if (state.paired) "系统无线调试已关闭，配对仍已保存。请在「开发者选项 → 无线调试」开启后重试，任务会自动连接助手。"
            else "虚拟屏助手未连接。请从「界面采集与规划 → 无线配对」完成首次配对。"
    }

    suspend fun pair(context: Context, pairingPort: Int, code: String): Unit = withContext(Dispatchers.IO) {
        requireSupported()
        require(pairingPort in 1..65535) { "请填写系统配对弹窗中的配对端口" }
        require(Regex("[0-9]{6}").matches(code)) { "请输入系统当前显示的六位配对码" }
        setup.withLock {
            val keys = WirelessDisplayIdentity(context).keys
            val identity = keys.forPairing()
            val settings = prefs(context)
            if (keys.state == AdbKeyState.Created) {
                check(settings.edit().clear().commit()) { "无法重置失效的配对记录" }
            }
            val peer = try {
                AdbPairing(WirelessDisplayIdentity.tls(identity)).pair("127.0.0.1", pairingPort, code)
            } catch (e: AdbPairingException) {
                val reason = when (e.reason) {
                    AdbPairingException.Reason.WRONG_CODE -> "配对码不匹配或已过期，请重新打开系统配对弹窗"
                    AdbPairingException.Reason.CONNECT_FAILED -> "连接不到本机配对端口，请核对端口并保持系统配对弹窗打开"
                    else -> "配对握手失败，请核对系统配对弹窗中的端口和配对码"
                }
                throw IOException(reason, e)
            } catch (e: IOException) {
                throw IOException("配对中断，请重新打开系统配对弹窗后再试", e)
            }
            check(peer.type == 1 && peer.data.isNotBlank()) { "系统未返回有效的无线配对回执" }
            check(settings.edit().putBoolean("paired", true).commit()) { "无法保存本机配对记录" }
        }
    }

    suspend fun connect(context: Context, connectPort: Int): Unit = withContext(Dispatchers.IO) {
        requireSupported()
        require(connectPort in 1..65535) { "请填写无线调试首页的连接端口" }
        setup.withLock {
            val state = status(context)
            check(state.paired) { "请先使用本机系统配对码配对" }
            if (state.connected) return@withLock
            check(!state.helperAvailable) { "后台屏助手已通过其他连接启动，可直接使用。切换无线前请先关闭原助手。" }
            session?.close()
            session = null
            val identity = try {
                WirelessDisplayIdentity(context).keys.forConnect()
            } catch (e: AdbKeyUnreadableException) {
                throw IOException("本机配对资料已失效，请重新配对", e)
            }
            val adb = try {
                AdbConnection.openWireless(connectPort, WirelessDisplayIdentity.tls(identity), timeoutMs = 6_000)
            } catch (e: AdbConnectionException) {
                if (e.reason == AdbConnectionException.Reason.UNAUTHORIZED) {
                    throw IOException("系统未接受此配对，请在已配对设备中核对后重新配对", e)
                }
                throw IOException("无线连接失败，请核对系统无线调试首页的连接端口", e)
            } catch (e: IOException) {
                throw IOException("连接中断，请检查无线调试和连接端口", e)
            }
            var pending: HelperSession? = null
            try {
                val stream = adb.exec(helperCommand(context.applicationInfo.sourceDir, context.packageName))
                val helper = HelperSession(context.packageName, adb, stream)
                pending = helper
                val attached = withTimeoutOrNull(12_000) { helper.attached.await() } == true
                check(attached && helper.alive && BackgroundDisplay.available) { "本机已连接，但后台屏助手未能注册，请重新连接" }
                check(prefs(context).edit().putInt("port", connectPort).commit()) { "无法保存连接端口" }
                session = helper
                pending = null
            } catch (e: IOException) {
                throw IOException("无法启动本机后台屏助手，请重新连接", e)
            } finally {
                pending?.close()
                if (session?.connection !== adb) adb.close()
            }
        }
    }

    suspend fun forget(context: Context): Unit = withContext(Dispatchers.IO) {
        setup.withLock {
            session?.takeIf { it.owner == context.packageName }?.let {
                session = null
                it.close()
            }
            val cleared = prefs(context).edit().clear().commit()
            WirelessDisplayIdentity(context).keys.forget()
            check(cleared) { "无法清除本机配对记录" }
        }
    }

    private fun requireSupported() {
        check(Build.VERSION.SDK_INT >= 34) { "后台屏助手需要 Android 14 或更高版本" }
    }

    internal fun helperCommand(apk: String, pkg: String): String {
        require(apk.startsWith('/') && apk.endsWith(".apk")) { "无法确定本机应用安装路径" }
        require(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(pkg)) { "无效的应用包名" }
        val quotedApk = apk.replace("'", "'\\''")
        return "CLASSPATH='$quotedApk' exec /system/bin/app_process /system/bin " +
            "com.clawmate.platform.display.WirelessDisplayHostMain '$pkg'"
    }

    private class HelperSession(val owner: String, val connection: AdbConnection,
                                private val stream: AdbConnection.AdbStream) : Closeable {
        val attached = CompletableDeferred<Boolean>()
        @Volatile private var ended = false
        val alive: Boolean get() = !ended && !connection.isClosed

        init {
            thread(name = "wireless-display-status", isDaemon = true) {
                try {
                    stream.inputStream.bufferedReader().forEachLine { line ->
                        if (line == "ClawMate background display host connected") attached.complete(true)
                    }
                } catch (_: IOException) {
                    // The platform closes this stream when wireless debugging is turned off.
                } finally {
                    ended = true
                    attached.complete(false)
                    connection.close()
                }
            }
        }

        override fun close() {
            try {
                if (alive) runCatching { stream.write(byteArrayOf(0)) }
            } finally {
                stream.close()
                connection.close()
            }
        }
    }
}

/** The wireless helper lives only while its app-owned ADB stream remains open. */
object WirelessDisplayHostMain {
    @JvmStatic fun main(args: Array<String>) {
        thread(name = "wireless-display-lifetime", isDaemon = true) {
            try { System.`in`.read() } finally { exitProcess(0) }
        }
        DisplayHostMain.main(args)
    }
}
