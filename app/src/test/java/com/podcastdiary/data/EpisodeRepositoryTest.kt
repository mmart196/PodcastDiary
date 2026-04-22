package com.podcastdiary.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.podcastdiary.data.db.PodcastDatabase
import com.podcastdiary.data.db.entities.EpisodeEntity
import com.podcastdiary.data.feed.RssFeedFetcher
import com.podcastdiary.data.feed.RssFeedParser
import com.podcastdiary.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpisodeRepositoryTest {

    private lateinit var db: PodcastDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: EpisodeRepository
    private lateinit var settings: SettingsStore

    private val fakeFeed = """
        <?xml version="1.0"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <item>
              <title>Episode 10: Foo</title>
              <guid>g-10</guid>
              <pubDate>Wed, 15 Apr 2026 10:00:00 +0000</pubDate>
              <link>https://x/10</link>
              <description>d10</description>
              <category>USMLE Step 1</category>
              <enclosure url="https://x/10.mp3" length="100" type="audio/mpeg"/>
              <itunes:duration>10:00</itunes:duration>
            </item>
            <item>
              <title>Episode 9: Bar</title>
              <guid>g-9</guid>
              <pubDate>Wed, 08 Apr 2026 10:00:00 +0000</pubDate>
              <link>https://x/9</link>
              <description>d9</description>
              <category>USMLE Step 2 CK</category>
              <enclosure url="https://x/9.mp3" length="200" type="audio/mpeg"/>
              <itunes:duration>20:00</itunes:duration>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Clear any DataStore state from a previous test in the same JVM.
        val dsDir = java.io.File(ctx.filesDir, "datastore")
        dsDir.listFiles()?.forEach { it.delete() }
        db = Room.inMemoryDatabaseBuilder(ctx, PodcastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer().apply { start() }
        settings = SettingsStore(ctx)
        repo = EpisodeRepository(
            episodeDao = db.episodeDao(),
            listenEventDao = db.listenEventDao(),
            fetcher = RssFeedFetcher(),
            parser = RssFeedParser(),
            settings = settings,
            feedUrl = server.url("/feed").toString(),
            clock = { 1_700_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun syncFeedParsesAndPersistsEpisodes() = runBlocking {
        server.enqueue(MockResponse().setBody(fakeFeed))
        val result = repo.syncFeed()
        assertEquals(2, result.totalParsed)
        val all = repo.observeAll().first()
        assertEquals(2, all.size)
        val ep10 = all.single { it.guid == "g-10" }
        assertEquals(10, ep10.episodeNumber)
        assertEquals("https://x/10.mp3", ep10.audioUrl)
        assertEquals("USMLE Step 1", ep10.category)
    }

    @Test
    fun resyncPreservesPerUserColumns() = runBlocking {
        server.enqueue(MockResponse().setBody(fakeFeed))
        repo.syncFeed()

        // Simulate user listening to episode 10
        db.episodeDao().markListened("g-10", 1_700_000_000_000L)
        db.episodeDao().setLastPosition("g-10", 123_456L)
        db.episodeDao().setDownloadResult("g-10", EpisodeEntity.STATE_DONE, "/tmp/ep10.mp3")

        server.enqueue(MockResponse().setBody(fakeFeed))
        repo.syncFeed()

        val ep10 = repo.getEpisode("g-10")!!
        assertTrue("listenedFlag preserved", ep10.listenedFlag)
        assertEquals(1, ep10.playCount)
        assertEquals(123_456L, ep10.lastPositionMs)
        assertEquals(EpisodeEntity.STATE_DONE, ep10.downloadState)
        assertEquals("/tmp/ep10.mp3", ep10.localPath)
        assertNotNull(ep10.firstListenedAt)
    }

    @Test
    fun newSincePreviousSyncCountsOnlyNewerThanPrevious() = runBlocking {
        server.enqueue(MockResponse().setBody(fakeFeed))
        val firstResult = repo.syncFeed()
        // First run: prevSync is 0 → every episode counts as "new".
        assertEquals(2, firstResult.newSincePreviousSync)

        server.enqueue(MockResponse().setBody(fakeFeed))
        val secondResult = repo.syncFeed()
        // Second run: prevSync = clock = 1_700_000_000_000, all pubDates older.
        assertEquals(0, secondResult.newSincePreviousSync)
    }
}
