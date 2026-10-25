package com.clawmate.platform

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalImageAccessTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "clawmate-image-policy/files")
    @Test fun privateImageAndGrantedContentCanBeAttempted() {
        assertEquals(File(root, "照片.png").toURI().toString(), LocalImageAccess.validate(File(root, "照片.png").toURI().toString(), listOf(root)))
        assertEquals("content://media/picker/1", LocalImageAccess.validate("content://media/picker/1", listOf(root)))
    }
    @Test fun remoteAndSiblingDirectoriesAreRejected() {
        for (value in listOf("https://fixture.invalid/photo.png", "file:///etc/passwd", File(root.parentFile, "files-other/photo.png").toURI().toString(), File(root, "../database.db").toURI().toString())) {
            assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.validate(value, listOf(root)) }
        }
    }
    @Test fun encodedTraversalAndAuthorityAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.validate(root.toURI().toString().trimEnd('/')+"/%2e%2e/database.db", listOf(root)) }
        assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.validate("file://fixture.invalid/photo.png", listOf(root)) }
        assertThrows(IllegalArgumentException::class.java) { LocalImageAccess.validate("content://user:secret@provider/photo", listOf(root)) }
    }
}
