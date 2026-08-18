package com.clawmate.app.gallery.collection

import com.clawmate.app.datamanager.DailyLogLocalIndex
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.datamanager.LocalConsolidationTypes.QueryOptions
import java.io.File

/**
 * Read seam for the summary collection grouping. Production wiring uses the real
 * [DailyLogLocalIndex] (read-only, app-private, bounded). Tests inject a fake so
 * no Room / network / device is required.
 *
 * The reader is **pure and read-only** — it never creates artifacts, never
 * reaches the network and never triggers a model call.
 */
fun interface IndexedEventReader {
    fun read(sceneId: Int): List<IndexedEvent>
}

/** Real production reader over the persisted local index (bounded, tolerant). */
class LocalIndexEnvironmentIndexedEventReader(
    private val localIndex: DailyLogLocalIndex,
) : IndexedEventReader {
    override fun read(sceneId: Int): List<IndexedEvent> {
        // A bounded, honest query: all events are read once and grouped locally.
        // No keyword/time filter on the reader; grouping happens in the service.
        val result = localIndex.query(QueryOptions(limit = DailyLogLocalIndex.DEFAULT_QUERY_LIMIT))
        return result.events
    }
}

/** Build a production reader for throwaway re-construction from an app-private base dir. */
object IndexedEventReaderFactory {
    fun appPrivate(filesDir: File): IndexedEventReader =
        LocalIndexEnvironmentIndexedEventReader(DailyLogLocalIndex(filesDir))
}
