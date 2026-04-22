package com.podcastdiary.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.podcastdiary.data.db.entities.EpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {

    @Query("SELECT * FROM episodes ORDER BY pubDate DESC")
    fun observeAll(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes")
    suspend fun allSnapshot(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE guid = :guid LIMIT 1")
    suspend fun getByGuid(guid: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE guid = :guid LIMIT 1")
    fun observeByGuid(guid: String): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getByDownloadId(downloadId: Long): EpisodeEntity?

    @Query("SELECT DISTINCT category FROM episodes WHERE category IS NOT NULL ORDER BY category ASC")
    fun observeCategories(): Flow<List<String>>

    /**
     * Upsert feed-derived fields only. If a row with the same guid already
     * exists, per-user columns (localPath, downloadState, lastPositionMs,
     * listenedFlag, playCount, firstListenedAt, lastListenedAt, downloadId,
     * durationMs when already known) are preserved.
     */
    @Transaction
    suspend fun upsertFromFeed(items: List<EpisodeEntity>) {
        for (incoming in items) {
            val existing = getByGuid(incoming.guid)
            if (existing == null) {
                insert(incoming)
            } else {
                insert(
                    incoming.copy(
                        localPath = existing.localPath,
                        downloadState = existing.downloadState,
                        downloadId = existing.downloadId,
                        lastPositionMs = existing.lastPositionMs,
                        listenedFlag = existing.listenedFlag,
                        playCount = existing.playCount,
                        firstListenedAt = existing.firstListenedAt,
                        lastListenedAt = existing.lastListenedAt,
                        durationMs = existing.durationMs ?: incoming.durationMs,
                    )
                )
            }
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(episode: EpisodeEntity)

    @Query(
        """
        UPDATE episodes
        SET downloadId = :downloadId, downloadState = :state
        WHERE guid = :guid
        """
    )
    suspend fun setDownloadQueued(guid: String, downloadId: Long, state: String)

    @Query(
        """
        UPDATE episodes
        SET downloadState = :state, localPath = :localPath
        WHERE guid = :guid
        """
    )
    suspend fun setDownloadResult(guid: String, state: String, localPath: String?)

    @Query(
        """
        UPDATE episodes
        SET localPath = NULL,
            downloadState = 'NONE',
            downloadId = NULL
        """
    )
    suspend fun clearAllDownloads()

    @Query("UPDATE episodes SET lastPositionMs = :positionMs WHERE guid = :guid")
    suspend fun setLastPosition(guid: String, positionMs: Long)

    @Query("UPDATE episodes SET durationMs = :durationMs WHERE guid = :guid AND durationMs IS NULL")
    suspend fun setDurationIfMissing(guid: String, durationMs: Long)

    @Query(
        """
        UPDATE episodes
        SET listenedFlag = 1,
            playCount = playCount + 1,
            firstListenedAt = COALESCE(firstListenedAt, :now),
            lastListenedAt = :now
        WHERE guid = :guid
        """
    )
    suspend fun markListened(guid: String, now: Long)

    @Query(
        """
        UPDATE episodes
        SET listenedFlag = 0
        WHERE guid = :guid
        """
    )
    suspend fun markUnlistened(guid: String)

    @Query("UPDATE episodes SET lastListenedAt = :now WHERE guid = :guid")
    suspend fun touchLastListened(guid: String, now: Long)

    @Query("SELECT COUNT(*) FROM episodes WHERE pubDate > :sinceMs")
    suspend fun countNewerThan(sinceMs: Long): Int
}
