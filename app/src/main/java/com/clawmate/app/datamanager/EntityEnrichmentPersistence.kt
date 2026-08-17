package com.clawmate.app.datamanager

import java.io.File

/**
 * Phase-36 **explicit entity-enrichment persistence** — the *caller-side* write
 * that persists a model-enriched entity document **only when an explicit caller
 * decides to**. This is deliberately separated from the Phase-35
 * [EntityEnrichmentRunner], which never writes and only returns the enriched
 * document to the caller. The UI / host invokes [writeEnriched] after it has
 * itself confirmed an explicit `MODEL` success (see the Settings action).
 *
 * Safety:
 *  - writing is only ever triggered by an explicit caller, never by the runner
 *    itself, never on launch / Home load / recomposition;
 *  - the artifact written is always the strictly-validated enriched document
 *    returned by the runner (bounded, real-id whitelisted, no secret); a
 *    `FALLBACK` / `LOCAL_ONLY` / `EMPTY` result never reaches this seam;
 *  - writes are atomic (temp + rename via [AtomicJsonStore]).
 */
class EntityEnrichmentPersistence(
    private val baseDir: File,
    private val store: AtomicJsonStore = AtomicJsonStore(),
) {

    /** Whether a result is a persisted-eligible MODEL outcome. */
    fun canPersist(result: EntityEnrichmentTypes.EnrichmentResult): Boolean =
        result.status == EntityEnrichmentTypes.EnrichmentStatus.MODEL &&
            result.enrichedDocument != null

    /**
     * Atomically persist [result]'s enriched document. Returns true on a
     * successful write. Throws nothing to the caller — write failures return
     * false (the caller surfaces an honest error and keeps the old artifact).
     */
    fun writeEnriched(result: EntityEnrichmentTypes.EnrichmentResult): Boolean {
        val doc = result.enrichedDocument ?: return false
        return runCatching {
            store.writeJson(DataManagerPaths.entityIndex(baseDir), doc) { EntitySceneCodec.encodeEntity(it) }
            true
        }.getOrDefault(false)
    }
}
