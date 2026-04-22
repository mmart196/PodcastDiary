package com.podcastdiary.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RssFeedParserTest {

    private fun sample(): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("feed_sample.xml"))
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun parsesAllItemsWithAudioAndEpisodeNumbers() {
        val items = RssFeedParser().parse(sample())
        assertEquals(3, items.size)
        items.forEach {
            assertTrue("audio url non-empty for ${it.title}", it.audioUrl.isNotBlank())
            assertNotNull("episode number for ${it.title}", it.episodeNumber)
        }
        val byNumber = items.associateBy { it.episodeNumber }
        assertEquals(648, byNumber.keys.max())
    }

    @Test
    fun parsesItunesDurationFormats() {
        val items = RssFeedParser().parse(sample())
        val ep648 = items.single { it.episodeNumber == 648 }
        assertEquals(18L * 60 * 1000 + 42 * 1000, ep648.durationMs)
        val ep647 = items.single { it.episodeNumber == 647 }
        assertEquals((1 * 3600 + 2 * 60 + 10) * 1000L, ep647.durationMs)
        val ep646 = items.single { it.episodeNumber == 646 }
        assertNull(ep646.durationMs)
    }

    @Test
    fun parsesCategoryAndPubDateEpochMs() {
        val items = RssFeedParser().parse(sample())
        val ep648 = items.single { it.episodeNumber == 648 }
        assertEquals("USMLE Step 2 CK", ep648.category)
        assertTrue(ep648.pubDate > 0)
    }

    @Test
    fun readsAudioLengthAttribute() {
        val items = RssFeedParser().parse(sample())
        assertEquals(12345678L, items.single { it.episodeNumber == 648 }.audioByteLength)
    }

    @Test
    fun itunesDurationStandalone() {
        assertEquals(90L * 1000, RssFeedParser.parseItunesDuration("90"))
        assertEquals(0L, RssFeedParser.parseItunesDuration("0"))
        assertEquals(null, RssFeedParser.parseItunesDuration(""))
        assertEquals((1 * 3600 + 30 * 60) * 1000L, RssFeedParser.parseItunesDuration("1:30:00"))
    }
}
