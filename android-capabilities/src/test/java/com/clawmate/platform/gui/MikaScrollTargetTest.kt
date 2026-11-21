package com.clawmate.platform.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MikaScrollTargetTest {
    private fun candidate(name: String, role: String, bounds: List<Int>, vertical: Boolean = false,
        horizontal: Boolean = false) = MikaScrollCandidate(name, role, bounds, vertical, horizontal)

    @Test fun searchTabStripAndPagerDoNotConsumeVerticalCollectionScrolls() {
        assertEquals("posts", verticalScrollTarget(listOf(
            candidate("tabs", "HorizontalScrollView", listOf(0, 106, 897, 159)),
            candidate("pager", "ViewPager", listOf(0, 159, 999, 931)),
            candidate("posts", "RecyclerView", listOf(0, 159, 999, 931)),
        )))
    }
    @Test fun noVerticalContainerFallsBackToGestureInsteadOfMovingTabs() {
        assertNull(verticalScrollTarget(listOf(candidate("tabs", "HorizontalScrollView", listOf(0, 0, 999, 80)))))
    }
    @Test fun explicitVerticalPagerIsSupportedAndHorizontalCustomCarouselIsExcluded() {
        assertEquals("verticalPager", verticalScrollTarget(listOf(
            candidate("carousel", "CustomList", listOf(0, 0, 999, 999), horizontal = true),
            candidate("verticalPager", "ViewPager2", listOf(0, 100, 999, 900), vertical = true),
        )))
    }
    @Test fun largestContentRegionWinsAmongLegacyForwardOnlyContainers() {
        assertEquals("article", verticalScrollTarget(listOf(
            candidate("small", "ScrollView", listOf(0, 0, 300, 100)),
            candidate("article", "RecyclerView", listOf(0, 100, 999, 900)),
        )))
    }
}
