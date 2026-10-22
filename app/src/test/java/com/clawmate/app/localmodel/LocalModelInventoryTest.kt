package com.clawmate.app.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deterministic tests for the model inventory scanner using an in-memory
 * [LocalModelInventory.ModelFolderProbe] seam. Ports the HarmonyOS validation
 * rule: a model dir is loadable when it has config.json + a weight file.
 */
class LocalModelInventoryTest {

    @Test
    fun `empty filesDir yields no models`() {
        val probe = FakeProbe()
        assertEquals(0, LocalModelInventory.scan("/data/files", probe).size)
    }

    @Test
    fun `complete legacy model dir is discovered and loadable`() {
        val probe = FakeProbe()
        probe.addFile("/data/files/model/config.json", 100)
        probe.addFile("/data/files/model/model.mnn", 2000)
        val models = LocalModelInventory.scan("/data/files", probe)
        assertEquals(1, models.size)
        assertEquals("legacy:model", models[0].id)
        assertTrue(models[0].hasConfig)
        assertTrue(models[0].hasWeightFile)
        assertTrue(models[0].ready)
        assertTrue(models[0].path.startsWith(File("/data/files/model").absolutePath))
    }

    @Test
    fun `weight file detection recurses and honors extensions`() {
        val probe = FakeProbe()
        probe.addFile("/data/files/model/config.json", 100)
        probe.addFile("/data/files/model/layers/layer.om", 4321)
        val models = LocalModelInventory.scan("/data/files", probe)
        assertEquals(1, models.size)
        assertTrue(models[0].ready)

        // extend to check .safetensors / .weight too
        assertTrue(LocalModelInventory.isWeightFile("model.safetensors"))
        assertTrue(LocalModelInventory.isWeightFile("x.weight"))
        assertFalse(LocalModelInventory.isWeightFile("config.json"))
        assertFalse(LocalModelInventory.isWeightFile("layer.bin"))
    }

    @Test
    fun `model dir missing config is not ready`() {
        val probe = FakeProbe()
        probe.addFile("/data/files/model/model.mnn", 2000)
        val models = LocalModelInventory.scan("/data/files", probe)
        assertEquals(1, models.size)
        assertFalse(models[0].hasConfig)
        assertFalse(models[0].ready)
    }

    @Test
    fun `model dir missing weight is not ready`() {
        val probe = FakeProbe()
        probe.addFile("/data/files/model/config.json", 100)
        val models = LocalModelInventory.scan("/data/files", probe)
        assertEquals(1, models.size)
        assertTrue(models[0].hasConfig)
        assertFalse(models[0].hasWeightFile)
        assertFalse(models[0].ready)
    }

    @Test
    fun `modelscope repos are discovered and decoded`() {
        val probe = FakeProbe()
        probe.addFile("/data/files/model/config.json", 50)
        probe.addFile("/data/files/model/model.mnn", 100)
        probe.addFile("/data/files/modelscope/owner__repo/config.json", 60)
        probe.addFile("/data/files/modelscope/owner__repo/repo.mnn", 120)
        val models = LocalModelInventory.scan("/data/files", probe)
        assertEquals(2, models.size)
        assertTrue(models.any { it.id == "legacy:model" })
        assertTrue(models.any { it.id == "modelscope:owner/repo" })
    }

    @Test
    fun `decodeModelScopeRepoDir converts double underscore`() {
        assertEquals("owner/repo", LocalModelInventory.decodeModelScopeRepoDir("owner__repo"))
        assertEquals("noSep", LocalModelInventory.decodeModelScopeRepoDir("noSep"))
        assertEquals("", LocalModelInventory.decodeModelScopeRepoDir(""))
    }

    /** In-memory dir/file tree backed by a map of absolute path -> size. */
    private class FakeProbe : LocalModelInventory.ModelFolderProbe {
        private val files = LinkedHashMap<String, Long>()
        private val dirs = mutableSetOf<String>()

        fun addFile(path: String, size: Long) {
            files[File(path).absolutePath] = size
            var parent = File(path).parent
            while (parent != null && parent.isNotEmpty()) {
                dirs.add(parent)
                val next = File(parent).parent
                if (next == parent) break
                parent = next
            }
        }

        override fun listEntries(dir: File): List<String> {
            val prefix = dir.absolutePath.trimEnd(File.separatorChar) + File.separator
            val result = LinkedHashSet<String>()
            files.keys.forEach { p ->
                val suffix = p.removePrefix(prefix)
                if (suffix != p) {
                    val first = suffix.substringBefore(File.separatorChar)
                    if (first.isNotEmpty()) result.add(first)
                }
            }
            dirs.forEach { p ->
                val suffix = p.removePrefix(prefix)
                if (suffix != p && suffix.isNotEmpty()) {
                    val first = suffix.substringBefore(File.separatorChar)
                    if (first.isNotEmpty()) result.add(first)
                }
            }
            return result.sorted()
        }

        override fun isFile(f: File): Boolean = files.containsKey(f.absolutePath)

        override fun sizeOf(f: File): Long = files[f.absolutePath] ?: 0L
    }
}
