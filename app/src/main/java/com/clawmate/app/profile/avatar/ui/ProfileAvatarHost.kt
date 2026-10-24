package com.clawmate.app.profile.avatar.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.profile.avatar.data.DataStoreProfileAvatarRepository
import com.clawmate.app.profile.avatar.data.DiskAvatarAssetRepository
import com.clawmate.app.profile.avatar.data.generation.SeedreamAvatarProvider
import com.clawmate.app.settings.SettingsComponent

/**
 * Self-contained host for the Profile / Avatar screen. Constructs the real
 * ViewModel with:
 *  - the app-private DataStore [DataStoreProfileAvatarRepository] for the
 *    locally curated profile text;
 *  - the app-private, file-backed [DiskAvatarAssetRepository] for the local
 *    avatar asset repository (version history + imported local image + the
 *    real generated-image result path);
 *  - the existing [SettingsComponent] settings repository (so the honest
 *    generation gate reads the user-configured avatar service settings);
 *  - the production [SeedreamAvatarProvider], which reaches the configured
 *    Seedream endpoint only when the gate is READY (base URL + api key + model
 *    + size present).
 */
@Composable
fun ProfileAvatarHost(onClose: () -> Unit, supplementaryContent: @Composable () -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val vm: ProfileAvatarViewModel = viewModel(
        factory = ProfileAvatarViewModel.Factory(
            profileRepository = DataStoreProfileAvatarRepository(context),
            settingsRepository = SettingsComponent.repository(context),
            assetRepository = DiskAvatarAssetRepository(context),
            provider = SeedreamAvatarProvider.withDefaultClient(),
        ),
    )
    ProfileAvatarScreen(viewModel = vm, onClose = onClose, supplementaryContent = supplementaryContent)
}
