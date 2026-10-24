package com.clawmate.app.task.json

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowGraphDraftTest {
    @Test fun editingKnownFieldsPreservesUnknownFieldsAndNestedTypes() {
        val original = """{"extra":{"number":1,"flag":false},"metadata":{"name":"original","custom":[1,2]},"steps":[{"id":"a","type":"future_node","nested":{"keep":true}}]}"""
        val draft = WorkflowGraphDraft.parse(original)!!
        val edited = draft.field("metadata", "description", JsonPrimitive("新标题")).field("steps/0", "name", JsonPrimitive("节点名"))
        assertEquals(draft.root["extra"], edited.root["extra"])
        assertEquals(draft.at("steps/0/nested"), edited.at("steps/0/nested"))
        assertEquals(draft.at("metadata/custom"), edited.at("metadata/custom"))
        assertNull(WorkflowEditValidator.error(edited.text()))
    }

    @Test fun pairedCaptureAndSummaryMoveAndDeleteTogether() {
        val draft = WorkflowGraphDraft.parse(WorkflowGraphDraft.empty())!!.insert("shot_summary").insert("gui_task")
        val moved = draft.move("steps/0", 1)
        val list = moved.root["steps"] as JsonArray
        assertEquals("gui_task", list[0].jsonObject.string("type"))
        assertTrue(WorkflowGraphDraft.isPair(list, 1))
        assertEquals(1, (moved.remove("steps/2").root["steps"] as JsonArray).size)
        assertEquals(draft.root, moved.move("steps/1", -1).root)
    }

    @Test fun nestedInsertAndDeleteDoNotAlterSiblingBranch() {
        val draft = WorkflowGraphDraft.parse(WorkflowGraphDraft.empty())!!.insert("if")
            .insert("gui_task", branch = "steps/0/then_steps").insert("until_loop", branch = "steps/0/else_steps")
        val sibling = draft.at("steps/0/else_steps")
        val deleted = draft.remove("steps/0/then_steps/0")
        assertEquals(sibling, deleted.at("steps/0/else_steps"))
        assertTrue((deleted.at("steps/0/then_steps") as JsonArray).isEmpty())
        assertNull(WorkflowEditValidator.error(deleted.text()))
    }

    @Test fun templateIdsAvoidBothStringAndNumberIdsAndReferenceTheActualShot() {
        val draft = WorkflowGraphDraft.parse("""{"steps":[{"id":"1","type":"if","then_steps":[{"id":2,"type":"tool"}]}]}""")!!.insert("shot_summary")
        assertEquals("3", draft.at("steps/1/id")!!.jsonPrimitive.content)
        assertEquals("4", draft.at("steps/2/id")!!.jsonPrimitive.content)
        assertEquals("\${steps.3.output.image_b64}", draft.at("steps/2/inputs/image")!!.jsonPrimitive.content)
    }

    @Test fun invalidDocumentCannotBeSilentlyConvertedOrRepaired() {
        assertNull(WorkflowGraphDraft.parse("{broken}"))
        assertNull(WorkflowGraphDraft.parse("""{"steps":[{"type":"tool"}]}"""))
    }

    @Test fun actionDefaultsFollowTheSourceAndDoNotOverwriteExistingParameters() {
        val draft = WorkflowGraphDraft.parse(WorkflowGraphDraft.empty())!!.insert("shot_summary")
        assertEquals("up", draft.action("steps/0", "swipe").at("steps/0/direction")!!.jsonPrimitive.content)
        assertEquals("1", draft.action("steps/0", "sleep").at("steps/0/seconds")!!.jsonPrimitive.content)
        val custom = draft.field("steps/0", "seconds", JsonPrimitive(9)).action("steps/0", "sleep")
        assertEquals("9", custom.at("steps/0/seconds")!!.jsonPrimitive.content)
        assertEquals(draft.at("steps/1"), custom.at("steps/1"))
    }

    @Test fun unrelatedSummaryDoesNotGetGroupedOrDeletedWithScreenshot() {
        val draft = WorkflowGraphDraft.parse("""{"steps":[{"id":1,"type":"gui_action","action":"screenshot"},{"id":2,"type":"tool","tool_name":"vlm_qa","inputs":{"mode":"summary","image":"different"}}]}""")!!
        assertFalse(WorkflowGraphDraft.isPair(draft.root["steps"] as JsonArray, 0))
        assertEquals("2", draft.remove("steps/0").at("steps/0/id")!!.jsonPrimitive.content)
    }
}
