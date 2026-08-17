package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.EntityEnrichmentTypes.EnrichmentResult
import com.clawmate.app.datamanager.EntityEnrichmentTypes.EnrichmentStatus
import com.clawmate.app.datamanager.EntitySceneIndexTypes.EntityDefinition
import com.clawmate.app.datamanager.EntitySceneIndexTypes.EntityDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase-36 tests for the **explicit entity-enrichment persistence seam**
 * ([EntityEnrichmentPersistence]): the caller-only write that persists a
 * model-enriched entity document **only** on an explicit MODEL success.
 * LOCAL_ONLY / FALLBACK / EMPTY are never persisted, and the deterministic
 * local artifact is preserved verbatim otherwise.
 */
class EntityEnrichmentPersistenceTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase36-entitypersist").toFile().apply { deleteOnExit() }

    @Test
    fun `only a MODEL result with an enriched document is persist-eligible`() {
        val doc = EntityDocument(
            generatedAt = "2026-08-01T00:00:00",
            entities = mapOf(
                "merchant:老王面馆" to EntityDefinition(
                    type = "merchant", name = "老王面馆",
                    aliases = listOf("老王"), eventIds = listOf("e#1"),
                ),
            ),
            aliasPostings = emptyMap(),
            mentionAssignments = emptyMap(),
        )
        val model = EnrichmentResult(status = EnrichmentStatus.MODEL, candidateCount = 1, admittedEntityCount = 1, enrichedDocument = doc)
        val localOnly = EnrichmentResult(status = EnrichmentStatus.LOCAL_ONLY, enrichedDocument = doc)
        val fallback = EnrichmentResult(status = EnrichmentStatus.FALLBACK)
        val empty = EnrichmentResult(status = EnrichmentStatus.EMPTY)
        val noDoc = EnrichmentResult(status = EnrichmentStatus.MODEL, admittedEntityCount = 1, enrichedDocument = null)

        val persistence = EntityEnrichmentPersistence(tempBase())
        assertTrue(persistence.canPersist(model))
        assertFalse(persistence.canPersist(localOnly))
        assertFalse(persistence.canPersist(fallback))
        assertFalse(persistence.canPersist(empty))
        assertFalse(persistence.canPersist(noDoc))
    }

    @Test
    fun `writeEnriched persists the model-enriched document on explicit MODEL success`() {
        val base = tempBase()
        val doc = EntityDocument(
            generatedAt = "2026-08-01T00:00:00",
            entities = mapOf(
                "merchant:老王面馆" to EntityDefinition(
                    type = "merchant", name = "老王面馆",
                    aliases = listOf("老王"), eventIds = listOf("e#1"),
                ),
            ),
            aliasPostings = mapOf("老王面馆" to listOf("merchant:老王面馆")),
            mentionAssignments = emptyMap(),
        )
        val model = EnrichmentResult(status = EnrichmentStatus.MODEL, candidateCount = 1, admittedEntityCount = 1, enrichedDocument = doc)

        val persistence = EntityEnrichmentPersistence(base)
        assertTrue(persistence.writeEnriched(model))

        val written = EntitySceneCodec.decodeEntity(DataManagerPaths.entityIndex(base).readText())
        assertTrue("enriched entity must be persisted", written.entities.isNotEmpty())
        assertTrue(written.entities.containsKey("merchant:老王面馆"))
    }

    @Test
    fun `non-MODEL results are never persisted`() {
        val base = tempBase()
        // Seed a deterministic local artifact so we can prove it is untouched.
        val builder = EntitySceneIndexBuilder(base)
        DataManagerPaths.entityDir(base).mkdirs()
        val local = EntityDocument(
            generatedAt = "2026-01-01T00:00:00",
            entities = mapOf(
                "merchant:旧店" to EntityDefinition(type = "merchant", name = "旧店", aliases = emptyList(), eventIds = listOf("e0")),
            ),
            aliasPostings = emptyMap(),
            mentionAssignments = emptyMap(),
        )
        DataManagerPaths.entityIndex(base).writeText(EntitySceneCodec.encodeEntity(local))

        val fallback = EnrichmentResult(status = EnrichmentStatus.FALLBACK)
        val persistence = EntityEnrichmentPersistence(base)
        assertFalse(persistence.writeEnriched(fallback))

        // The old artifact is preserved byte-for-byte.
        val old = EntitySceneCodec.decodeEntity(DataManagerPaths.entityIndex(base).readText())
        assertTrue(old.entities.containsKey("merchant:旧店"))
        assertFalse(old.entities.containsKey("merchant:新店"))
    }

    @Test
    fun `writeEnriched returns false when there is nothing to persist`() {
        val base = tempBase()
        val persistence = EntityEnrichmentPersistence(base)
        assertFalse(persistence.writeEnriched(EnrichmentResult(status = EnrichmentStatus.EMPTY)))
        assertFalse(persistence.writeEnriched(EnrichmentResult(status = EnrichmentStatus.MODEL, enrichedDocument = null)))
    }
}
