package com.clawmate.app.task.model

/**
 * Workflow scene/tab configuration, ported from HarmonyOS
 * `utils/WorkflowSceneConfig.ets` (reference only). The 7 collection scenes
 * shown on the Task page are mirrored verbatim; the memory/gallery scenes are
 * kept out of this Task-page slice (they belong to the data manager, a later
 * phase), matching the source comment that the Task page only shows the 7
 * collection scenes.
 *
 * This model is pure (no Android/Compose types) so it stays deterministically
 * unit-testable; the UI parses [colorHex] as needed.
 */
data class WorkflowScene(
    val sceneId: Int,
    val tabId: String,
    val label: String,
    val colorHex: String,
)

object WorkflowSceneConfig {

    /** The Task page's 7 collection scenes (source: WORKFLOW_SCENES). */
    val scenes: List<WorkflowScene> = listOf(
        WorkflowScene(0, "shopping", "购物", "#765BFF"),
        WorkflowScene(1, "takeout", "外卖", "#39BFA3"),
        WorkflowScene(2, "entertainment", "娱乐", "#8066FF"),
        WorkflowScene(3, "chat", "聊天", "#4B8BFF"),
        WorkflowScene(4, "life", "生活", "#D97706"),
        WorkflowScene(5, "social", "社交", "#D9467D"),
        WorkflowScene(6, "travel", "差旅", "#0EA5E9"),
    )

    fun sceneById(sceneId: Int): WorkflowScene =
        scenes.firstOrNull { it.sceneId == sceneId } ?: scenes.first()

    fun sceneByIndex(index: Int): WorkflowScene =
        if (index in scenes.indices) scenes[index] else scenes.first()

    fun label(sceneId: Int): String = sceneById(sceneId).label
}
