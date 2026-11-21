package com.clawmate.platform.display

import com.wangzan101.mika.core.adb.AdbConnection
import com.wangzan101.mika.core.adb.AdbConnectionException
import com.wangzan101.mika.core.adb.AdbIdentity
import com.wangzan101.mika.core.adb.AdbMessage
import com.wangzan101.mika.core.adb.AdbTls
import com.wangzan101.mika.core.adb.AdbServiceFinder
import com.wangzan101.mika.core.adb.AdbServiceTypes
import java.io.IOException
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WirelessDisplayPairingTest {
    @Test fun taskReusesSavedPairingAndVerifiesTheHelperWasRegistered() = kotlinx.coroutines.runBlocking {
        val paired = WirelessDisplayPairing.State(true, true, false, false, 2345)
        var connected = false
        var attempts = 0
        val connect: suspend () -> Unit = { attempts++; connected = true }
        val noWait: suspend () -> Unit = { error("unexpected wait") }
        assertEquals(null, WirelessDisplayPairing.prepareForTask(paired, -1, { connected }, connect, noWait))
        assertEquals(null, WirelessDisplayPairing.prepareForTask(paired, 1, { connected }, connect, noWait))
        assertEquals(1, attempts)
        val missing = WirelessDisplayPairing.prepareForTask(paired, 1, { false }, {}, noWait)
        org.junit.Assert.assertTrue(missing!!.contains("无线调试"))
    }

    @Test fun disabledWirelessOrMissingPairingCanStillUseAnExternalHelper() = kotlinx.coroutines.runBlocking {
        val paired = WirelessDisplayPairing.State(true, true, false, false, 2345)
        val noConnect: suspend () -> Unit = { error("unexpected wireless connection") }
        val off = WirelessDisplayPairing.prepareForTask(paired, 0, { false }, noConnect, {})
        org.junit.Assert.assertTrue(off!!.contains("系统无线调试已关闭"))
        val first = WirelessDisplayPairing.prepareForTask(paired.copy(paired = false), 1, { false }, noConnect, {})
        org.junit.Assert.assertTrue(first!!.contains("首次配对"))
        var usbReady = false
        assertEquals(null, WirelessDisplayPairing.prepareForTask(paired, 0, { usbReady }, noConnect, { usbReady = true }))
    }

    @Test fun authorizedFirstPairFindsBothPortsAndReconnectSkipsPairing() = kotlinx.coroutines.runBlocking {
        val actions = mutableListOf<String>()
        val phases = mutableListOf<String>()
        val discovery = AdbServiceFinder { type, _ ->
            actions += type
            if (type == AdbServiceTypes.PAIRING) listOf(1234) else listOf(2300, 2345)
        }
        val fresh = WirelessDisplayPairing.State(true, false, false, false, null)
        val pair: suspend (Int, String) -> Unit = { port, code ->
            assertEquals("123456", code); actions += "pair $port"
        }
        val connect: suspend (Int) -> Unit = { port ->
            actions += "connect $port"; if (port == 2300) throw IOException("fixture stale")
        }
        WirelessDisplayPairing.autoSetup(fresh, "123456", discovery, { phases += it }, pair, connect)
        assertEquals(listOf(AdbServiceTypes.PAIRING, "pair 1234", AdbServiceTypes.CONNECT, "connect 2300", "connect 2345"), actions)
        org.junit.Assert.assertFalse(phases.any { it.contains("123456") })
        actions.clear()
        WirelessDisplayPairing.autoSetup(fresh.copy(paired = true), null, discovery, {}, pair, connect)
        assertEquals(listOf(AdbServiceTypes.CONNECT, "connect 2300", "connect 2345"), actions)
    }

    @Test fun missingOrAmbiguousServiceAndWrongCodeNeverStartTheHelper() {
        val state = WirelessDisplayPairing.State(true, false, false, false, null)
        for (ports in listOf(emptyList(), listOf(2345, 2346), listOf(2345))) {
            var pairAttempts = 0
            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    WirelessDisplayPairing.autoSetup(state, "123456", AdbServiceFinder { _, _ -> ports }, {},
                        { _, _ -> pairAttempts++; throw IOException("fixture wrong code") }, { error("unexpected connect") })
                }
            }
            assertEquals(if (ports.size == 1) 1 else 0, pairAttempts)
        }
    }

    @Test fun wirelessTransportRejectsUnpairedPlaintextListener() {
        val tls = AdbTls(AdbIdentity.generate("test"), { _, _, _ -> error("TLS exporter should not be needed") })
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val replied = CompletableFuture.runAsync {
                listener.accept().use { socket ->
                    val hello = AdbMessage.read(DataInputStream(socket.getInputStream()))
                    assertEquals(AdbMessage.CNXN, hello?.command)
                    socket.getOutputStream().write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, 4096).encode())
                }
            }
            val error = assertThrows(AdbConnectionException::class.java) {
                AdbConnection.openWireless(listener.localPort, tls, 2_000)
            }
            assertEquals(AdbConnectionException.Reason.PROTOCOL, error.reason)
            replied.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun launchUsesTheInstalledApkAndFixedEntryPoint() {
        val apk = "/data/app/~~AbCd==/com.clawmate.shared.dsh-123/base.apk"
        assertEquals("CLASSPATH='$apk' exec /system/bin/app_process /system/bin " +
            "com.clawmate.platform.display.WirelessDisplayHostMain 'com.clawmate.shared.dsh'",
            WirelessDisplayPairing.helperCommand(apk, "com.clawmate.shared.dsh"))
        assertThrows(IllegalArgumentException::class.java) {
            WirelessDisplayPairing.helperCommand(apk, "com.clawmate.shared extra")
        }
    }
}
