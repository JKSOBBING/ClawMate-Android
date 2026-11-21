// Adapted from Mika's AndroidAdb (Apache-2.0). See third_party/mika/LICENSE.
package com.clawmate.platform.display

import android.content.Context
import android.net.ssl.SSLSockets
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wangzan101.mika.core.adb.AdbIdentity
import com.wangzan101.mika.core.adb.AdbIdentitySource
import com.wangzan101.mika.core.adb.AdbKeyStore
import com.wangzan101.mika.core.adb.AdbTls
import com.wangzan101.mika.core.adb.WrappedKeyFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.SSLException

internal class WirelessDisplayIdentity(context: Context) {
    val file = File(context.noBackupFilesDir, "wireless-display/identity.bin")
    private val alias = context.packageName + ".wireless_display_adb"
    private val wrapped = WrappedKeyFile(file, ::wrappingKey)
    private val store = object : AdbKeyStore by wrapped {
        override fun clear() {
            keyStore().let { if (it.containsAlias(alias)) it.deleteEntry(alias) }
            wrapped.clear()
            check(!file.exists()) { "无法删除本机配对资料" }
        }
    }
    val keys = AdbIdentitySource(store, { "ClawMate@${context.packageName}" })

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun wrappingKey(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    companion object {
        fun tls(identity: AdbIdentity) = AdbTls(identity, { socket, label, length ->
            SSLSockets.exportKeyingMaterial(socket, label, ByteArray(0), length)
                ?: throw SSLException("TLS key export failed")
        })
    }
}
