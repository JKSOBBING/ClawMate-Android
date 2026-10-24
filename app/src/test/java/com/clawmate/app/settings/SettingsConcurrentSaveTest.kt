package com.clawmate.app.settings

import androidx.lifecycle.viewModelScope
import com.clawmate.app.settings.data.SettingsRepository
import com.clawmate.app.settings.config.EndpointBridge
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.ui.SettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsConcurrentSaveTest {
    @Test fun aFocusLossSaveDoesNotOverwriteANewerFieldEdit() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val previous = EndpointBridge.seeded(AppSettings())
        val stored = MutableStateFlow(AppSettings())
        val release = CompletableDeferred<Unit>()
        val repository = object : SettingsRepository {
            override val settings = stored
            override suspend fun snapshot() = stored.value
            override suspend fun save(settings: AppSettings): AppSettings {
                release.await()
                stored.value = settings
                return settings
            }
        }
        val vm = SettingsViewModel(repository, "device-test", 1L)
        try {
            runCurrent()
            vm.setCloudPlannerModel("old-fixture")
            vm.save()
            runCurrent()
            assertTrue(vm.state.value.saveBusy)
            vm.setCloudPlannerModel("new-fixture")
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals("old-fixture", stored.value.cloudPlannerModel)
            assertEquals("new-fixture", vm.state.value.draft.cloudPlannerModel)
            assertTrue(vm.state.value.dirty)
            vm.save()
            advanceUntilIdle()
            assertEquals("new-fixture", stored.value.cloudPlannerModel)
            assertFalse(vm.state.value.dirty)
        } finally { vm.viewModelScope.cancel(); EndpointBridge.applyToRuntime(previous); Dispatchers.resetMain() }
    }
}
