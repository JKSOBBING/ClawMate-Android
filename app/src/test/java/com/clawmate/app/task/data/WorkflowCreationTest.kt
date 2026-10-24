package com.clawmate.app.task.data

import com.clawmate.app.task.json.WorkflowGraphDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorkflowCreationTest {
    @Test fun newWorkflowIsDisabledAndSurvivesRepositoryReopenWithExactJson() = runBlocking {
        val base = Files.createTempDirectory("workflow-new").toFile()
        val reader = BundledConfigReader { null }
        val repository = DefaultWorkflowRepository(base, reader)
        val raw = WorkflowGraphDraft.parse(WorkflowGraphDraft.empty())!!.insert("gui_task").text()
        val created = repository.createConfigJson(2, raw)
        assertFalse(created.enabled)
        assertTrue(created.isUserModified)
        assertEquals("未运行", created.lastRunStatus)
        val reopened = DefaultWorkflowRepository(base, reader)
        assertEquals(created, reopened.tasks().single())
        assertEquals(raw, reopened.configJson(created))
        reopened.saveConfigJson(created.copy(sceneId = 4), raw)
        assertEquals(4, reopened.tasks().single().sceneId)
    }

    @Test fun invalidConfigAndUnknownSceneCannotCreateTask() = runBlocking {
        val base = Files.createTempDirectory("workflow-invalid").toFile()
        val repository = DefaultWorkflowRepository(base, BundledConfigReader { null })
        assertTrue(runCatching { repository.createConfigJson(0, "{broken}") }.isFailure)
        assertTrue(runCatching { repository.createConfigJson(999, WorkflowGraphDraft.empty()) }.isFailure)
        assertTrue(repository.tasks().isEmpty())
    }
}
