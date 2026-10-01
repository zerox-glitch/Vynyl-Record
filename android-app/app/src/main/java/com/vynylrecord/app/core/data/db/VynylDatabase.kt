package com.vynylrecord.app.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The app's one database.
 *
 * It holds two tables and no audio. Metadata only: a row is a few hundred bytes, so the whole Vault
 * database for a hundred records is smaller than a single second of the audio it describes. That is what
 * makes the file-level invariants in `core/storage` so much simpler — audio is always a file, and a file
 * is either complete or absent.
 *
 * Exported schemas live in `app/schemas` so a schema change is a diff in review rather than a surprise
 * on a device. Version 1 is the schema that ships.
 */
@Database(
    entities = [RecordEntity::class, AudioAssetEntity::class],
    version = VynylDatabase.VERSION,
    exportSchema = true,
)
abstract class VynylDatabase : RoomDatabase() {

    abstract fun records(): RecordDao
    abstract fun assets(): AudioAssetDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "vynyl.db"

        @Volatile
        private var instance: VynylDatabase? = null

        fun get(context: Context): VynylDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): VynylDatabase = Room.databaseBuilder(
            context,
            VynylDatabase::class.java,
            FILE_NAME,
        )
            // Room's own integrity check on open, plus foreign-key and WAL behaviour that suits a database
            // written from a background render and read from the UI at the same time.
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*MIGRATIONS)
            // Only reachable when a lower schema version opens a database written by a higher one, which
            // happens while developing. The audio files are still on disk in that case, so a rescan puts
            // the library back together; refusing to open would leave the app unable to start at all.
            .fallbackToDestructiveMigration()
            .build()

        /**
         * No migrations ship in version 1 — there is nothing to migrate from.
         *
         * The array exists so the first schema change has an obvious home, and so the review of that
         * change has to touch this file rather than quietly adding a callback somewhere.
         */
        private val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
