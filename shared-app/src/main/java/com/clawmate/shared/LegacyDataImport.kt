package com.clawmate.shared

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** First shared-host launch over the Compose product. The original Room database stays available for rollback. */
internal object LegacyDataImport {
    @Volatile private var state = "idle"

    @Synchronized fun status(context: Context): String {
        if (state == "running" || state == "done") return state
        if (state != "idle") return state.also { state = "idle" }
        if (context.packageName != "com.clawmate.app") return "done"
        val files = context.filesDir
        val database = context.getDatabasePath("clawmate-chat.db")
        val gallery = context.getDatabasePath("clawmate_gallery.db")
        val workflows = File(files, "workflows")
        if (!database.isFile && !gallery.isFile && !workflows.isDirectory) return "done"
        state = "running"
        thread(name = "ClawMate-data-upgrade") {
            state = try {
                importExisting(files, database, gallery)
                "done"
            } catch (_: Exception) {
                "旧版资料尚未全部导入。原文件已保留，请检查剩余空间后重新启动。"
            }
        }
        return state
    }

    internal fun importExisting(files: File, database: File, gallery: File) {
        if (database.isFile) importDatabase(database, files)
        if (gallery.isFile) importGallery(gallery, files)
        val workflows = File(files, "workflows")
        if (workflows.isDirectory) importWorkflows(workflows, files)
    }

