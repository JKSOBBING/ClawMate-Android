package com.clawmate.app.home.domain

/**
 * 首次安装引导页的稳定数据契约 — pure types/functions only (no UI), ported
 * from the HarmonyOS source `pages/onboarding/OnboardingTypes.ets`.
 *
 * This file deliberately depends on no Compose/ArkUI: the shell decides whether
 * to show onboarding, the onboarding screen only renders, and unit tests can
 * assert the versioned "never shown again" behaviour deterministically.
 */

/** Persistence key for the last-surfaced onboarding version. */
const val FIRST_LAUNCH_ONBOARDING_STORAGE_KEY: String = "firstLaunchOnboardingVersion"

/** Current onboarding version string (must equal the source value). */
const val FIRST_LAUNCH_ONBOARDING_VERSION: String = "2026-07-core-preview-usage-guide"

const val FIRST_LAUNCH_ONBOARDING_SLIDE_DATA_HOME: String = "data_home"
const val FIRST_LAUNCH_ONBOARDING_SLIDE_AVATAR: String = "avatar"
const val FIRST_LAUNCH_ONBOARDING_SLIDE_AGENT: String = "agent"
const val FIRST_LAUNCH_ONBOARDING_SLIDE_USAGE_GUIDE: String = "usage_guide"

enum class FirstLaunchOnboardingSlideKind { PREVIEW, USAGE_GUIDE }

data class FirstLaunchOnboardingSlide(
    val id: String,
    val kind: FirstLaunchOnboardingSlideKind,
    val title: String,
    val description: String,
    val mediaResourceName: String?,
    val accentColor: String,
    val softBackgroundColor: String,
)

data class FirstLaunchUsageGuideItem(
    val id: String,
    val title: String,
    val entryLabel: String,
    val configLabel: String,
    val description: String,
    val iconText: String,
    val accentColor: String,
    val softBackgroundColor: String,
)

/** 首次引导最后一页的功能入口清单：只描述使用与配置位置，不做任何副作用。 */
val FIRST_LAUNCH_USAGE_GUIDE_ITEMS = listOf(
    FirstLaunchUsageGuideItem(
        id = "usage_data_home",
        title = "ClawMate",
        entryLabel = "使用入口：汇总",
        configLabel = "配置入口：设置 > 数据采集",
        description = "同步聊天、图库和位置线索，在汇总页查看整理后的个人事件。",
        iconText = "D",
        accentColor = "#4F68FF",
        softBackgroundColor = "#EAF1FF",
    ),
    FirstLaunchUsageGuideItem(
        id = "usage_avatar",
        title = "数字分身",
        entryLabel = "使用入口：首页",
        configLabel = "配置入口：设置 > 个人与隐私",
        description = "基于个人画像和头像生成数字分身，用于问答、任务执行和长期陪伴。",
        iconText = "A",
        accentColor = "#8066FF",
        softBackgroundColor = "#F1EEFF",
    ),
    FirstLaunchUsageGuideItem(
        id = "usage_agent",
        title = "自动执行",
        entryLabel = "使用入口：聊天 / 任务",
        configLabel = "配置入口：设置 > 模型与智能体、任务与自动化",
        description = "配置 Planner、Decider 和 HDC 后，让 Agent 按目标规划并执行任务。",
        iconText = "T",
        accentColor = "#18A886",
        softBackgroundColor = "#E9F8F3",
    ),
)

/** 三条能力 preview 页 + 一条 usage-guide 页，顺序与文案与源码一致。 */
val FIRST_LAUNCH_ONBOARDING_SLIDES = listOf(
    FirstLaunchOnboardingSlide(
        id = FIRST_LAUNCH_ONBOARDING_SLIDE_DATA_HOME,
        kind = FirstLaunchOnboardingSlideKind.PREVIEW,
        title = "ClawMate",
        description = "汇总聊天、图库、任务和位置线索，让分散在各个 App 里的信息回到你的个人数据中心。",
        mediaResourceName = "onboarding_data_home",
        accentColor = "#4F68FF",
        softBackgroundColor = "#EAF1FF",
    ),
    FirstLaunchOnboardingSlide(
        id = FIRST_LAUNCH_ONBOARDING_SLIDE_AVATAR,
        kind = FirstLaunchOnboardingSlideKind.PREVIEW,
        title = "数字分身",
        description = "结合个人资料、头像和历史上下文，生成更懂你的助手，用于问答、陪伴和任务协作。",
        mediaResourceName = "onboarding_avatar",
        accentColor = "#8066FF",
        softBackgroundColor = "#F1EEFF",
    ),
    FirstLaunchOnboardingSlide(
        id = FIRST_LAUNCH_ONBOARDING_SLIDE_AGENT,
        kind = FirstLaunchOnboardingSlideKind.PREVIEW,
        title = "自动执行",
        description = "让 Agent 根据你的目标规划步骤、调用工具并执行任务，更准确，更懂你。",
        mediaResourceName = "onboarding_agent",
        accentColor = "#18A886",
        softBackgroundColor = "#E9F8F3",
    ),
    FirstLaunchOnboardingSlide(
        id = FIRST_LAUNCH_ONBOARDING_SLIDE_USAGE_GUIDE,
        kind = FirstLaunchOnboardingSlideKind.USAGE_GUIDE,
        title = "开始使用",
        description = "三步建立你的个人智能助手：汇总数据，生成数字分身，最后 Agent 执行任务。",
        mediaResourceName = null,
        accentColor = "#4F68FF",
        softBackgroundColor = "#EAF1FF",
    ),
)

/** Whether the stored version differs from the current one (i.e. show again). */
fun shouldShowFirstLaunchOnboarding(storedVersion: String): Boolean =
    storedVersion != FIRST_LAUNCH_ONBOARDING_VERSION

/** True when [index] is the last slide (the finish/进入首页 page). */
fun isFirstLaunchOnboardingLastPage(index: Int): Boolean =
    clampFirstLaunchOnboardingIndex(index) == FIRST_LAUNCH_ONBOARDING_SLIDES.size - 1

/** Primary button label: 下一页 until the last page, then 进入首页. */
fun firstLaunchOnboardingButtonText(index: Int): String =
    if (isFirstLaunchOnboardingLastPage(index)) "进入首页" else "下一页"

/** Clamp an index into the valid [0, slides-1] range (floors non-integers). */
fun clampFirstLaunchOnboardingIndex(index: Int): Int {
    if (index < 0) return 0
    val maxIndex = FIRST_LAUNCH_ONBOARDING_SLIDES.size - 1
    return if (index > maxIndex) maxIndex else index
}

/**
 * Skip button visible only before the last page: jumping straight to the last
 * (usage-guide) page still requires finishing to mark the onboarding complete.
 */
fun firstLaunchOnboardingSkipTargetIndex(index: Int): Int =
    if (isFirstLaunchOnboardingLastPage(index)) index else FIRST_LAUNCH_ONBOARDING_SLIDES.size - 1
