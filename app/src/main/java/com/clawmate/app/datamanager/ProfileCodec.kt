package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.ProfileTypes.ProfileState
import kotlinx.serialization.json.Json

/**
 * Deterministic, tolerant JSON serde for the Phase-32 profile artifacts
 * (`indexes/profile/state.json`). Decoding is lenient (unknown keys ignored,
 * bad fields coerced, corrupt JSON recovered to a defensive empty [ProfileState])
 * so a missing / corrupt / oversized on-disk artifact fails open to an honest
 * empty profile — the caller then re-derives from the real indexed events and
 * never lets a stale/broken artifact masquerade as a fresh claim.
 */
object ProfileCodec {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Encode a profile state (schema + version embedded in the artifact). */
    fun encodeState(state: ProfileState): String =
        json.encodeToString(ProfileState.serializer(), state)

    /** Decode a profile state; returns an empty state when corrupt/incompatible. */
    fun decodeState(text: String): ProfileState = runCatching {
        json.decodeFromString(ProfileState.serializer(), text)
    }.getOrElse { ProfileState() }
}
