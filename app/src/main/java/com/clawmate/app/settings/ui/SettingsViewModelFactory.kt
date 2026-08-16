package com.clawmate.app.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clawmate.app.settings.data.SettingsRepository

/** Simple factory for [SettingsViewModel]. */
class SettingsViewModelFactory(
    private val repository: SettingsRepository,
    private val versionName: String,
    private val versionCode: Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(repository, versionName, versionCode) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
