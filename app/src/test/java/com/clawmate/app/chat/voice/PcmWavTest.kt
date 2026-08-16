package com.clawmate.app.chat.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Deterministic tests for the PCM16 → WAV builder and chunk concat (PcmWav.ets port). */
class PcmWavTest {

    @Test
    fun `concat empty chunks returns empty`() {
        assertArrayEquals(ByteArray(0), PcmWav.concatPcmChunks(emptyList()))
    }

    @Test
    fun `concat joins chunk bytes in order`() {
        val merged = PcmWav.concatPcmChunks(listOf(byteArrayOf(1, 2), byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), merged)
    }

    @Test
    fun `wav header has standard 44-byte riff layout`() {
        val pcm = ByteArray(4) { 0x61 }
        val wav = PcmWav.buildPcm16WavBytes(pcm, options = PcmWav.Pcm16WavOptions(sampleRate = 24000, channels = 1))
        assertEquals(44 + 4, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        // PCM16, mono
        assertEquals(1, readLe16(wav, 20))
        assertEquals(1, readLe16(wav, 22))
        assertEquals(24000, readLe32(wav, 24))
        assertEquals(16, readLe16(wav, 34))
        // chunk size field
        assertEquals(4, readLe32(wav, 40))
    }

    @Test
    fun `wav body mirrors input pcm bytes`() {
        val pcm = byteArrayOf(0x01, 0x00, 0xff.toByte(), 0x7f)
        val wav = PcmWav.buildPcm16WavBytes(pcm, options = PcmWav.Pcm16WavOptions(sampleRate = 24000, channels = 1))
        assertNotEquals(0, readLe32(wav, 4)) // non-zero RIFF size
        for (i in pcm.indices) {
            assertEquals(pcm[i], wav[44 + i])
        }
    }

    private fun readLe16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
}
