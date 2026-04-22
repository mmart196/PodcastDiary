package com.podcastdiary.data.feed

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ParsedEpisode(
    val guid: String,
    val episodeNumber: Int?,
    val title: String,
    val description: String,
    val pubDate: Long,
    val pageUrl: String,
    val audioUrl: String,
    val audioByteLength: Long,
    val durationMs: Long?,
    val category: String?,
)

class RssFeedParser {

    fun parse(xml: String): List<ParsedEpisode> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        val items = mutableListOf<ParsedEpisode>()
        var inItem = false
        var current = MutableItem()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    if (!inItem) {
                        if (name == "item") {
                            inItem = true
                            current = MutableItem()
                        }
                    } else {
                        when (name) {
                            "title" -> current.title = readText(parser)
                            "link" -> current.link = readText(parser)
                            "guid" -> current.guid = readText(parser)
                            "pubDate" -> current.pubDate = readText(parser)
                            "description" -> {
                                if (current.description.isEmpty()) {
                                    current.description = readText(parser)
                                }
                            }
                            "content:encoded" -> current.description = readText(parser)
                            "category" -> current.categories += readText(parser)
                            "enclosure" -> {
                                current.audioUrl = parser.getAttributeValue(null, "url").orEmpty()
                                current.audioLength =
                                    parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                                skip(parser)
                            }
                            "itunes:duration" -> current.itunesDuration = readText(parser)
                            "itunes:episode" -> current.itunesEpisode =
                                readText(parser).toIntOrNull()
                            else -> skip(parser)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (inItem && parser.name == "item") {
                        current.build()?.let { items += it }
                        inItem = false
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var done = false
        while (!done) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text ?: "")
                XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> done = true
            }
        }
        return sb.toString().trim()
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private class MutableItem {
        var title: String = ""
        var link: String = ""
        var guid: String = ""
        var pubDate: String = ""
        var description: String = ""
        val categories = mutableListOf<String>()
        var audioUrl: String = ""
        var audioLength: Long = 0L
        var itunesDuration: String = ""
        var itunesEpisode: Int? = null

        fun build(): ParsedEpisode? {
            val effectiveGuid = guid.ifBlank { audioUrl.ifBlank { link } }
            if (effectiveGuid.isBlank() || audioUrl.isBlank()) return null
            val ep = itunesEpisode ?: EPISODE_RE.find(title)?.groupValues?.get(1)?.toIntOrNull()
            val category = categories.firstOrNull { it.startsWith("USMLE", ignoreCase = true) }
                ?: categories.firstOrNull()
            return ParsedEpisode(
                guid = effectiveGuid,
                episodeNumber = ep,
                title = title,
                description = description,
                pubDate = parsePubDate(pubDate),
                pageUrl = link,
                audioUrl = audioUrl,
                audioByteLength = audioLength,
                durationMs = parseItunesDuration(itunesDuration),
                category = category,
            )
        }
    }

    companion object {
        private val EPISODE_RE = Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)

        private val RFC_822_FORMATS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "dd MMM yyyy HH:mm:ss Z",
        )

        fun parsePubDate(raw: String): Long {
            if (raw.isBlank()) return 0L
            for (pattern in RFC_822_FORMATS) {
                try {
                    val fmt = SimpleDateFormat(pattern, Locale.US)
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                    return fmt.parse(raw)?.time ?: continue
                } catch (_: Exception) {
                    // try next
                }
            }
            return 0L
        }

        fun parseItunesDuration(raw: String): Long? {
            if (raw.isBlank()) return null
            val parts = raw.split(":").mapNotNull { it.toIntOrNull() }
            val seconds = when (parts.size) {
                1 -> parts[0]
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> return null
            }
            return seconds * 1000L
        }
    }
}
