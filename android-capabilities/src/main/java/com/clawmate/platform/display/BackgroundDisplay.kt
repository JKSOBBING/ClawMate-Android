package com.clawmate.platform.display

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import java.io.Closeable
import kotlinx.coroutines.delay

/** A display belongs to one approved launch and one app process. */
class BackgroundDisplay private constructor(val id: Int, private val host: IDisplayHost,
    internal val agentDisplay: AgentDisplay? = null, internal val launchedAtNanos: Long = 0L,
) : Closeable {
    val connected: Boolean get() = host.asBinder().isBinderAlive

    internal fun pressKey(keyCode: Int): Boolean = host.pressKey(id, keyCode)
    fun relaunch(packageName: String): Boolean = host.relaunch(id, packageName)
    fun reopenLink(): Boolean = host.reopenLink(id)

    override fun close() {
        if (agentDisplay != null) agentDisplay.release() else runCatching { host.close(id) }
    }

    companion object {
        @Volatile private var host: IDisplayHost? = null
        private val lifetime = Binder()
        val available: Boolean get() = host?.asBinder()?.isBinderAlive == true

        @Synchronized
        internal fun attach(remote: IBinder): IBinder {
            check(!available) { "后台屏助手已连接" }
            val proxy = IDisplayHost.Stub.asInterface(remote)
            remote.linkToDeath({
                synchronized(this) { if (host?.asBinder() === remote) host = null }
            }, 0)
            host = proxy
            return lifetime
        }

        /** Call after the user approves this exact launch, off the UI thread. */
        fun open(approved: Intent): BackgroundDisplay {
            val service = checkNotNull(host?.takeIf { it.asBinder().isBinderAlive }) { "请先连接 ClawMate 后台屏助手" }
            val id = service.open(approved)
            check(id > 0) { "后台屏创建失败" }
            return BackgroundDisplay(id, service)
        }

        internal suspend fun openForAgent(context: Context, approved: Intent, detailedText: Boolean = false): BackgroundDisplay {
            val service = checkNotNull(host?.takeIf { it.asBinder().isBinderAlive }) { "请先连接 ClawMate 后台屏助手" }
            val displays = context.getSystemService(DisplayManager::class.java)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            checkNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY)).getRealMetrics(metrics)
            // Render small order text at a higher native glyph resolution without changing the layout in dp.
            val scale = if (detailedText) minOf(1.5, 1536.0 / minOf(metrics.widthPixels, metrics.heightPixels),
                kotlin.math.sqrt(5_000_000.0 / (metrics.widthPixels.toDouble() * metrics.heightPixels))).coerceAtLeast(1.0) else 1.0
            val started = System.nanoTime()
            val agent = AgentDisplay(
                { surface, width, height, dpi -> service.openWithSurface(approved, surface, width, height, dpi) },
                { service.close(it) }, (metrics.widthPixels * scale).toInt(), (metrics.heightPixels * scale).toInt(),
                (metrics.densityDpi * scale).toInt(), displays,
            )
            return try {
                FrameFreshness.awaitNewFrame(SystemClock::uptimeMillis, { delay(it) }, agent::lastFrameAt, started)
                BackgroundDisplay(agent.displayId, service, agent, started)
            } catch (failure: Throwable) {
                agent.release()
                throw failure
            }
        }
    }
}

/** Entry for an explicitly authorized adb shell helper on this device. */
class BackgroundDisplayProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000 && method == "attach") { "仅 Android 调试 shell 可以注册后台屏助手" }
        val remote = requireNotNull(extras?.getBinder("host"))
        return Bundle().apply { putBinder("lifetime", BackgroundDisplay.attach(remote)) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
