package com.clawmate.app.chat.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Room persistence for chat sessions and messages.
 *
 * The HarmonyOS source stores sessions as JSON files under
 * `filesDir/chat-sessions/` (ChatSessionStorage + ChatSessionController). This
 * migration uses Room per MIGRATION_STATUS.md (Chat & Agent Loop) while keeping
 * the same message/session fields and the same derived title/preview rules.
 *
 * Messages are stored flat in [ChatMessageEntity] keyed by session id so a
 * session's stream history can be upserted transactionally without rewriting
 * the whole file.
 */
@Entity(
    tableName = "chat_sessions",
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mode: String,
    val contextSummary: String,
    val customTitle: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "chat_messages",
    indices = [Index("sessionId"), Index(value = ["sessionId", "createdAt"])],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val kind: String,
    val content: String,
    val trace: List<String>,
    val collapsed: Boolean,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val imagePaths: List<String> = emptyList(),
)

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<ChatMessageEntity>)

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    suspend fun observeSessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun session(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun messagesFor(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId AND createdAt >= :cutoff AND createdAt <= :now " +
        "AND kind = 'chat' AND role IN ('user', 'assistant') ORDER BY createdAt ASC, id ASC")
    suspend fun memoryMessagesFor(sessionId: String, cutoff: Long, now: Long): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesFor(sessionId: String)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN imagePaths TEXT NOT NULL DEFAULT ''")
            }
        }
        @Volatile
        private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "clawmate-chat.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}

/**
 * Room [androidx.room.TypeConverter]s for fields that persist as embedded JSON
 * strings. The `trace` field on a message is a small list of tool-traces.
 */
class Converters {
    @TypeConverter
    fun fromTrace(value: List<String>): String = value.joinToString("\n")

    @TypeConverter
    fun toTrace(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("\n")
}
