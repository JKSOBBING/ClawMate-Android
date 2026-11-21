package com.clawmate.platform.display

import android.content.AttributionSource
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.Surface
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** app_process entry for a user-authorized adb shell. Android still checks display/launch permissions. */
object DisplayHostMain {
    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"

    @JvmStatic
    fun main(args: Array<String>) {
        check(Process.myUid() == SHELL_UID && Build.VERSION.SDK_INT >= 34)
        val pkg = args.single()
        Looper.prepareMainLooper()
        val thread = Class.forName("android.app.ActivityThread")
        val instance = thread.getMethod("systemMain").invoke(null)
        val system = thread.getDeclaredMethod("getSystemContext").invoke(instance) as Context
        val shell = system.createPackageContext(SHELL_PACKAGE, 0)
        val uid = shell.packageManager.getPackageUid(pkg, 0)
        val handler = Handler(Looper.myLooper()!!)
        val host = DisplayHost(shell, uid, handler)
        DisplayHostConnection(shell, pkg, uid, host::release) { handOff(pkg, uid, host) }.start()
        Looper.loop()
    }

    // Like Android's content shell client, use an external provider handle from the shell process.
    private fun handOff(pkg: String, uid: Int, host: IBinder): IBinder {
        val authority = "$pkg.background-display"
        val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = requireNotNull(manager.javaClass.getMethod("getContentProviderExternal",
            String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(manager, authority, 0, token, "ClawMateDisplay"))
        try {
            val info = holder.javaClass.getField("info").get(holder) as ProviderInfo
            check(info.packageName == pkg && info.applicationInfo.uid == uid &&
                info.applicationInfo.sourceDir == System.getProperty("java.class.path")) { "后台屏助手 APK 与目标应用不匹配" }
            val provider = holder.javaClass.getField("provider").get(holder)
            val call = provider.javaClass.getMethod("call", AttributionSource::class.java,
                String::class.java, String::class.java, String::class.java, Bundle::class.java)
            val caller = AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
            val reply = call.invoke(provider, caller, authority, "attach", null, Bundle().apply { putBinder("host", host) }) as Bundle
            return requireNotNull(reply.getBinder("lifetime"))
        } finally {
            manager.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java,
                IBinder::class.java, Int::class.javaPrimitiveType).invoke(manager, authority, token, 0)
        }
    }
}

