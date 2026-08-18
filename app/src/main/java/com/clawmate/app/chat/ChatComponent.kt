package com.clawmate.app.chat

import android.content.Context
import com.clawmate.app.chat.cloud.ChatCompletionsClient
import com.clawmate.app.chat.cloud.ChatServerConfig
import com.clawmate.app.chat.data.ChatDao
import com.clawmate.app.chat.data.ChatDatabase
import com.clawmate.app.chat.data.ChatRepository
import com.clawmate.app.chat.data.DefaultChatRepository
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Simple manual dependency container for the chat feature (phase 2).
 *
 * There is no DI framework in the skeleton; this object lazily wires the Room
 * database, OkHttp client, repository, and the client configuration. Endpoints
 * and keys come from [ChatServerConfig.fromEndpointConfig] (loaded from the
 * git-ignored `local_config.properties`), never from source code.
 */
object ChatComponent {

    @Volatile
    private var repository: ChatRepository? = null

    @Volatile
    private var dao: ChatDao? = null

    @Volatile
    private var client: ChatCompletionsClient? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    fun dao(context: Context): ChatDao =
        dao ?: ChatDatabase.get(context).chatDao().also { dao = it }

    fun client(): ChatCompletionsClient =
        client ?: ChatCompletionsClient(httpClient, ChatServerConfig.fromEndpointConfig())
            .also { client = it }

    /** The single injectable [ChatRepository] used by the ChatViewModel. */
    fun repository(context: Context): ChatRepository =
        repository ?: DefaultChatRepository(
            dao = dao(context),
            client = client(),
            config = ChatServerConfig.fromEndpointConfig(),
            settingsRepository = com.clawmate.app.settings.data.DataStoreSettingsRepository(
                context.applicationContext,
            ),
        ).also { repository = it }
}
