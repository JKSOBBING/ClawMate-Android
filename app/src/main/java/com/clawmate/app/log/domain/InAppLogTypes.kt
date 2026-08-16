package com.clawmate.app.log.domain

/**
 * Phase 14 — portable in-app log viewer data model.
 *
 * Ported from the HarmonyOS `entry/src/main/ets/pages/LogView.ets` (which
 * switches between a native C++ runtime ring buffer, cloud Agent logs and
 * gallery logs) and `entry/src/main/ets/utils/CloudLogOutput.ets` (bounded,
 * append-only + eviction). On Android we implement one honest, bounded in-app
 * ring buffer instead of pretending to read Harmony `native.getLogs()`: real
 * app events are appended by code paths that actually run, and no record is
 * fabricated.
 */

/** Log severity, ordered so `ordinal` can drive level filtering. */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * Log origins exposed by the viewer, mirroring the Harmony tabs:
 * Native (→ [LogSource.Application]), Cloud Agent (→ [LogSource.CloudAgent]),
 * Gallery (→ [LogSource.Gallery]) plus the Android Workflow core. The Harmony
 * native-native runtime tab has no Android equivalent (we never read logcat or
 * a C++ ring buffer we do not own), so real application events are surfaced
 * under [LogSource.Application] instead.
 */
enum class LogSource(val displayName: String) {
    APPLICATION("应用"),
    CLOUD_AGENT("云端 Agent"),
    GALLERY("图库分析"),
    WORKFLOW("任务/自动化"),
    SYSTEM("系统"),
}

/** A single immutable, redacted log record. */
data class InAppLogEntry(
    val sequence: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val source: LogSource,
    val tag: String,
    val message: String,
) {
    /** A stable identity key for Compose LazyColumn keys. */
    val key: String = "$sequence-$timestampMillis-${source.name}-$tag"
}
