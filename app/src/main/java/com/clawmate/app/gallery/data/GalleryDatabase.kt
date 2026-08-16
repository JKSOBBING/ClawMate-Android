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
    version = 2,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun galleryDao(): GalleryDao

    companion object {
        private const val DB_NAME = "clawmate_gallery.db"

        /**
         * Migration table, in dependency order. Every schema change gets an
         * explicit, never-destructive migration.
         *
         * v1 -> v2 (Phase 9): the gallery item grows a resolved reverse-geocoded
         * address, its provider, and a deterministic analysis lifecycle state.
         * New columns are added empty so existing items stay truthful (no
         * inferred address, no fake analysis state).
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE gallery_items ADD COLUMN address TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE gallery_items ADD COLUMN locationProvider TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE gallery_items ADD COLUMN analysisState TEXT NOT NULL DEFAULT ''")
                }
            },
        )

        fun build(context: Context): GalleryDatabase =
            Room.databaseBuilder(context.applicationContext, GalleryDatabase::class.java, DB_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
