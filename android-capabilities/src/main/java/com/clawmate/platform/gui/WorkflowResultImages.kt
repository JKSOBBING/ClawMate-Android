package com.clawmate.platform.gui

import java.io.File
import java.io.IOException

/** A bounded window of screen observations. Publish a complete step with one directory rename. */
internal class WorkflowResultImages(private val filesDir: File, private val runName: String) {
    companion object {
        const val MAX_COUNT = 8
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        private const val MAX_RUN_BYTES = 32 * 1024 * 1024
    }
    init { require(runName.matches(Regex("android-[0-9]{1,19}-[0-9]{1,10}"))) }
    private val frames = ArrayDeque<ByteArray>()
    private var count = 0
    private var invocation = 0
    private var bytesWritten = 0
    private var runDirectory: File? = null
    val collecting get() = count > 0

    fun begin(limit: Int) {
        require(limit in 0..MAX_COUNT)
        frames.clear()
        count = limit
        invocation++
    }

    fun observe(jpeg: ByteArray?) {
        if (!collecting) return
        if (jpeg == null || jpeg.isEmpty() || jpeg.size > MAX_IMAGE_BYTES) {
            frames.clear()
            return
        }
        if (frames.lastOrNull()?.contentEquals(jpeg) == true) return
        if (frames.size == count) frames.removeFirst()
        frames.addLast(jpeg)
    }

    fun publish(standaloneName: String? = null): List<String> {
        if (standaloneName != null) require(standaloneName.matches(Regex("[A-Za-z0-9_-]{1,64}\\.jpe?g")) && frames.size == 1)
        if (frames.isEmpty()) return emptyList()
        val bytes = frames.sumOf { it.size }
        if (bytes > MAX_RUN_BYTES - bytesWritten) throw IOException("Workflow screenshot size limit")
        val relativeRun = "workflows/runs/$runName"
        val parent = runDirectory ?: File(filesDir, relativeRun).also {
            it.parentFile?.mkdirs()
            if (!it.mkdir()) throw IOException("Cannot reserve workflow run directory")
            runDirectory = it
        }
        val step = if (standaloneName == null) "gui-$invocation" else "shot-$invocation"
        val staging = File(parent, ".$step")
        if (!staging.mkdir()) throw IOException("Cannot stage workflow images")
        try {
            val paths = frames.mapIndexed { index, jpeg ->
                val name = standaloneName ?: ("result_screenshot_" + (index + 1).toString().padStart(2, '0') + ".jpg")
                File(staging, name).writeBytes(jpeg)
                "$relativeRun/$step/$name"
            }
            if (!staging.renameTo(File(parent, step))) throw IOException("Cannot publish workflow images")
            bytesWritten += bytes
            frames.clear()
            return paths
        } finally {
            if (staging.exists()) {
                staging.listFiles()?.forEach { it.delete() }
                staging.delete()
            }
        }
    }
}
