package com.clawmate.app.home.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Deterministic in-memory [HomeRecRepository] for tests. Matches the
 * DataStore-backed repository semantics (empty on construction, setCompleted
 * writes a flag, isCompleted reads it back).
 */
class FakeHomeRecRepository(
    seed: Map<String, Boolean> = emptyMap(),
) : HomeRecRepository {

    private val flags = MutableStateFlow(LinkedHashMap(seed))

    override val completionFlags: Flow<Map<String, Boolean>> = flags.map { LinkedHashMap(it) }

    override suspend fun snapshot(): Map<String, Boolean> = LinkedHashMap(flags.value)

    override suspend fun setCompleted(id: String, completed: Boolean): Boolean {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return false
        flags.value = LinkedHashMap(flags.value).also { it[cleanId] = completed }
        return completed
    }
}
