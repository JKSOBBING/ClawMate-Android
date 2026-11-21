package com.clawmate.shared

import android.content.Context
import android.content.ContextWrapper
import android.net.ssl.SSLSockets
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.display.WirelessDisplayPairing
import com.wangzan101.mika.core.adb.AdbIdentity
import com.wangzan101.mika.core.adb.AdbPairing
import com.wangzan101.mika.core.adb.AdbTls
import com.wangzan101.mika.core.adb.KeyingMaterialExporter
import com.wangzan101.mika.core.adb.PairingAuth
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WirelessDisplayPairingDeviceTest {
    // Both peers live in this test. Never pair with adbd or use the owner's identity.
    private suspend fun exchange(context: Context, matchingCode: Boolean) = coroutineScope {
        val code = (100000 + SecureRandom().nextInt(900000)).toString()
        val clientCode = if (matchingCode) code else "000000"
        val tls = AdbTls(AdbIdentity.generate("fixture"), KeyingMaterialExporter { socket, label, length ->
            requireNotNull(SSLSockets.exportKeyingMaterial(socket, label, byteArrayOf(), length))
        })
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 12000
            val peer = async(Dispatchers.IO) {
                server.accept().use { plain ->
                    plain.soTimeout = 12000
                    tls.wrap(plain, InetSocketAddress(plain.localAddress, server.localPort), false).use { socket ->
                        val input = DataInputStream(socket.inputStream)
                        val output = socket.outputStream
                        val exported = tls.exportKeyingMaterial(socket, AdbPairing.EXPORT_LABEL, AdbPairing.EXPORT_LENGTH)
                        val auth = PairingAuth.server(code.toByteArray() + exported)
                        val hello = requireNotNull(AdbPairing.readPacket(input, AdbPairing.TYPE_SPAKE2))
                        AdbPairing.writePacket(output, AdbPairing.TYPE_SPAKE2, auth.message)
                        auth.initCipher(hello)
                        val encrypted = requireNotNull(AdbPairing.readPacket(input, AdbPairing.TYPE_PEER_INFO))
                        try {
                            val info = auth.decrypt(encrypted)
                            assertEquals(AdbPairing.PEER_RSA_PUB_KEY, info[0])
                        } catch (badCode: AEADBadTagException) {
                            if (matchingCode) throw badCode
                            return@async
                        }
                        val reply = ByteArray(AdbPairing.PEER_INFO_SIZE)
                        reply[0] = AdbPairing.PEER_DEVICE_GUID
                        "adb-local-fixture".toByteArray().copyInto(reply, 1)
                        AdbPairing.writePacket(output, AdbPairing.TYPE_PEER_INFO, auth.encrypt(reply))
                    }
                }
            }
            val result = runCatching { WirelessDisplayPairing.pair(context, server.localPort, clientCode) }
            peer.await()
            if (matchingCode) result.getOrThrow() else assertTrue("A different code must be rejected", result.isFailure)
        }
    }

    @Test fun localHandshakeRejectsWrongCodeAndPersistsUntilForgotten() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.nio.file.Files.createTempDirectory(base.cacheDir.toPath(), "wireless-fixture-").toFile()
        val prefs = directory.name
        fun isolatedContext(): Context = object : ContextWrapper(base) {
            override fun getPackageName() = base.packageName + ".wirelessfixture"
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefs, mode)
        }
        val context = isolatedContext()
        try {
            assertFalse(WirelessDisplayPairing.status(context).paired)
            exchange(context, false)
            assertFalse(WirelessDisplayPairing.status(context).paired)
            exchange(context, true)
            assertTrue(WirelessDisplayPairing.status(isolatedContext()).paired)
            WirelessDisplayPairing.forget(context)
            assertFalse(WirelessDisplayPairing.status(isolatedContext()).paired)
        } finally {
            WirelessDisplayPairing.forget(context)
            base.deleteSharedPreferences(prefs)
            directory.deleteRecursively()
        }
        Unit
    }
}
