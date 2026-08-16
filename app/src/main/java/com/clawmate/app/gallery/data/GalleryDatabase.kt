package com.clawmate.app.gallery.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for gallery items, collection groups and FTS search metadata.
 *
 * Migration policy: every schema change gets an explicit [Migration] and a
 * version bump. `fallbackToDestructiveMigration()` is deliberately NOT used so
 * local gallery data is never silently dropped on an unhandled upgrade.
 */
@Database(
    entities = [
        GalleryItemEntity::class,
        CollectionGroupEntity::class,
        GalleryItemFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun galleryDao(): GalleryDao

    companion object {
        private const val DB_NAME = "clawmate_gallery.db"

        /**
         * Migration table. The gallery schema debuts at version 1 in this
         * phase, so there are no upgrades yet — add them here (in dependency
         * order) whenever `@Database(version=...)` is bumped. Explicit, never
         * destructive.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // v1 -> v2:
            // object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) { ... } }
        )

        fun build(context: Context): GalleryDatabase =
            Room.databaseBuilder(context.applicationContext, GalleryDatabase::class.java, DB_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
