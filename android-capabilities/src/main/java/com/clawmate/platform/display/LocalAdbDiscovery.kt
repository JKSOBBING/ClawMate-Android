package com.clawmate.platform.display

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import com.wangzan101.mika.core.adb.AdbServiceFinder
import com.wangzan101.mika.core.adb.AdbServiceTypes
import java.net.NetworkInterface
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Adapted from Mika's NsdAdbServiceFinder. Only the local device is eligible. */
internal class LocalAdbDiscovery(context: Context) : AdbServiceFinder {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> {
        require(serviceType == AdbServiceTypes.PAIRING || serviceType == AdbServiceTypes.CONNECT)
        val found = Channel<NsdServiceInfo>(32)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { found.close() }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
        }
        val ports = linkedSetOf<Int>()
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            val end = SystemClock.elapsedRealtime() + timeoutMs
            var deadline = end
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val info = withTimeoutOrNull(deadline - SystemClock.elapsedRealtime()) {
                        found.receiveCatching().getOrNull()
                    } ?: break
                    resolveLocalPort(info)?.let { port ->
                        if (ports.isEmpty()) deadline = minOf(end, SystemClock.elapsedRealtime() + 400)
                        ports.add(port)
                    }
                }
            }
        } finally {
            found.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return ports.toList()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLocalPort(info: NsdServiceInfo): Int? {
        val result = Channel<Int?>(1)
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, error: Int) { result.trySend(null) }
            override fun onServiceResolved(service: NsdServiceInfo) {
                val local = service.hostAddresses.any { address ->
                    address.isLoopbackAddress || runCatching { NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false)
                }
                result.trySend(service.port.takeIf { local && it in 1..65535 })
            }
        }
        nsd.resolveService(info, listener)
        return try { withTimeoutOrNull(1500) { result.receive() } }
        finally { result.close(); runCatching { nsd.stopServiceResolution(listener) } }
    }
}
