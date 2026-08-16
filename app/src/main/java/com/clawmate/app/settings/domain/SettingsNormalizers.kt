package com.clawmate.app.settings.domain

/**
 * Normalization / validation helpers for typed settings values.
 *
 * Mirrors the deterministic normalizers in the HarmonyOS settings source
 * (e.g. `normalizeChatContextCompressionThresholdPercent`,
 * `normalizeChatContextRecentRounds`, and the gallery/wechat numeric
 * normalizers). Every function is a pure total function: it never throws and
 * always returns a value within the documented range / form, so a corrupt or
 * empty stored value degrades to a safe default rather than breaking the UI.
 */
object SettingsNormalizers {

    // ------------------------------------------------------------------ text

    /** Trim surrounding whitespace; also strips a trailing slash for URLs. */
    fun normalizeUrl(value: String): String {
        var v = value.trim()
        while (v.endsWith("/")) v = v.dropLast(1)
        return v
    }

    /** Trim a non-sensitive short text field. */
    fun normalizeText(value: String): String = value.trim()

    /** Model name: trimmed, empty-allowed (empty means "don't send model"). */
    fun normalizeModel(value: String): String = value.trim()

    // ------------------------------------------------------------- bounded int

    /** Coerce an integer within [min, max]; empty / non-numeric -> [fallback]. */
    fun normalizedInt(
        raw: String,
        fallback: Int,
        min: Int,
        max: Int,
    ): Int {
        val parsed = raw.trim().toIntOrNull() ?: return fallback
        return parsed.coerceIn(min, max)
    }

    /**
     * Coerce a double within [min, max]; empty / non-numeric -> [fallback].
     * Used for swipe ratios and image scale (0.0..1.0 values as strings).
     */
    fun normalizedDouble(
        raw: String,
        fallback: Double,
        min: Double,
        max: Double,
    ): Double {
        val parsed = raw.trim().toDoubleOrNull() ?: return fallback
        return parsed.coerceIn(min, max)
    }

    /** Gallery / OCR concurrency: positive int within @[min]..@[max]. */
    fun concurrency(raw: String, fallback: Int): Int = normalizedInt(raw, fallback, min = 1, max = 16)

    /** Chat context compression threshold percent: 50..95, default 85. */
    fun compressionThresholdPercent(raw: String): Int = normalizedInt(raw, fallback = 85, min = 50, max = 95)

    /** Chat context recent rounds kept: 2..20, default 8. */
    fun recentRounds(raw: String): Int = normalizedInt(raw, fallback = 8, min = 2, max = 20)

    // ------------------------------------------------------------- booleans

    /** Parse a `"true"/"false"`/boolean string safely. */
    fun boolean(raw: String, fallback: Boolean): Boolean {
        val t = raw.trim()
        if (t.equals("true", ignoreCase = true)) return true
        if (t.equals("false", ignoreCase = true)) return false
        return fallback
    }

    /**
     * Parse an Embedding mode enum from a string. Matches the source's
     * `'remote'` / `'huawei_local'` values; anything else is the safe local mode.
     */
    fun embeddingMode(raw: String): EmbeddingMode {
        val t = raw.trim()
        return if (t.equals(EmbeddingMode.Remote.storageKey, ignoreCase = true)) {
            EmbeddingMode.Remote
        } else {
            EmbeddingMode.HuaweiLocal
        }
    }
}

/** Embedding service mode, matching HarmonyOS `'remote'` / `'huawei_local'`. */
enum class EmbeddingMode(val storageKey: String) {
    Remote("remote"),
    HuaweiLocal("huawei_local");
}

/** Action-confirmation policy enums (privacy section). */
enum class AutoConfirmMode(val storageKey: String) {
    Ask("ask"),
    AutoApprove("auto_approve");
}