private class DisplayHost(
    private val shell: Context, private val allowedUid: Int, private val handler: Handler,
) : IDisplayHost.Stub() {
    private var display: VirtualDisplay? = null
    private var frames: ImageReader? = null
    private var streamSurface: Surface? = null
    private var launchIntent: Intent? = null
    private val expiry = Runnable { release() }

    @Synchronized
    override fun open(target: Intent): Int = openDisplay(target, null, null)

    @Synchronized
    override fun openWithSurface(target: Intent, surface: Surface, width: Int, height: Int, dpi: Int): Int {
        try {
            require(surface.isValid && width > 0 && height > 0 && width.toLong() * height <= 8_000_000 && dpi > 0)
            val metrics = DisplayMetrics().apply { widthPixels = width; heightPixels = height; densityDpi = dpi }
            return openDisplay(target, surface, metrics).also { streamSurface = surface }
        } catch (failure: Throwable) {
            surface.release()
            throw failure
        }
    }

    private fun openDisplay(target: Intent, output: Surface?, dimensions: DisplayMetrics?): Int {
        enforceOwner()
        check(display == null) { "已有后台屏会话，请先停止" }
        val pkg = requireNotNull(target.component?.packageName ?: target.`package`)
        val data = target.data
        require((target.action == Intent.ACTION_MAIN && data == null) ||
            (target.action == Intent.ACTION_VIEW && data?.isHierarchical == true &&
                data.scheme !in setOf(null, "content", "file", "intent") && data.toString().length <= 2048)) {
            "后台屏不支持该启动方式"
        }
        val launch = if (target.action == Intent.ACTION_MAIN) {
            shell.packageManager.getLaunchIntentForPackage(pkg)
                ?: Intent.makeMainActivity(checkNotNull(target.component))
        } else if (pkg == "com.sina.weibo" && data?.scheme == "sinaweibo" && data.host == "searchall") {
            // Weibo's URI proxy is singleTask. On ColorOS it moves even a background user task
            // onto this display, where destroy-on-removal would also destroy the user's old page.
            val component = android.content.ComponentName(pkg, "com.sina.weibo.search.searchv3.SearchActivityV2")
            val info = runCatching { shell.packageManager.getActivityInfo(component, 0) }.getOrNull()
            check(info != null && info.enabled && info.exported && info.permission.isNullOrEmpty() &&
                info.launchMode == android.content.pm.ActivityInfo.LAUNCH_MULTIPLE &&
                info.documentLaunchMode != android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING) {
                "当前微博版本没有可独立打开的公开搜索页，已停止后台搜索。"
            }
            Intent(Intent.ACTION_VIEW, data).setComponent(component)
        } else {
            Intent(Intent.ACTION_VIEW, data).setPackage(pkg)
        }
        val resolved = checkNotNull(launch.resolveActivity(shell.packageManager)) { "目标应用没有可用入口" }
        check(resolved.packageName == pkg)
        launch.component = resolved
        // Isolate both launcher and deep-link starts from the user's warm task.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        // These AOSP flags require the shell's trusted-display permission.
        val trusted = 1 shl 10
        val supportsTouch = 1 shl 6
        val destroyOnRemoval = 1 shl 8
        val ownFocus = 1 shl 14
        val preventFocusSteal = 1 shl 16
        val manager = shell.getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        val metrics = dimensions ?: DisplayMetrics().also { manager.getDisplay(0).getRealMetrics(it) }
        try {
            val sink = output ?: ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
                .also { buffer ->
                    frames = buffer
                    buffer.setOnImageAvailableListener({ reader -> runCatching { reader.acquireLatestImage()?.close() } }, handler)
                }.surface
            // Android accessibility skips private virtual displays owned by the shell.
            // Match Mika's public, own-content-only display so it can observe the approved app without mirroring.
            val surface = checkNotNull(manager.createVirtualDisplay("ClawMate background", metrics.widthPixels, metrics.heightPixels,
                metrics.densityDpi, sink, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    trusted or supportsTouch or destroyOnRemoval or ownFocus or preventFocusSteal))
            display = surface
            handler.postDelayed(expiry, 630_000)
            val id = surface.display.displayId
            launchOnDisplay(launch, id)
            launchIntent = Intent(launch)
            return id
        } catch (failure: Exception) {
            release()
            throw failure
        }
    }

    private fun launchOnDisplay(intent: Intent, displayId: Int) {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        // Use the same ActivityTaskManager launch path as Mika; keep the shell command for API variants.
        val result = runCatching {
            val manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
            val start = manager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            start.invoke(manager, null, "com.android.shell", null, intent, null, null, null, 0, 0, null, options, -2) as Int
        }.getOrNull()
        if (result != null) {
            check(result >= 0) { "系统拒绝在后台屏启动目标应用（$result）" }
            return
        }
        val command = mutableListOf("/system/bin/am", "start", "--display", displayId.toString(),
            "-a", intent.action!!, "-n", checkNotNull(intent.component).flattenToString(),
            "-f", intent.flags.toString())
        intent.categories?.forEach { command.addAll(listOf("-c", it)) }
        intent.dataString?.let { command.addAll(listOf("-d", it)) }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            check(process.waitFor(6, TimeUnit.SECONDS)) { "后台屏启动应用超时" }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.exitValue() == 0 && "Error" !in output) { "系统拒绝在后台屏启动目标应用" }
        } finally {
            process.destroy()
        }
    }

    @Synchronized
    override fun reopenLink(displayId: Int): Boolean {
        enforceOwner()
        val launch = launchIntent ?: return false
        if (display?.display?.displayId != displayId || launch.action != Intent.ACTION_VIEW) return false
        launchOnDisplay(Intent(launch), displayId)
        return true
    }

    @Synchronized
    override fun relaunch(displayId: Int, packageName: String): Boolean {
        enforceOwner()
        val launch = launchIntent ?: return false
        if (display?.display?.displayId != displayId || launch.component?.packageName != packageName) return false
        val stop = ProcessBuilder("/system/bin/am", "force-stop", packageName).redirectErrorStream(true).start()
        try {
            if (!stop.waitFor(6, TimeUnit.SECONDS) || stop.exitValue() != 0) return false
        } finally { stop.destroy() }
        launchOnDisplay(Intent(launch), displayId)
        return true
    }

    @Synchronized
    override fun pressKey(displayId: Int, keyCode: Int): Boolean {
        enforceOwner()
        if (display?.display?.displayId != displayId || displayId <= 0 ||
            keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ENTER) return false
        val input = ProcessBuilder("/system/bin/input", "-d", displayId.toString(),
            "keyevent", keyCode.toString()).redirectErrorStream(true).start()
        return try { input.waitFor(2, TimeUnit.SECONDS) && input.exitValue() == 0 }
        finally { input.destroy() }
    }

    @Synchronized
    override fun close(displayId: Int) {
        enforceOwner()
        if (display?.display?.displayId == displayId) release()
    }

    private fun enforceOwner() {
        check(Binder.getCallingUid() == allowedUid) { "后台屏会话不属于调用方" }
    }

    @Synchronized
    fun release() {
        handler.removeCallbacks(expiry)
        display?.release()
        display = null
        launchIntent = null
        streamSurface?.release()
        streamSurface = null
        frames?.close()
        frames = null
    }
}
