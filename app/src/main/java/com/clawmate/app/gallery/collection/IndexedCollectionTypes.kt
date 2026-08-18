package com.clawmate.app.gallery.collection

import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent

/**
 * Phase-44 collection models for the **index-back** summary grouping.
 *
 * These are a pure, model-free projection of the real indexed daily-log events
 * (read from [com.clawmate.app.datamanager.DailyLogLocalIndex]). Unlike the
 * photo-only [CollectionGroup] (which groups imported gallery photos), an
 * [IndexedCollectionGroup] always references real persisted event ids and is
 * explicitly marked `local` — Harmony's model-generated grouping is *not*
 * emulated here, so the UI always labels it as a local deterministic grouping.
 *
 * Every field is derived from real event text / ids / dates; never from a raw
 * path, URI, token or unauthorized media payload. No fake "model-graded" claim
 * is ever produced.
 */
data class IndexedCollectionGroup(
    /** Stable, deterministic group id (content hash over its sorted event ids). */
    val groupId: String,
    /** Deterministic local title derived from the real events in the group. */
    val title: String,
    /** Dominant source among the group's real events ("DailyLog" etc.). */
    val source: String,
    /** Newest real event date in the group (ISO yyyy-MM-dd). */
    val time: String,
    /** Human count label, e.g. "3 条". */
    val meta: String,
    /** Honest status word ("已整理"). */
    val status: String,
    /** Dominant domain id among the group's real events (may be "other"). */
    val domain: String,
    /** Sorted (stable) real event ids that belong to this group. */
    val eventIds: List<String>,
    /** Always true for the deterministic local projection. */
    val local: Boolean = true,
)

/** A single real indexed event, surfaced in the group detail view. */
data class IndexedCollectionEvent(
    /** Stable real event id. */
    val eventId: String,
    /** Real event date (ISO yyyy-MM-dd); may be empty for untrustworthy rows. */
    val date: String,
    /** Real summary text. */
    val summary: String,
    /** Real normalized fact. */
    val normalizedFact: String,
    /** Dominant/readable source display name. */
    val source: String,
)

/** One selectable collection tab (a subset of the workflow-scene set). */
data class IndexedCollectionTab(
    val sceneId: Int,
    val tabId: String,
    val label: String,
)

/** The deterministic, honest result of reading + grouping indexed events. */
data class IndexedCollectionLoadResult(
    val totalEvents: Int,
    val groups: List<IndexedCollectionGroup>,
    /** Total across all scenes read by the reader (pre-filter). */
    val indexedEventCount: Int,
    val generation: String = "local",
)
