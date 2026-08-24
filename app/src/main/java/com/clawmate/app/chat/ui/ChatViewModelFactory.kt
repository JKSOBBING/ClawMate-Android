package com.clawmate.app.chat.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clawmate.app.chat.ChatComponent
import com.clawmate.app.chat.data.ChatRepository
import com.clawmate.app.chat.voice.ChatVoiceController

/** [ViewModelProvider.Factory] that wires the real [ChatRepository] + [ChatVoiceController] into [ChatViewModel]. */
class ChatViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        val repository: ChatRepository = ChatComponent.repository(context)
        val voiceController = ChatVoiceController(context.applicationContext)
        return ChatViewModel(
            repository = repository,
            voiceController = voiceController,
            filesDir = context.filesDir?.path,
        ) as T
    }
}
