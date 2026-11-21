package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Test

class MergedLabelTest {
    private val panel = listOf(0, 169, 999, 399)
    private val caption = VisibleTextLine("All events", listOf(790, 350, 940, 383))
    private val merged = "City events and activities for today and this weekend: All events"
    private fun element(name: String, box: List<Int>) =
        UiElement(name, "View", name, 0, 0, box, null, true, false, false)

    private val leaf = UiTreeNode("View", panel, description = merged)
    private val window = UiWindowTree(1, "application", 0, null, "sample", true, true, listOf(leaf))
    private val elements = (1..6).map { element("Tab $it", listOf(0, 0, 99, 99)) } + element(merged, panel)
    private val screen = ScreenSnapshot("s", "sample", elements, 1080, 2000, windows = listOf(window))

    @Test fun mergedLeafRequestsOcrDespiteNamedSurroundingControls() {
        assertEquals(TreeQuality.Partial, TreeAdequacy.ofTree(screen).quality)
        assertEquals(TreeQuality.Protected, TreeAdequacy.ofTree(screen.copy(protected = true)).quality)
        val labeledChild = UiTreeNode("Text", caption.bounds, text = caption.text)
        val nested = window.copy(nodes = listOf(leaf.copy(children = listOf(labeledChild))))
        assertEquals(TreeQuality.Adequate, TreeAdequacy.ofTree(screen.copy(windows = listOf(nested))).quality)
    }

    @Test fun flatObservationExposesWhichTabIsSelected() {
        val active = element("Local", panel).copy(states = setOf(UiStates.SELECTED))
        val inactive = element("All", panel)
        val (json, _) = ScreenJson.elements(screen.copy(elements = listOf(active, inactive)), 2)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), (json[0] as kotlinx.serialization.json.JsonObject)["selected"])
        assertEquals(null, (json[1] as kotlinx.serialization.json.JsonObject)["selected"])
    }

    @Test fun locatesOneExactCaptionWithTrailingChevron() {
        val line = caption.copy(text = "All events ›")
        val other = line.copy(bounds = listOf(50, 200, 200, 233))
        assertEquals(line, VisibleText.exactCaption(screen, listOf(line), "All events"))
        assertEquals(null, VisibleText.exactCaption(screen, listOf(line, other), "All events"))
        assertEquals(null, VisibleText.exactCaption(screen, listOf(line), "events"))
        assertEquals(null, VisibleText.exactCaption(screen.copy(protected = true), listOf(line), "All events"))
    }

    @Test fun keepsCaptionPositionUnlessIndividuallyAddressableOrSecret() {
        assertEquals(listOf(caption), VisibleText.missing(screen, listOf(caption)))
        val button = element(caption.text, caption.bounds)
        assertEquals(emptyList<VisibleTextLine>(), VisibleText.missing(screen.copy(elements = elements + button), listOf(caption)))
        assertEquals(emptyList<VisibleTextLine>(), VisibleText.missing(
            screen.copy(elements = elements + button.copy(secret = true, name = "")), listOf(caption)))
    }
}
