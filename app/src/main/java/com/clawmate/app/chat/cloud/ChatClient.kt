package com.clawmate.app.chat.cloud

import kotlinx.coroutines.flow.Flow

/** Contract for an OpenAI-compatible `/v1/chat/completions` client. */
interface ChatClient {
    /** Executes a non-streaming completion. */
    suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult

    /** Streams a completion over SSE as a cold [Flow] of deltas. */
    fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk>
}
