package com.clawmate.app.task.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clawmate.app.task.data.WorkflowRepository
import com.clawmate.app.task.runner.WorkflowRunner

/** Simple factory for [TaskViewModel]. */
class TaskViewModelFactory(
    private val repository: WorkflowRepository,
    private val runner: WorkflowRunner,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TaskViewModel::class.java)) {
            return TaskViewModel(repository, runner) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
