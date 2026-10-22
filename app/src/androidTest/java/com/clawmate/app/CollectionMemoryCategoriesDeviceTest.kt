package com.clawmate.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.app.gallery.collection.IndexedEventCollectionService
import com.clawmate.app.gallery.ui.SummarySceneTabs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionMemoryCategoriesDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun overflowMemoryCategoryCanScrollIntoViewAndBeSelected() {
        val selected = mutableStateOf(IndexedEventCollectionService.tabForScene(0))
        compose.setContent { MaterialTheme { SummarySceneTabs(selected.value) { selected.value = it } } }
        compose.onNodeWithText("个人信息").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(12, selected.value.sceneId) }
        compose.onNodeWithText("外卖").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, selected.value.sceneId) }
    }
}
