package com.clawmate.app.profile.share.nfc.device

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.clawmate.app.profile.share.PROFILE_SHARE_MIME_TYPE
import com.clawmate.app.profile.share.nfc.KnockNfcStatus
import com.clawmate.app.profile.share.nfc.NfcProfileShareGateway

/**
 * Real, device-backed [NfcProfileShareGateway] for Android's [NfcAdapter].
 *
 * Availability is reported honestly from the actual adapter:
 *  - no [NfcAdapter] (hardware absent or unreachable) → NOT_SUPPORTED;
 *  - hardware present but [NfcAdapter.isEnabled] == false → DISABLED;
 *  - otherwise READY.
 *
 * Sending writes an NdefMessage of the canonical bounded profile payload to a
 * peer [Tag] that presents either [Ndef] or [NdefFormatable]. Receiving is driven
 * by the host Activity's foreground dispatch / reader mode: when an NDEF message
 * of the profile MIME arrives, the host calls [onTagDiscovered], which delivers
 * (declaredMime, payload) to the registered inbound callback.
 *
 * No simulation, no adb/shell, no fake device state: every call is a real NFC
 * operation on the Android NFC stack.
 */
class AndroidNfcProfileShareGateway(
    private val activity: Activity,
) : NfcProfileShareGateway {

    @Volatile
    private var inbound: ((String?, ByteArray) -> Unit)? = null

    private fun adapter(): NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    override fun probeAvailability(): KnockNfcStatus {
        val adapter = adapter()
        if (adapter == null) return KnockNfcStatus.NOT_SUPPORTED
        return if (adapter.isEnabled) KnockNfcStatus.READY else KnockNfcStatus.DISABLED
    }

    override fun startReceive(onMessage: (String?, ByteArray) -> Unit): Boolean {
        if (probeAvailability() != KnockNfcStatus.READY) return false
        inbound = onMessage
        return true
    }

    override fun stopReceive() {
        inbound = null
    }

    override fun writeTag(tagToken: Any, payload: ByteArray): String? {
        val tag = tagToken as? Tag ?: return "无效的 NFC 标签引用"
        return writeNdef(tag, payload)
    }

    /**
     * Entry point for the host Activity's reader / foreground-dispatch callback.
     * Reads the NDEF message, extracts the first record whose MIME type is the
     * profile MIME, and delivers (declaredMime, payload) to the inbound callback.
     */
    fun onTagDiscovered(tag: Tag) {
        val payload = readProfilePayload(tag) ?: return
        inbound?.invoke(PROFILE_SHARE_MIME_TYPE, payload)
    }

    /** Returns the payload byte array of the profile MIME record, or null. */
    private fun readProfilePayload(tag: Tag): ByteArray? {
        val message = readNdefMessage(tag) ?: return null
        for (record in message.records) {
            val type = record.typeToString() ?: continue
            if (type.equals(PROFILE_SHARE_MIME_TYPE, ignoreCase = true)) {
                return if (record.payload.isEmpty()) null else record.payload
            }
        }
        return null
    }

    private fun writeNdef(tag: Tag, payload: ByteArray): String? {
        if (payload.isEmpty()) return "待写入的画像为空"
        val ndefMessage = NdefMessage(arrayOf(NdefRecord.createMime(PROFILE_SHARE_MIME_TYPE, payload)))

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                try {
                    if (!ndef.isWritable) return "该 NFC 标签不可写"
                    if (ndef.maxSize < payload.size) return "画像过大，超出标签容量(${ndef.maxSize} B)"
                    ndef.writeNdefMessage(ndefMessage)
                    null
                } finally {
                    runCatching { ndef.close() }
                }
            } catch (t: Throwable) {
                "写入 NDEF 标签失败：${t.message}"
            }
        }

        val formattable = NdefFormatable.get(tag)
        if (formattable != null) {
            return try {
                formattable.connect()
                try {
                    formattable.format(ndefMessage)
                    null
                } finally {
                    runCatching { formattable.close() }
                }
            } catch (t: Throwable) {
                "格式化并写入 NDEF 标签失败：${t.message}"
            }
        }
        return "该标签不支持 NDEF 读写"
    }

    private fun readNdefMessage(tag: Tag): NdefMessage? {
        val ndef = Ndef.get(tag) ?: return null
        if (ndef.isConnected) {
            return ndef.cachedNdefMessage
        }
        return try {
            ndef.connect()
            ndef.ndefMessage
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun NdefRecord.typeToString(): String? {
        if (tnf != NdefRecord.TNF_MIME_MEDIA) return null
        return try {
            String(type, Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
