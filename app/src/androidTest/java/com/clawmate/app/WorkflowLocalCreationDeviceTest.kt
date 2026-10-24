package com.clawmate.app

import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.task.data.BundledConfigReader
import com.clawmate.app.task.data.DefaultWorkflowRepository
import com.clawmate.app.task.json.WorkflowGraphDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercise the phone filesystem/atomic-write implementation without touching user workflows. */
class WorkflowLocalCreationDeviceTest {
    @Test fun creatingAndReopeningAWorkflowOnDeviceKeepsItsJsonAndDisabledState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "workflow-ui-test-").toFile()
        val reader = BundledConfigReader { null }
        val repository = DefaultWorkflowRepository(base, reader)
        val raw = WorkflowGraphDraft.parse(WorkflowGraphDraft.empty())!!.insert("shot_summary").text()
        val created = repository.createConfigJson(4, raw)
        val reopened = DefaultWorkflowRepository(base, reader)
        assertEquals(created, reopened.tasks().single())
        assertEquals(raw, reopened.configJson(created))
        assertFalse(created.enabled)
        assertEquals("未运行", created.lastRunStatus)
        assertTrue(base.canonicalPath.startsWith(context.cacheDir.canonicalPath + java.io.File.separator))
    }
}
