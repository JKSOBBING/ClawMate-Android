package com.clawmate.app.settings.domain

/**
 * The five Settings sections, mirrored from HarmonyOS
 * `entry/src/main/ets/pages/settings/SettingsCenterTypes.ets`
 * (`SettingsSectionId`, `SETTINGS_SECTION_ITEMS`,
 * `normalizeSettingsSectionId`, `isSettingsSectionSelected`).
 *
 * Section metadata (title/subtitle) is intentionally filled by [sections]:
 * Pure section identifiers, with presentation text kept in the UI/template
 * layer so domain code stays locale-agnostic.
 */
enum class SettingsSectionId(val ordinalIndex: Int) {
    PersonalPrivacy(0),
    DataCollection(1),
    ModelAgents(2),
    TaskAutomation(3),
    StorageAbout(4),
    ;

    companion object {
        /** None-selected sentinel, matching `SETTINGS_SECTION_NONE = -1`. */
        const val NONE: Int = -1

        /** Normalize an arbitrary id to a known section or [NONE]. */
        fun normalize(id: Int): Int {
            entries.forEach { if (it.ordinalIndex == id) return it.ordinalIndex }
            return NONE
        }

        /** Whether a raw id selects a real section. */
        fun isSelected(id: Int): Boolean = normalize(id) != NONE
    }
}

/** Immutable section descriptor with title/subtitle (matches section items). */
data class SettingsSectionItem(
    val id: SettingsSectionId,
    val title: String,
    val subtitle: String,
) {
    /** The single-letter badge initial shown in the section list. */
    val badge: String
        get() = when (id) {
            SettingsSectionId.PersonalPrivacy -> "P"
            SettingsSectionId.DataCollection -> "D"
            SettingsSectionId.ModelAgents -> "M"
            SettingsSectionId.TaskAutomation -> "T"
            SettingsSectionId.StorageAbout -> "S"
        }
}

/** The five section descriptors, in display order. */
val SETTINGS_SECTIONS: List<SettingsSectionItem> = listOf(
    SettingsSectionItem(SettingsSectionId.PersonalPrivacy, "个人与隐私", "隐私守护、确认策略与数字分身"),
    SettingsSectionItem(SettingsSectionId.DataCollection, "数据采集", "图库、OCR、Embedding 与地图服务"),
    SettingsSectionItem(SettingsSectionId.ModelAgents, "模型与智能体", "云端 Planner/Decider、本地模型与 HDC"),
    SettingsSectionItem(SettingsSectionId.TaskAutomation, "任务与自动化", "Agent 执行、Workflow 与运行日志"),
    SettingsSectionItem(SettingsSectionId.StorageAbout, "存储与关于", "模型文件、调试产物、版本与说明"),
)

fun settingsSectionTitle(id: SettingsSectionId): String =
    SETTINGS_SECTIONS.firstOrNull { it.id == id }?.title.orEmpty()

fun settingsSectionSubtitle(id: SettingsSectionId): String =
    SETTINGS_SECTIONS.firstOrNull { it.id == id }?.subtitle.orEmpty()