    private fun importDatabase(database: File, files: File) {
        val root = File(files, "files").apply { mkdirs() }
        val target = File(root, "chat-sessions")
        val receipt = ".compose-room-v1.json"
        if (File(target, receipt).isFile) return
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.version in 1..2)
            val total = db.count("chat_sessions")
            if (total == 0L && db.count("chat_messages") == 0L) return
            check(!target.exists()) { "Existing shared chat records must not be overwritten" }
            val stage = File(root, ".compose-chat-import-v1")
            // The staging directory is never read by the UI. Rebuild it after an interrupted import.
            check(!stage.exists() || stage.deleteRecursively())
            val sessions = File(stage, "sessions").apply { check(mkdirs()) }
            val assets = File(stage, "assets").apply { check(mkdir()) }
            val index = JSONArray()
            var messageCount = 0L
            db.rawQuery("SELECT id,title,customTitle,contextSummary,mode,createdAt,updatedAt FROM chat_sessions ORDER BY updatedAt DESC,id", null).use { rows ->
                while (rows.moveToNext()) {
                    val id = rows.text("id")
                    require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                    val record = JSONObject().put("id", id).put("title", rows.text("title"))
                        .put("customTitle", rows.text("customTitle")).put("mode", rows.text("mode"))
                        .put("createdAt", rows.number("createdAt")).put("updatedAt", rows.number("updatedAt"))
                    val messages = JSONArray()
                    db.rawQuery("SELECT * FROM chat_messages WHERE sessionId=? ORDER BY createdAt,rowid", arrayOf(id)).use { msg ->
                        while (msg.moveToNext()) {
                            val message = JSONObject().put("id", msg.text("id")).put("role", msg.text("role"))
                                .put("kind", msg.text("kind")).put("content", msg.text("content"))
                                .put("createdAt", msg.number("createdAt")).put("collapsed", msg.number("collapsed") != 0L)
                                .put("trace", JSONArray(lines(msg.text("trace"))))
                            if (msg.getColumnIndex("imagePaths") >= 0) {
                                val paths = JSONArray()
                                for (name in lines(msg.text("imagePaths"))) {
                                    require(name.matches(Regex("[0-9a-f-]{36}\\.(jpg|png)")))
                                    val original = File(File(files, "chat-images"), name)
                                    val copy = File(File(assets, id), name)
                                    // Preserve even a previously missing attachment reference on the old record.
                                    if (original.isFile) {
                                        if (!copy.exists()) original.copyTo(copy)
                                        paths.put("file://" + File(target, "assets/$id/$name").path)
                                    } else {
                                        paths.put("file://" + original.path)
                                    }
                                }
                                message.put("imagePaths", paths)
                            }
                            messages.put(message)
                        }
                    }
                    val name = "$id.json"
                    val summary = JSONObject(record.toString()).put("fileName", name).put("messageCount", messages.length())
                        .put("lastMessagePreview", messages.optJSONObject(messages.length() - 1)?.optString("content").orEmpty().take(100))
                    if (rows.text("customTitle").isNotBlank()) summary.put("title", rows.text("customTitle"))
                    index.put(summary)
                    record.put("contextSummary", rows.text("contextSummary")).put("messages", messages)
                    File(sessions, name).writeText(record.toString())
                    messageCount += messages.length()
                }
            }
            check(index.length().toLong() == total && messageCount == db.count("chat_messages"))
            File(stage, "session-index.json").writeText(JSONObject().put("sessions", index).toString())
            File(stage, receipt).writeText(JSONObject().put("v", 1).put("sessions", total).put("messages", messageCount).toString())
            Os.rename(stage.path, target.path)
        }
    }

    private fun importGallery(database: File, files: File) {
        val root = File(files, "files").apply { mkdirs() }
        val target = File(root, "gallery-log")
        val receipt = ".compose-gallery-v1.json"
        if (File(target, receipt).isFile) return
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.version in 1..2)
            val total = db.count("gallery_items")
            if (total == 0L) return
            check(!target.exists())
            val stage = File(root, ".compose-gallery-import-v1")
            check(!stage.exists() || stage.deleteRecursively())
            val items = File(stage, "items").apply { check(mkdirs()) }
            var imported = 0L
            db.rawQuery("SELECT * FROM gallery_items", null).use { row ->
                while (row.moveToNext()) {
                    val date = row.text("date")
                    val id = row.text("id")
                    require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && id.matches(Regex("[A-Za-z0-9_-]{1,160}")))
                    val item = JSONObject()
                    for (name in listOf("id", "uri", "date", "title", "category", "summary", "ocrText", "rawModelText",
                            "address", "locationProvider", "analysisState")) {
                        if (row.getColumnIndex(name) >= 0) item.put(name, row.text(name))
                    }
                    for (name in listOf("details", "tags", "keywords")) {
                        val value = row.text(name)
                        item.put(name, if (value.isBlank()) JSONArray() else JSONArray(value))
                    }
                    for (name in listOf("latitude", "longitude")) {
                        val col = row.getColumnIndexOrThrow(name)
                        if (!row.isNull(col)) item.put(name, row.getDouble(col))
                    }
                    val exif = row.text("exifJson")
                    if (exif.isNotBlank() && exif != "null") item.put("exif", JSONObject(exif))
                    item.put("createdAt", row.number("createdAt")).put("embeddingRef", "")
                        .put("embeddingStatus", "").put("embeddingDimension", 0).put("error", "")
                    val day = File(items, date).apply { mkdirs() }
                    File(day, "$id.json").writeText(item.toString())
                    imported++
                }
            }
            check(imported == total)
            File(stage, receipt).writeText(JSONObject().put("v", 1).put("items", total).toString())
            Os.rename(stage.path, target.path)
        }
    }

    private fun importWorkflows(source: File, files: File) {
        val root = File(files, "files").apply { mkdirs() }
        val target = File(root, "workflows")
        val receipt = ".compose-workflows-v1"
        if (File(target, receipt).isFile) return
        check(!target.exists())
        val stage = File(root, ".compose-workflows-import-v1")
        check(!stage.exists() || stage.deleteRecursively())
        for (file in source.walkTopDown()) {
            check(!java.nio.file.Files.isSymbolicLink(file.toPath()))
            val destination = File(stage, file.relativeTo(source).path)
            if (file.isDirectory) check(destination.isDirectory || destination.mkdirs())
            else {
                check(file.isFile)
                file.copyTo(destination)
                check(file.length() == destination.length())
            }
        }
        File(stage, receipt).writeText("1")
        Os.rename(stage.path, target.path)
    }

    private fun lines(text: String) = if (text.isEmpty()) emptyList() else text.split('\n')
    private fun Cursor.text(column: String) = getString(getColumnIndexOrThrow(column)).orEmpty()
    private fun Cursor.number(column: String) = getLong(getColumnIndexOrThrow(column))
    private fun SQLiteDatabase.count(table: String) = rawQuery("SELECT count(*) FROM $table", null).use {
        check(it.moveToFirst())
        it.getLong(0)
    }
}
