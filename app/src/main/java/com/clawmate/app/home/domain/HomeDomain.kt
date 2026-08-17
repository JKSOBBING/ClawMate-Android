package com.clawmate.app.home.domain

/**
 * 首页画像 pillar（数字分身价值点的四个能力入口卡）。
 *
 * Ported from the HarmonyOS source `datamanager/DataManagerTypes.ets`
 * (`HomeDomainViewModel`) and `DataManagerFacade.ets`
 * (`defaultHomeDomains` / `homeProfileDomains`). Defaults are the honest
 * "暂无数据" empty profile — no invented profile claims.
 *
 * Only the four source pillars are surfaced on the home grid: eat(吃) / wear(穿)
 * / live(住) / travel(行).
 */
data class HomeDomainViewModel(
    val domain: String,
    val icon: String,
    val title: String,
    val desc: String,
    val fullDesc: String,
    val claims: List<String>,
    val color: String,
    val eventCount: Int,
    /**
     * Phase 32: real indexed evidence ids backing this pillar's claims (display
     * only, read-only; never persisted or written back to user data).
     */
    val evidenceIds: List<String> = emptyList(),
)

/** Empty-profile sentinel text used by the source for an un-populated domain. */
const val HOME_DOMAIN_NO_DATA: String = "暂无数据"

/**
 * Ordered, four-pillar empty profile. Colors and titles match the source
 * `DataManagerFacade.defaultHomeDomains()`.
 */
fun defaultHomeDomains(): List<HomeDomainViewModel> = listOf(
    HomeDomainViewModel(
        domain = "eat",
        icon = "Eat",
        title = "吃",
        desc = HOME_DOMAIN_NO_DATA,
        fullDesc = HOME_DOMAIN_NO_DATA,
        claims = emptyList(),
        color = "#39C2A2",
        eventCount = 0,
    ),
    HomeDomainViewModel(
        domain = "wear",
        icon = "Wear",
        title = "穿",
        desc = HOME_DOMAIN_NO_DATA,
        fullDesc = HOME_DOMAIN_NO_DATA,
        claims = emptyList(),
        color = "#7669FF",
        eventCount = 0,
    ),
    HomeDomainViewModel(
        domain = "live",
        icon = "Live",
        title = "住",
        desc = HOME_DOMAIN_NO_DATA,
        fullDesc = HOME_DOMAIN_NO_DATA,
        claims = emptyList(),
        color = "#FF963D",
        eventCount = 0,
    ),
    HomeDomainViewModel(
        domain = "travel",
        icon = "Go",
        title = "行",
        desc = HOME_DOMAIN_NO_DATA,
        fullDesc = HOME_DOMAIN_NO_DATA,
        claims = emptyList(),
        color = "#4B9BFF",
        eventCount = 0,
    ),
)

/**
 * 画像完整度（0..100）。Port of `homeProfileCompleteness` in
 * `DataManagerFacade.ets`: a domain counts toward completeness only when its
 * short description is populated (not `暂无数据`).
 */
fun homeProfileCompleteness(domains: List<HomeDomainViewModel>): Int {
    val total = 4
    val counted = domains
        .take(total)
        .count { it.desc.trim() != HOME_DOMAIN_NO_DATA }
    return (counted * 100 + total / 2) / total
}
