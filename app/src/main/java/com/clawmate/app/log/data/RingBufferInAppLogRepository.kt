package com.clawmate.app.log.data

import androidx.annotation.VisibleForTesting
import com.clawmate.app.log.domain.InAppLogEntry
import com.clawmate.app.log.domain.LogLevel
import com.clawmate.app.log.domain.LogSource
import com.clawmate.app.log.domain.SensitiveLogRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 14 — in-app log ring buffer repository (bounded retention).
 *
 * Ports the Harmony `LogView.ets` ring-buffer idea and
 * `CloudLogOutput.ets` bounded, append-only eviction to a real Android
 * implementation. Records are appended by code paths that actually run; nothing
 * is fabricated. The buffer is capacity-bounded (oldest entries are evicted in
 * FIFO order), requires minimal level to be kept, and redacts sensitive values
 * on insert so a raw secret can never be stored or rendered.
 *
 * Thread-safety: all mutations are synchronized; reads of [snapshot] take the
 * same lock, plus a [StateFlow] mirrors the list for Compose observation.
 */
interface InAppLogRepository {
    /** Truncated, newest-first snapshot of the ring buffer (copy). */
    fun snapshot(): List<InAppLogEntry>

    /** Newest-first ordered flow driving the Compose observer. */
    val entries: StateFlow<List<InAppLogEntry>>

    /** Append one record, redacting it and enforcing retention limits. */
    fun append(entry: InAppLogEntry)

    /** Convenience builder for the common case (started at insertion time). */
    fun append(
        level: LogLevel,
        source: LogSource,
        tag: String,
        message: String,
    ) = append(
        InAppLogEntry(
            sequence = 0L, // assigned by the repository
            timestampMillis = System.currentTimeMillis(),
            level = level,
            source = source,
            tag = tag,
            message = message,
        ),
    )

    /** Remove all records (optionally scoped to one source). */
    fun clear(source: LogSource? = null)

    /** Total capacity (max kept records). */
    val capacity: Int
}

/**
 * Bounded ring-buffer implementation backed by [kotlinx.coroutines.flow.StateFlow].
 *
 * @param maxEntries maximum records retained (retention bound).
 * @param minKeepLevel entries below this severity are not retained at all.
 * @param maxMessageLength messages longer than this are truncated to a safe
 *   scan length before redaction.
 */
class RingBufferInAppLogRepository(
    override val capacity: Int = DEFAULT_CAPACITY,
    private val minKeepLevel: LogLevel = LogLevel.DEBUG,
    private val maxMessageLength: Int = DEFAULT_MAX_MESSAGE_LENGTH,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sequence: AtomicLong = AtomicLong(0L),
) : InAppLogRepository {

    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(capacity <= MAX_CAPACITY) { "capacity must be <= $MAX_CAPACITY" }
        require(maxMessageLength > 0) { "maxMessageLength must be > 0" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<InAppLogEntry>()
    private val _entries = MutableStateFlow<List<InAppLogEntry>>(emptyList())
    override val entries: StateFlow<List<InAppLogEntry>> = _entries.asStateFlow()

    override fun snapshot(): List<InAppLogEntry> = synchronized(lock) { newestFirst() }

    override fun append(entry: InAppLogEntry) {
        // Enforce minimal-severity retention gate up front.
        if (entry.level.ordinal < minKeepLevel.ordinal) return
        val message = sanitize(entry.message)
        val record = InAppLogEntry(
            sequence = if (entry.sequence == 0L) sequence.incrementAndGet() else entry.sequence,
            timestampMillis = if (entry.timestampMillis == 0L) clock() else entry.timestampMillis,
            level = entry.level,
            source = entry.source,
            tag = entry.tag,
            message = message,
        )
        synchronized(lock) {
            if (buffer.size >= capacity) {
                // FIFO eviction of the oldest (the buffer holds newest-last).
                buffer.removeFirst()
            }
            buffer.addLast(record)
            _entries.value = newestFirst()
        }
    }

    override fun clear(source: LogSource?) {
        synchronized(lock) {
            if (source == null) {
                buffer.clear()
            } else {
                val kept = buffer.filterTo(ArrayDeque()) { it.source != source }
                buffer.clear()
                buffer.addAll(kept)
            }
            _entries.value = newestFirst()
        }
    }

    private fun sanitize(raw: String): String {
        val bounded = if (raw.length > maxMessageLength) raw.take(maxMessageLength) + "…" else raw
        return SensitiveLogRedactor.redact(bounded)
    }

    /** Newest first for display and for Compose iteration. */
    private fun newestFirst(): List<InAppLogEntry> = buffer.reversed()

    companion object {
        const val DEFAULT_CAPACITY: Int = 512
        const val MAX_CAPACITY: Int = 4096
        const val DEFAULT_MAX_MESSAGE_LENGTH: Int = 4000

        @VisibleForTesting
        fun createForTest(capacity: Int = DEFAULT_CAPACITY, sequence: Long = 0L): RingBufferInAppLogRepository =
            RingBufferInAppLogRepository(
                capacity = capacity,
                minKeepLevel = LogLevel.DEBUG,
                maxMessageLength = DEFAULT_MAX_MESSAGE_LENGTH,
                clock = { DEFAULT_TIMESTAMP_BASE + sequence },
                sequence = AtomicLong(sequence),
            )

        /** Fixed synthetic clock base so tests are deterministic. */
        @VisibleForTesting
        const val DEFAULT_TIMESTAMP_BASE: Long = 1_700_000_000_000L
    }
}
