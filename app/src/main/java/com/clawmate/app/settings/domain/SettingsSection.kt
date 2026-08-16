package com.clawmate.app.settings.domain

/**
 * The Settings sections, mirrored from HarmonyOS
 * `entry/src/main/ets/pages/settings/SettingsCenterTypes.ets`
 * (`SettingsSectionId`, `SETTINGS_SECTION_ITEMS`,
 * `normalizeSettingsSectionId`, `isSettingsSectionSelected`). A sixth
 * section, [SettingsSectionId.WechatAutoSummary], is Android-specific and
 * exposes the ported WeChat auto-summary settings/validation boundary.
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
    WechatAutoSummary(5),
    RuntimeLogs(6),
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
            SettingsSectionId.WechatAutoSummary -> "W"
            SettingsSectionId.RuntimeLogs -> "L"
        }
}

/** The section descriptors, in display order (Phase 13 adds WeChat summary). */
val SETTINGS_SECTIONS: List<SettingsSectionItem> = listOf(
    SettingsSectionItem(SettingsSectionId.PersonalPrivacy, "个人与隐私", "隐私守护、确认策略与数字分身"),
    SettingsSectionItem(SettingsSectionId.DataCollection, "数据采集", "图库、OCR、Embedding 与地图服务"),
    SettingsSectionItem(SettingsSectionId.ModelAgents, "模型与智能体", "云端 Planner/Decider、本地模型与 HDC"),
    SettingsSectionItem(SettingsSectionId.TaskAutomation, "任务与自动化", "Agent 执行、Workflow 与运行日志"),
    SettingsSectionItem(SettingsSectionId.StorageAbout, "存储与关于", "模型文件、调试产物、版本与说明"),
    SettingsSectionItem(SettingsSectionId.WechatAutoSummary, "微信自动汇总", "微信摘要参数、验证与 PC/HDC 采集边界"),
    SettingsSectionItem(SettingsSectionId.RuntimeLogs, "运行日志", "应用内日志查看：过滤、清空、复制与敏感遮挡"),
)

fun settingsSectionTitle(id: SettingsSectionId): String =
    SETTINGS_SECTIONS.firstOrNull { it.id == id }?.title.orEmpty()

fun settingsSectionSubtitle(id: SettingsSectionId): String =
    SETTINGS_SECTIONS.firstOrNull { it.id == id }?.subtitle.orEmpty()
