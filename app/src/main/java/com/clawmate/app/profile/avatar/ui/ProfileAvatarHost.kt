package com.clawmate.app.profile.avatar.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.profile.avatar.data.DataStoreProfileAvatarRepository
import com.clawmate.app.profile.avatar.data.DiskAvatarAssetRepository
import com.clawmate.app.settings.SettingsComponent

/**
 * Self-contained host for the Profile / Avatar screen. Constructs the real
 * ViewModel with:
 *  - the app-private DataStore [DataStoreProfileAvatarRepository] for the
 *    locally curated profile text;
 *  - the app-private, file-backed [DiskAvatarAssetRepository] for the local
 *    avatar asset repository (version history + imported local image);
 *  - the existing [SettingsComponent] settings repository (so the honest
 *    generation gate reads the user-configured avatar service settings, and
 *    generation stays NOT_CONFIGURED absent a wired provider/composable).
 */
@Composable
fun ProfileAvatarHost(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val vm: ProfileAvatarViewModel = viewModel(
        factory = ProfileAvatarViewModel.Factory(
            profileRepository = DataStoreProfileAvatarRepository(context),
            settingsRepository = SettingsComponent.repository(context),
            assetRepository = DiskAvatarAssetRepository(context),
        ),
    )
    ProfileAvatarScreen(viewModel = vm, onClose = onClose)
}
