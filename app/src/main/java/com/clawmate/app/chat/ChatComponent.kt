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

    /** Agent tools with the read-only app-private event index bound for Android. */
    fun tools(context: Context): com.clawmate.app.chat.agent.ToolRegistry =
        com.clawmate.app.chat.agent.createDefaultToolRegistry().apply {
            register(com.clawmate.app.chat.agent.createQueryEventsTool(
                com.clawmate.app.datamanager.DailyLogLocalIndex(context.applicationContext.filesDir),
            ))
        }

    /** The single injectable [ChatRepository] used by the ChatViewModel. */
    fun repository(context: Context): ChatRepository =
        repository ?: DefaultChatRepository(
            dao = dao(context),
            client = client(),
            config = ChatServerConfig.fromEndpointConfig(),
            toolRegistry = tools(context),
            settingsRepository = com.clawmate.app.settings.data.DataStoreSettingsRepository(
                context.applicationContext,
            ),
            imageStore = com.clawmate.app.chat.data.ChatImageFileStore(context.applicationContext.filesDir),
            connectionProvider = {
                val current = ChatServerConfig.fromEndpointConfig()
                current to ChatCompletionsClient(httpClient, current)
            },
        ).also { repository = it }
}
