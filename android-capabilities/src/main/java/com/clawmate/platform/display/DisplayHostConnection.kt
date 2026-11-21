// Adapted from Mika HelperProcess at f881d83f (Apache-2.0). See third_party/mika/LICENSE.
package com.clawmate.platform.display

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

/** Retain the shell helper across app death and hand it back when the app's UID returns. */
internal class DisplayHostConnection(
    private val context: Context,
    private val packageName: String,
    private val appUid: Int,
    private val release: () -> Unit,
    private val deliver: () -> IBinder,
) {
    private val worker = Handler(HandlerThread("display-handoff").apply { start() }.looper)
    private var appToken: IBinder? = null
    private var retrying = false
    private lateinit var uidManager: ActivityManager
    private val retryDelays = longArrayOf(0, 1_000, 3_000, 10_000)

    fun start() = worker.post {
        watchUid()
        redeliver(0)
    }

    private fun onAppDied(token: IBinder) {
        if (appToken !== token) return
        appToken = null
        release()
        println("ClawMate app disconnected; waiting for UID to return")
        // A quick process replacement can keep the UID in the same importance band, so Android
        // need not emit another crossing. Only reattach if a process actually exists; never
        // resurrect a force-stopped app just because its previous Binder died.
        for (delayMs in longArrayOf(500, 1_500, 3_000)) worker.postDelayed({
            if (appToken == null && !retrying &&
                uidImportance() != ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) {
                redeliver(0)
            }
        }, delayMs)
    }

    private fun uidImportance(): Int = runCatching {
        ActivityManager::class.java.getMethod("getUidImportance", Int::class.javaPrimitiveType)
            .invoke(uidManager, appUid) as Int
    }.getOrDefault(ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE)

    private fun redeliver(attempt: Int) {
        if (appToken != null) return
        // An APK update needs a helper started from the new APK. Never attach stale code.
        val installed = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        if (installed == null || installed.uid != appUid ||
            installed.sourceDir != System.getProperty("java.class.path")) {
            release()
            exitProcess(0)
        }
        try {
            val token = deliver()
            appToken = token
            try { token.linkToDeath({ worker.post { onAppDied(token) } }, 0) }
            catch (_: android.os.RemoteException) { onAppDied(token) }
            retrying = false
            println("ClawMate background display host connected")
        } catch (failure: Exception) {
            retrying = attempt + 1 < retryDelays.size
            System.err.println("Display handoff failed: ${failure.javaClass.simpleName}")
            if (retrying) worker.postDelayed({ redeliver(attempt + 1) }, retryDelays[attempt + 1])
        }
    }

    private fun watchUid() {
        val manager = ActivityManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }.newInstance(context, worker)
        uidManager = manager
        val listenerClass = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
        val listener = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
            when (method.name) {
                "onUidImportance" -> {
                    val uid = args!![0] as Int
                    val importance = args[1] as Int
                    if (uid == appUid && importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) {
                        worker.post { if (appToken == null && !retrying) redeliver(0) }
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "ClawMateDisplayUidListener"
                else -> null
            }
        }
        ActivityManager::class.java.getMethod("addOnUidImportanceListener", listenerClass, Int::class.javaPrimitiveType)
            .invoke(manager, listener, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
    }
}
