package com.clawmate.platform.mika

import com.wangzan101.mika.core.cap.phone.AppPacePlan
import com.wangzan101.mika.core.cap.phone.OperatingPace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persisted 「操作节奏」 for phone tasks (upstream #574): one global gear plus per-app overrides.
 *
 * Upstream keeps the same pair in `FeatureFlags` (SharedPreferences) and seeds every App whose
 * bundled knowledge is marked `careful` to [OperatingPace.HUMAN] the first time the list is
 * opened. This port keeps that seeding rule and stores the same two values in one small
 * app-private JSON file, written atomically, so a task start only has to read it.
 *
 * Unreadable or malformed storage never fails a task: it falls back to the built-in defaults.
 */
class MikaPaceSettings(
    private val file: File,
    /** Seeds per-app overrides the first time the file is created; upstream uses the careful Apps. */
    private val seed: () -> Map<String, OperatingPace> = { emptyMap() },
) {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val lock = Any()

    /** Global gear plus per-app overrides, seeded once when the file does not exist yet. */
    fun load(): AppPacePlan = synchronized(lock) { read(createIfMissing = true) }

    fun global(): OperatingPace = load().global

    fun overrides(): Map<String, OperatingPace> = load().overrides

    fun setGlobal(pace: OperatingPace) {
        synchronized(lock) {
            val current = read(createIfMissing = false)
            write(PaceState(pace.name, current.overrides.mapValues { it.value.name }))
        }
    }

    /** [pace] `null` removes the override, so the App follows the global gear again. */
    fun setOverride(pkg: String, pace: OperatingPace?) {
        val key = pkg.trim()
        if (key.isEmpty()) return
        synchronized(lock) {
            val current = read(createIfMissing = false)
            val next = current.overrides.toMutableMap()
            if (pace == null) next.remove(key) else next[key] = pace
            write(PaceState(current.global.name, next.mapValues { it.value.name }))
        }
    }

    private fun read(createIfMissing: Boolean): AppPacePlan {
        val stored = if (file.isFile) {
            runCatching { json.decodeFromString(PaceState.serializer(), file.readText()) }.getOrNull()
        } else {
            null
        }
        if (stored == null) {
            if (!createIfMissing) return AppPacePlan(OperatingPace.DEFAULT)
            val seeded = runCatching(seed).getOrDefault(emptyMap())
            val plan = AppPacePlan(OperatingPace.DEFAULT, seeded)
            write(PaceState(plan.global.name, seeded.mapValues { it.value.name }))
            return plan
        }
        val overrides = stored.overrides.mapNotNull { (pkg, raw) ->
            OperatingPace.parse(raw)?.let { pkg to it }
        }.toMap()
        return AppPacePlan(OperatingPace.parse(stored.global) ?: OperatingPace.DEFAULT, overrides)
    }

    private fun write(state: PaceState) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(json.encodeToString(PaceState.serializer(), state))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    @Serializable
    private data class PaceState(
        val global: String = OperatingPace.DEFAULT.name,
        val overrides: Map<String, String> = emptyMap(),
    )
}

/**
 * Cycles a per-App gear the way the settings dialog taps it:
 * 跟随全局 → 快 → 标准 → 像人 → 跟随全局. Pure so the dialog stays a thin wrapper.
 */
object MikaPaceCycle {
    fun next(current: OperatingPace?): OperatingPace? = when (current) {
        null -> OperatingPace.FAST
        OperatingPace.FAST -> OperatingPace.STANDARD
        OperatingPace.STANDARD -> OperatingPace.HUMAN
        OperatingPace.HUMAN -> null
    }
}
