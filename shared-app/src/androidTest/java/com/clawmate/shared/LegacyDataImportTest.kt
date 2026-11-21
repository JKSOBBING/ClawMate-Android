package com.clawmate.shared

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LegacyDataImportTest {
    @get:Rule val temp = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test fun settingsUseTheirOriginalServiceAndPreserveOnboarding() {
        val files = temp.newFolder("preferences")
        val storeDir = File(files, "datastore").apply { mkdirs() }
        runBlocking {
            val job = SupervisorJob()
            val scope = CoroutineScope(Dispatchers.IO + job)
            try {
                val settings = PreferenceDataStoreFactory.create(scope = scope) { File(storeDir, "clawmate_settings.preferences_pb") }
                settings.edit {
                    it[stringPreferencesKey("cloud_planner_base_url")] = "https://qa.invalid/v1"
                    it[stringPreferencesKey("cloud_planner_api_key")] = "fixture"
                    it[stringPreferencesKey("cloud_decider_api_key")] = "missing-origin"
                    it[stringPreferencesKey("chat_recent_rounds")] = "7"
                }
                val home = PreferenceDataStoreFactory.create(scope = scope) { File(storeDir, "clawmate_home.preferences_pb") }
                home.edit { it[stringPreferencesKey("firstLaunchOnboardingVersion")] = "previous" }
            } finally { job.cancelAndJoin() }
        }
        val actual = LegacySettingsImport.read(files)
        assertEquals("fixture", actual.getString("cloudPlannerApiKey"))
        assertEquals("https://qa.invalid/v1", actual.getString("cloudPlannerBaseUrl"))
        assertFalse(actual.has("cloudDeciderApiKey"))
        assertEquals("7", actual.getString("chatContextRecentRounds"))
        assertEquals("previous", actual.getString("firstLaunchOnboardingVersion"))
    }

    @Test fun bothChatSchemasAndAttachmentsSurviveImportAndRetry() {
        for (version in 1..2) {
            val files = temp.newFolder("files-$version")
            val dbFile = File(temp.root, "chat-$version.db")
            val name = "12345678-1234-1234-1234-123456789abc.jpg"
            val image = File(files, "chat-images/$name").apply { parentFile!!.mkdirs(); writeText("fixture") }
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                db.version = version
                db.execSQL("CREATE TABLE chat_sessions (id TEXT PRIMARY KEY,title TEXT,customTitle TEXT,contextSummary TEXT,mode TEXT,createdAt INTEGER,updatedAt INTEGER)")
                db.execSQL("CREATE TABLE chat_messages (id TEXT PRIMARY KEY,sessionId TEXT,role TEXT,kind TEXT,content TEXT,trace TEXT,collapsed INTEGER,createdAt INTEGER)")
                db.execSQL("INSERT INTO chat_sessions VALUES ('sess-test','Old','Pinned name','summary','cloud_agent',1,2)")
                db.execSQL("INSERT INTO chat_messages VALUES ('m1','sess-test','user','chat','prior content','first',0,2)")
                if (version == 2) {
                    db.execSQL("ALTER TABLE chat_messages ADD COLUMN imagePaths TEXT DEFAULT ''")
                    db.execSQL("UPDATE chat_messages SET imagePaths=?", arrayOf(name))
                }
            }
            val original = dbFile.readBytes()
            val workflows = File(files, "workflows/configs/custom.json").apply { parentFile!!.mkdirs(); writeText("{}") }
            val gallery = File(temp.root, "no-gallery")
            LegacyDataImport.importExisting(files, dbFile, gallery)
            val root = File(files, "files/chat-sessions")
            val recordFile = File(root, "sessions/sess-test.json")
            val record = JSONObject(recordFile.readText())
            val msg = record.getJSONArray("messages").getJSONObject(0)
            assertEquals("prior content", msg.getString("content"))
            assertEquals("summary", record.getString("contextSummary"))
            assertEquals("Pinned name", JSONObject(File(root,"session-index.json").readText()).getJSONArray("sessions").getJSONObject(0).getString("title"))
            if (version == 2) {
                assertEquals(image.readText(), File(java.net.URI(msg.getJSONArray("imagePaths").getString(0))).readText())
            }
            assertEquals(workflows.readText(), File(files,"files/workflows/configs/custom.json").readText())
            recordFile.writeText("post-upgrade edit")
            LegacyDataImport.importExisting(files, dbFile, gallery)
            assertEquals("post-upgrade edit", recordFile.readText())
            assertArrayEquals(original, dbFile.readBytes())
        }
    }

    @Test fun galleryMetadataSurvivesAndPartialOutputIsNeverPublished() {
        val files = temp.newFolder("host-files")
        val chat = File(temp.root, "no-chat")
        val gallery = File(temp.root, "gallery.db")
        SQLiteDatabase.openOrCreateDatabase(gallery, null).use { db ->
            db.version = 2
            db.execSQL("CREATE TABLE gallery_items(id TEXT,date TEXT,uri TEXT,title TEXT,category TEXT,summary TEXT,ocrText TEXT,rawModelText TEXT,details TEXT,tags TEXT,keywords TEXT,latitude REAL,longitude REAL,exifJson TEXT,createdAt INTEGER)")
            db.execSQL("""INSERT INTO gallery_items VALUES('photo1','2026-11-20','content://test/1','Receipt','文档','summary','ocr','','["one"]','','',NULL,NULL,'{}',2)""")
            db.execSQL("INSERT INTO gallery_items SELECT '../escape',date,uri,title,category,summary,ocrText,rawModelText,details,tags,keywords,latitude,longitude,exifJson,createdAt FROM gallery_items")
        }
        assertThrows(IllegalArgumentException::class.java) { LegacyDataImport.importExisting(files, chat, gallery) }
        assertFalse(File(files, "files/gallery-log").exists())
        SQLiteDatabase.openDatabase(gallery.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.delete("gallery_items","id=?", arrayOf("../escape")) }
        LegacyDataImport.importExisting(files, chat, gallery)
        val photo = JSONObject(File(files, "files/gallery-log/items/2026-11-20/photo1.json").readText())
        assertEquals("content://test/1", photo.getString("uri"))
        assertEquals("one", photo.getJSONArray("details").getString(0))
        assertEquals("ocr", photo.getString("ocrText"))
    }
}
