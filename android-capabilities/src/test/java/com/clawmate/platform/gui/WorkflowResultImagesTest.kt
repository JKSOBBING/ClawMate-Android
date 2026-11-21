package com.clawmate.platform.gui

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkflowResultImagesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun publishesRecentDistinctFramesAndKeepsPreviousStepsImmutable() {
        val images = WorkflowResultImages(folder.root, "android-123-456")
        images.begin(2)
        listOf(1, 2, 2, 3).forEach { images.observe(byteArrayOf(it.toByte())) }
        val paths = images.publish()
        assertEquals(listOf(2, 3), paths.map { File(folder.root, it).readBytes().single().toInt() })
        images.begin(1)
        images.observe(byteArrayOf(4))
        assertNotEquals(paths.last(), images.publish().single())
        assertArrayEquals(byteArrayOf(3), File(folder.root, paths.last()).readBytes())
    }

    @Test fun missingFinalOrProtectedFrameCannotReturnAnOldScreen() {
        val images = WorkflowResultImages(folder.root, "android-123-456")
        images.begin(2)
        images.observe(byteArrayOf(1))
        images.observe(null)
        assertTrue(images.publish().isEmpty())
        assertTrue(folder.root.list().orEmpty().isEmpty())
    }

    @Test fun standaloneLoopShotsDoNotOverwriteAndCannotEscapeTheirDirectory() {
        val images = WorkflowResultImages(folder.root, "android-1-2")
        fun save(value: Byte): String {
            images.begin(1)
            images.observe(byteArrayOf(value))
            return images.publish("chat.jpg").single()
        }
        val first = save(1)
        val second = save(2)
        assertNotEquals(first, second)
        assertArrayEquals(byteArrayOf(1), File(folder.root, first).readBytes())
        assertArrayEquals(byteArrayOf(2), File(folder.root, second).readBytes())
        images.begin(1)
        images.observe(byteArrayOf(3))
        assertThrows(IllegalArgumentException::class.java) { images.publish("../chat.jpg") }
    }

    @Test fun eachWorkflowReservesItsOwnDirectory() {
        fun save() = WorkflowResultImages(folder.root, "android-123-456").run {
            begin(1); observe(byteArrayOf(1)); publish()
        }
        save()
        assertThrows(IOException::class.java) { save() }
        assertThrows(IllegalArgumentException::class.java) { WorkflowResultImages(folder.root, "../elsewhere") }
    }
}
