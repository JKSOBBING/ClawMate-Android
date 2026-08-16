package com.clawmate.app.chat.voice

/**
 * PCM16 → WAV bytes and chunk concatenation. Port of HarmonyOS
 * `entry/src/main/ets/utils/voice/PcmWav.ets`.
 *
 * Used to wrap raw PCM16LE streaming-TTS audio into a standard RIFF/WAVE file
 * so a cache can be replayed. Byte-exact and deterministic (unit-testable).
 */
object PcmWav {

    private const val PCM16_BITS_PER_SAMPLE: Int = 16
    private const val WAV_HEADER_BYTES: Int = 44

    data class Pcm16WavOptions(
        val sampleRate: Int,
        val channels: Int,
    )

    fun concatPcmChunks(chunks: List<ByteArray>): ByteArray {
        val total = chunks.sumOf { it.size }
        val output = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(output, destinationOffset = offset)
            offset += chunk.size
        }
        return output
    }

    /** Builds a RIFF/WAVE header object for [pcm] bytes. */
    fun buildPcm16WavBytes(pcm: ByteArray, options: Pcm16WavOptions): ByteArray {
        val channels = maxOf(1, options.channels)
        val sampleRate = maxOf(1, options.sampleRate)
        val byteRate = sampleRate * channels * PCM16_BITS_PER_SAMPLE / 8
        val blockAlign = channels * PCM16_BITS_PER_SAMPLE / 8
        val output = ByteArray(WAV_HEADER_BYTES + pcm.size)
        writeAscii(output, 0, "RIFF")
        writeLe32(output, 4, 36 + pcm.size)
        writeAscii(output, 8, "WAVE")
        writeAscii(output, 12, "fmt ")
        writeLe32(output, 16, 16)
        writeLe16(output, 20, 1)
        writeLe16(output, 22, channels)
        writeLe32(output, 24, sampleRate)
        writeLe32(output, 28, byteRate)
        writeLe16(output, 32, blockAlign)
        writeLe16(output, 34, PCM16_BITS_PER_SAMPLE)
        writeAscii(output, 36, "data")
        writeLe32(output, 40, pcm.size)
        pcm.copyInto(output, destinationOffset = WAV_HEADER_BYTES)
        return output
    }

    private fun writeAscii(bytes: ByteArray, offset: Int, value: String) {
        for (i in value.indices) {
            bytes[offset + i] = value[i].code.toByte()
        }
    }

    private fun writeLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeLe32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
