package com.clawmate.platform.gui

/** Android's generic FORWARD action also scrolls horizontal tabs; it is not a vertical hint. */
internal data class MikaScrollCandidate<T>(
    val value: T, val role: String, val bounds: List<Int>,
    val vertical: Boolean, val horizontal: Boolean,
) {
    val acceptsVertical: Boolean get() = vertical || !horizontal && role !in setOf(
        "HorizontalScrollView", "ViewPager", "ViewPager2",
    )
    val area: Long get() = (bounds[2] - bounds[0]).coerceAtLeast(0).toLong() *
        (bounds[3] - bounds[1]).coerceAtLeast(0)
}

/** Prefer direction-specific support, then the largest content region, never a horizontal tab bar. */
internal fun <T> verticalScrollTarget(candidates: List<MikaScrollCandidate<T>>): T? = candidates
    .filter { it.acceptsVertical && it.area > 0 }
    .maxWithOrNull(compareBy<MikaScrollCandidate<T>> { it.vertical }.thenBy { it.area })?.value
