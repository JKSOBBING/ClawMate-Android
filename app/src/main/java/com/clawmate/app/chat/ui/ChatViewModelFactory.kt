package com.clawmate.app.chat.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clawmate.app.chat.ChatComponent
import com.clawmate.app.chat.data.ChatRepository

/** [ViewModelProvider.Factory] that wires the real [ChatRepository] into [ChatViewModel]. */
class ChatViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        val repository: ChatRepository = ChatComponent.repository(context)
        return ChatViewModel(repository) as T
    }
}
