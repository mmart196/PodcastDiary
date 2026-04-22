package com.podcastdiary.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.podcastdiary.data.db.entities.EpisodeEntity
import com.podcastdiary.data.db.entities.ListenEventEntity

@Database(
    entities = [EpisodeEntity::class, ListenEventEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PodcastDatabase : RoomDatabase() {
    abstract fun episodeDao(): EpisodeDao
    abstract fun listenEventDao(): ListenEventDao

    companion object {
        fun build(context: Context): PodcastDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PodcastDatabase::class.java,
                "podcastdiary.db"
            ).build()
    }
}
