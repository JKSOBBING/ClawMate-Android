package com.clawmate.app.log.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clawmate.app.log.data.InAppLogRepository

/** Simple factory for [LogViewModel]. */
class LogViewModelFactory(
    private val repository: InAppLogRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LogViewModel::class.java)) {
            return LogViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
