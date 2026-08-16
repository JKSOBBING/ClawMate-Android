package com.clawmate.app.log.domain

/**
 * Phase 14 — log query/display filter (pure, deterministic, unit-testable).
 *
 * The Compose screen derives this from user controls (source tab, minimum
 * level, free-text query) and passes it to [filterLogs]. Keeping filtering a
 * pure function mirrors the Harmony LogView source selection while staying
 * trivially testable offline.
 */
data class LogFilter(
    val source: LogSource,
    val minLevel: LogLevel = LogLevel.DEBUG,
    val query: String = "",
    val limit: Int = 1000,
    /** Viewer chrome: poll the buffer on a ticker when true (unused offline). */
    val autoRefresh: Boolean = false,
    /** Viewer chrome: jump to newest entries when true. */
    val autoScroll: Boolean = true,
)

/** Pure filter over an immutable log list, newest-first results preserved. */
fun filterLogs(entries: List<InAppLogEntry>, filter: LogFilter): List<InAppLogEntry> {
    val needle = filter.query.trim()
    return entries
        .asSequence()
        .filter { it.source == filter.source }
        .filter { it.level.ordinal >= filter.minLevel.ordinal }
        .filter { entry ->
            if (needle.isEmpty()) true
            else {
                entry.message.contains(needle, ignoreCase = true) ||
                    entry.tag.contains(needle, ignoreCase = true)
            }
        }
        .take(filter.limit)
        .toList()
}
