package se.jpfm.lyrics

import org.json.JSONArray
import org.json.JSONObject

data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>) {
    /** True när raderna har tider, så att texten kan följa med musiken. */
    val synced: Boolean get() = lines.isNotEmpty() && lines.all { it.timeMs != null }

    /** Raden som sjungs vid [positionMs], eller -1 före första raden. */
    fun indexAt(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs!! <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    companion object {
        val EMPTY = Lyrics(emptyList())

        /**
         * Tolkar låttext från Plex. Servern kan svara med LRC ("[01:02.50]text"), vanlig text,
         * eller JSON/XML med rader ("Line" med "startOffset" och "Span"), beroende på källa och version.
         */
        fun parse(raw: String): Lyrics {
            val text = raw.trim().removePrefix("﻿")
            if (text.isEmpty()) return EMPTY
            return when {
                text.startsWith("{") -> runCatching { fromJson(JSONObject(text)) }.getOrNull() ?: EMPTY
                text.startsWith("<") -> fromXml(text)
                else -> fromLrc(text)
            }.let { l -> Lyrics(l.lines.dropLastWhile { it.text.isBlank() }) }
        }

        private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val metaTag = Regex("""^\[([a-zA-Z]+):(.*)]$""")

        fun fromLrc(text: String): Lyrics {
            var offset = 0L
            val timed = mutableListOf<LyricLine>()
            val plain = mutableListOf<LyricLine>()
            for (rawLine in text.lines()) {
                val line = rawLine.trimEnd()
                val tags = timeTag.findAll(line).toList()
                if (tags.isEmpty()) {
                    val meta = metaTag.matchEntire(line.trim())
                    if (meta != null) {
                        if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                            offset = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                        }
                        continue
                    }
                    plain += LyricLine(null, line.trim())
                    continue
                }
                // Text efter sista tidsmärket. En rad kan ha flera tider ("[00:10][01:20]Refräng").
                val lyric = line.substring(tags.last().range.last + 1).trim()
                for (t in tags) {
                    val min = t.groupValues[1].toLong()
                    val sec = t.groupValues[2].toLong()
                    val fracRaw = t.groupValues[3]
                    val frac = when (fracRaw.length) {
                        0 -> 0L
                        1 -> fracRaw.toLong() * 100
                        2 -> fracRaw.toLong() * 10
                        else -> fracRaw.take(3).toLong()
                    }
                    // LRC-offset: positivt värde betyder att texten ska visas tidigare.
                    val ms = (min * 60_000 + sec * 1000 + frac - offset).coerceAtLeast(0)
                    timed += LyricLine(ms, lyric)
                }
            }
            return if (timed.isNotEmpty()) Lyrics(timed.sortedBy { it.timeMs }) else Lyrics(trimBlank(plain))
        }

        private fun fromJson(root: JSONObject): Lyrics {
            val mc = root.optJSONObject("MediaContainer") ?: root
            val lyricsArr = when (val l = mc.opt("Lyrics")) {
                is JSONArray -> (0 until l.length()).mapNotNull { l.optJSONObject(it) }
                is JSONObject -> listOf(l)
                else -> emptyList()
            }
            val out = mutableListOf<LyricLine>()
            for (ly in lyricsArr) {
                val linesArr = when (val ln = ly.opt("Line")) {
                    is JSONArray -> (0 until ln.length()).mapNotNull { ln.optJSONObject(it) }
                    is JSONObject -> listOf(ln)
                    else -> emptyList()
                }
                if (linesArr.isEmpty()) {
                    // Hela texten i ett fält.
                    val t = ly.optString("text").ifBlank { ly.optString("value") }
                    if (t.isNotBlank()) return fromLrc(t)
                }
                for (ln in linesArr) {
                    val spans = when (val s = ln.opt("Span")) {
                        is JSONArray -> (0 until s.length()).mapNotNull { s.optJSONObject(it) }
                        is JSONObject -> listOf(s)
                        else -> emptyList()
                    }
                    val txt = if (spans.isNotEmpty()) spans.joinToString("") { it.optString("text") } else ln.optString("text")
                    val start = if (ln.has("startOffset")) ln.optLong("startOffset") else null
                    out += LyricLine(start, txt.trim())
                }
            }
            return normalize(out)
        }

        private val xmlLine = Regex("""<Line\b([^>]*)>(.*?)</Line>|<Line\b([^>]*)/>""", RegexOption.DOT_MATCHES_ALL)
        private val xmlSpan = Regex("""<Span\b[^>]*\btext="([^"]*)"""")
        private val xmlStart = Regex("""\bstartOffset="(\d+)"""")

        private fun fromXml(text: String): Lyrics {
            val out = mutableListOf<LyricLine>()
            for (m in xmlLine.findAll(text)) {
                val attrs = m.groupValues[1].ifEmpty { m.groupValues[3] }
                val body = m.groupValues[2]
                val start = xmlStart.find(attrs)?.groupValues?.get(1)?.toLongOrNull()
                val words = xmlSpan.findAll(body).joinToString("") { unescape(it.groupValues[1]) }
                out += LyricLine(start, words.trim())
            }
            if (out.isEmpty()) {
                // Okänt format: ta bort taggarna och visa texten.
                return fromLrc(unescape(text.replace(Regex("<[^>]+>"), "\n")))
            }
            return normalize(out)
        }

        private fun normalize(lines: List<LyricLine>): Lyrics {
            if (lines.isEmpty()) return EMPTY
            val allTimed = lines.all { it.timeMs != null }
            return if (allTimed) Lyrics(lines.sortedBy { it.timeMs }) else Lyrics(trimBlank(lines.map { LyricLine(null, it.text) }))
        }

        private fun trimBlank(lines: List<LyricLine>): List<LyricLine> =
            lines.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }

        private fun unescape(s: String): String = s
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
