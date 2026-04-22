package com.podcastdiary.data

import com.podcastdiary.data.db.EpisodeDao
import com.podcastdiary.data.db.ListenEventDao
import com.podcastdiary.data.db.entities.EpisodeEntity
import com.podcastdiary.data.db.entities.ListenEventEntity
import com.podcastdiary.data.feed.ParsedEpisode
import com.podcastdiary.data.feed.RssFeedFetcher
import com.podcastdiary.data.feed.RssFeedParser
import com.podcastdiary.data.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class EpisodeRepository(
    private val episodeDao: EpisodeDao,
    private val listenEventDao: ListenEventDao,
    private val fetcher: RssFeedFetcher,
    private val parser: RssFeedParser,
    private val settings: SettingsStore,
    private val feedUrl: String = DEFAULT_FEED_URL,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun observeAll(): Flow<List<EpisodeEntity>> = episodeDao.observeAll()
    fun observeCategories(): Flow<List<String>> = episodeDao.observeCategories()
    fun observeEpisode(guid: String): Flow<EpisodeEntity?> = episodeDao.observeByGuid(guid)
    suspend fun getEpisode(guid: String): EpisodeEntity? = episodeDao.getByGuid(guid)

    fun observeListenEvents(guid: String): Flow<List<ListenEventEntity>> =
        listenEventDao.observeForEpisode(guid)

    /**
     * Fetches the feed, upserts episodes (preserving per-user columns), and
     * returns the number of episodes newer than the previous sync high-water.
     */
    suspend fun syncFeed(): SyncResult {
        val prevSync = settings.lastFeedSyncMs.first()
        val xml = fetcher.fetch(feedUrl)
        val parsed = parser.parse(xml)
        val entities = parsed.map { it.toEntity() }
        episodeDao.upsertFromFeed(entities)
        val newest = entities.maxByOrNull { it.pubDate }
        val newCount = entities.count { it.pubDate > prevSync }
        settings.setLastFeedSync(clock(), newest?.guid)
        return SyncResult(totalParsed = entities.size, newSincePreviousSync = newCount)
    }

    suspend fun setLastPosition(guid: String, positionMs: Long) {
        episodeDao.setLastPosition(guid, positionMs)
    }

    suspend fun setDurationIfMissing(guid: String, durationMs: Long) {
        episodeDao.setDurationIfMissing(guid, durationMs)
    }

    suspend fun markListened(guid: String) {
        episodeDao.markListened(guid, clock())
    }

    suspend fun markUnlistened(guid: String) {
        episodeDao.markUnlistened(guid)
    }

    suspend fun touchLastListened(guid: String) {
        episodeDao.touchLastListened(guid, clock())
    }

    suspend fun recordListenSession(
        guid: String,
        startedAt: Long,
        endedAt: Long,
        startPositionMs: Long,
        endPositionMs: Long,
    ) {
        listenEventDao.insert(
            ListenEventEntity(
                episodeGuid = guid,
                startedAt = startedAt,
                endedAt = endedAt,
                startPositionMs = startPositionMs,
                endPositionMs = endPositionMs,
            )
        )
    }

    suspend fun resetAllDownloadedState() {
        episodeDao.clearAllDownloads()
    }

    data class SyncResult(
        val totalParsed: Int,
        val newSincePreviousSync: Int,
    )

    companion object {
        const val DEFAULT_FEED_URL = "https://divineinterventionpodcasts.com/feed/"
    }
}

internal fun ParsedEpisode.toEntity(): EpisodeEntity = EpisodeEntity(
    guid = guid,
    episodeNumber = episodeNumber,
    title = title,
    description = description,
    pubDate = pubDate,
    pageUrl = pageUrl,
    audioUrl = audioUrl,
    audioByteLength = audioByteLength,
    durationMs = durationMs,
    category = category,
    localPath = null,
    downloadState = EpisodeEntity.STATE_NONE,
    downloadId = null,
    lastPositionMs = 0L,
    listenedFlag = false,
    playCount = 0,
    firstListenedAt = null,
    lastListenedAt = null,
)
