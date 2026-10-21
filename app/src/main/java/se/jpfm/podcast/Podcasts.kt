package se.jpfm.podcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class Podcast(
    val id: String,
    val title: String,
    val author: String?,
    val image: String?,
    val feedUrl: String,
    val description: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        author?.let { put("author", it) }
        image?.let { put("image", it) }
        put("feed", feedUrl)
        description?.let { put("desc", it) }
    }

    companion object {
        fun idFor(feedUrl: String) = "pod:" + sha1(feedUrl.trim()).take(16)

        fun fromJson(o: JSONObject?): Podcast? {
            o ?: return null
            val feed = o.optString("feed").ifBlank { return null }
            return Podcast(
                id = o.optString("id").ifBlank { idFor(feed) },
                title = o.optString("title").ifBlank { "Podd" },
                author = o.optString("author").ifBlank { null },
                image = o.optString("image").ifBlank { null },
                feedUrl = feed,
                description = o.optString("desc").ifBlank { null },
            )
        }
    }
}

data class Episode(
    val id: String,
    val podcastId: String,
    val podcastTitle: String,
    val title: String,
    val audioUrl: String,
    val image: String?,
    val publishedAt: Long,
    val durationMs: Long,
    val description: String? = null,
    val mimeType: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("pod", podcastId)
        put("podTitle", podcastTitle)
        put("title", title)
        put("url", audioUrl)
        image?.let { put("image", it) }
        put("date", publishedAt)
        put("dur", durationMs)
        description?.let { put("desc", it.take(4000)) }
        mimeType?.let { put("mime", it) }
    }

    companion object {
        fun fromJson(o: JSONObject?): Episode? {
            o ?: return null
            val id = o.optString("id").ifBlank { return null }
            val url = o.optString("url").ifBlank { return null }
            return Episode(
                id = id,
                podcastId = o.optString("pod"),
                podcastTitle = o.optString("podTitle"),
                title = o.optString("title").ifBlank { "Avsnitt" },
                audioUrl = url,
                image = o.optString("image").ifBlank { null },
                publishedAt = o.optLong("date"),
                durationMs = o.optLong("dur"),
                description = o.optString("desc").ifBlank { null },
                mimeType = o.optString("mime").ifBlank { null },
            )
        }
    }
}

/** Hur långt du lyssnat på ett avsnitt. */
data class Progress(val positionMs: Long, val durationMs: Long, val played: Boolean, val updatedAt: Long) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

internal fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** Tolkar RSS-flöden och sökresultat. Ingen Android, så det går att testa. */
object PodcastParse {
    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    fun rss(
        xml: String,
        feedUrl: String,
        parser: XmlPullParser = XmlPullParserFactory.newInstance().newPullParser(),
    ): Pair<Podcast, List<Episode>> {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        val podId = Podcast.idFor(feedUrl)
        var title: String? = null
        var author: String? = null
        var image: String? = null
        var description: String? = null
        val episodes = mutableListOf<Episode>()

        var inItem = false
        var inImage = false
        var cur = mutableMapOf<String, String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name
                val ns = parser.namespace.orEmpty()
                when {
                    name == "item" -> {
                        inItem = true
                        cur = mutableMapOf()
                    }
                    name == "image" && ns.isEmpty() && !inItem -> inImage = true
                    name == "enclosure" && inItem -> {
                        parser.getAttributeValue(null, "url")?.let { u -> if ("url" !in cur) cur["url"] = u }
                        parser.getAttributeValue(null, "type")?.let { t -> cur["type"] = t }
                    }
                    name == "image" && ns == ITUNES -> {
                        val href = parser.getAttributeValue(null, "href")
                        if (href != null) {
                            if (inItem) cur["image"] = href else if (image == null) image = href
                        }
                    }
                    inItem && ns.isEmpty() && name in setOf("title", "guid", "pubDate", "description") -> cur[name] = readText(parser)
                    inItem && ns == ITUNES && name in setOf("duration", "summary", "title") -> cur["itunes:$name"] = readText(parser)
                    !inItem && inImage && name == "url" -> { if (image == null) image = readText(parser) }
                    !inItem && ns.isEmpty() && name == "title" && title == null -> title = readText(parser)
                    !inItem && ns.isEmpty() && name == "description" && description == null -> description = readText(parser)
                    !inItem && ns == ITUNES && name == "author" && author == null -> author = readText(parser)
                    !inItem && ns == ITUNES && name == "summary" && description == null -> description = readText(parser)
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "item" -> {
                        inItem = false
                        val url = cur["url"]
                        if (url != null) {
                            val key = cur["guid"]?.trim()?.ifBlank { null } ?: url
                            episodes += Episode(
                                id = "ep:" + sha1("$podId|$key").take(20),
                                podcastId = podId,
                                podcastTitle = title.orEmpty(),
                                title = (cur["title"] ?: cur["itunes:title"]).orEmpty().trim().ifBlank { "Avsnitt" },
                                audioUrl = url.trim(),
                                image = cur["image"],
                                publishedAt = parseDate(cur["pubDate"]),
                                durationMs = parseDuration(cur["itunes:duration"]),
                                description = cleanHtml(cur["description"] ?: cur["itunes:summary"]),
                                mimeType = cur["type"],
                            )
                        }
                    }
                    "image" -> inImage = false
                }
            }
            event = parser.next()
        }
        val podcast = Podcast(podId, title?.trim().orEmpty().ifBlank { "Podd" }, author?.trim(), image?.trim(), feedUrl, cleanHtml(description))
        val eps = episodes.map { e -> e.copy(podcastTitle = podcast.title, image = e.image ?: podcast.image) }
            .sortedByDescending { it.publishedAt }
        return podcast to eps
    }

    /** Läser texten i ett element, även om det innehåller andra element. */
    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(p.text)
                XmlPullParser.ENTITY_REF -> sb.append(p.text ?: "")
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString().trim()
    }

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm zzz",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    fun parseDate(s: String?): Long {
        val t = s?.trim()?.ifBlank { null } ?: return 0L
        for (f in dateFormats) {
            runCatching { SimpleDateFormat(f, Locale.US).parse(t)?.time }.getOrNull()?.let { return it }
        }
        return 0L
    }

    /** "1:02:03", "62:03", "3723" eller "3723.5" → millisekunder. */
    fun parseDuration(s: String?): Long {
        val t = s?.trim()?.ifBlank { null } ?: return 0L
        val parts = t.split(':')
        return runCatching {
            when (parts.size) {
                1 -> (parts[0].toDouble() * 1000).toLong()
                2 -> (parts[0].toLong() * 60 + parts[1].toDouble().toLong()) * 1000
                else -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble().toLong()) * 1000
            }
        }.getOrDefault(0L)
    }

    /** Beskrivningar är ofta HTML: gör vanlig text av dem. */
    fun cleanHtml(s: String?): String? {
        val t = s?.ifBlank { null } ?: return null
        return t.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .ifBlank { null }
    }

    /** iTunes sök/lookup → poddar med RSS-adress. */
    fun itunes(root: JSONObject): List<Podcast> = itunesWithIds(root).map { it.second }

    /** Som [itunes], med Apples id för varje podd (för att behålla topplistans ordning). */
    fun itunesWithIds(root: JSONObject): List<Pair<String, Podcast>> {
        val arr = root.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val feed = o.optString("feedUrl").ifBlank { return@mapNotNull null }
            o.optLong("collectionId").toString() to Podcast(
                id = Podcast.idFor(feed),
                title = o.optString("collectionName").ifBlank { o.optString("trackName") }.ifBlank { "Podd" },
                author = o.optString("artistName").ifBlank { null },
                image = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.ifBlank { null },
                feedUrl = feed,
            )
        }
    }

    /** Apples topplista: id:n som sedan slås upp för att få RSS-adressen. */
    fun topIds(root: JSONObject): List<String> {
        val arr = root.optJSONObject("feed")?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.ifBlank { null } }
    }
}

/**
 * Poddar: sök (Apple Podcasts katalog), prenumerationer, avsnitt från RSS och hur långt du
 * lyssnat. Allt sparas på telefonen; inget konto behövs.
 */
class PodcastRepository(private val http: OkHttpClient, dir: File) {
    private val file = File(dir, "podcasts.json")
    private val lock = Any()

    private val _subscriptions = MutableStateFlow<List<Podcast>>(emptyList())
    val subscriptions: StateFlow<List<Podcast>> = _subscriptions.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    /** Senaste hämtade avsnitt per podd. */
    private val feeds = ConcurrentHashMap<String, Pair<Long, List<Episode>>>()
    private val episodes = ConcurrentHashMap<String, Episode>()
    private val podcasts = ConcurrentHashMap<String, Podcast>()

    @Volatile private var top: Pair<Long, List<Podcast>>? = null

    init {
        load()
    }

    fun podcast(id: String): Podcast? = podcasts[id]
    fun episode(id: String): Episode? = episodes[id]
    fun isSubscribed(id: String): Boolean = _subscriptions.value.any { it.id == id }
    fun cachedEpisodes(podcastId: String): List<Episode> = feeds[podcastId]?.second.orEmpty()

    fun remember(p: Podcast) {
        podcasts[p.id] = p
    }

    fun toggleSubscription(p: Podcast) {
        synchronized(lock) {
            val list = _subscriptions.value
            _subscriptions.value = if (list.any { it.id == p.id }) list.filter { it.id != p.id } else listOf(p) + list
            podcasts[p.id] = p
        }
        save()
    }

    // ---------- Hämta ----------

    suspend fun feed(p: Podcast, force: Boolean = false): List<Episode> {
        val c = feeds[p.id]
        if (!force && c != null && System.currentTimeMillis() - c.first < HOUR) return c.second
        return try {
            val xml = get(p.feedUrl)
            val (pod, eps) = withContext(Dispatchers.Default) { PodcastParse.rss(xml, p.feedUrl) }
            val merged = pod.copy(image = pod.image ?: p.image, author = pod.author ?: p.author)
            podcasts[p.id] = merged
            val list = eps.take(MAX_EPISODES)
            feeds[p.id] = System.currentTimeMillis() to list
            list.forEach { episodes[it.id] = it }
            // Prenumerationen får den senaste titeln och bilden.
            if (isSubscribed(p.id)) {
                synchronized(lock) { _subscriptions.value = _subscriptions.value.map { if (it.id == p.id) merged else it } }
            }
            save()
            list
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            c?.second ?: throw IOException("Kunde inte hämta podden: ${e.message}")
        }
    }

    /** Nya avsnitt från alla prenumerationer, nyast först. */
    suspend fun latest(force: Boolean = false): List<Episode> = coroutineScope {
        _subscriptions.value.map { p -> async { runCatching { feed(p, force) }.getOrDefault(cachedEpisodes(p.id)).take(10) } }
            .awaitAll()
            .flatten()
            .sortedByDescending { it.publishedAt }
            .take(60)
    }

    suspend fun search(query: String): List<Podcast> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val list = PodcastParse.itunes(JSONObject(get("https://itunes.apple.com/search?media=podcast&entity=podcast&country=se&limit=30&term=$q")))
        list.forEach { podcasts[it.id] = it }
        return list
    }

    /** Topplistan i Sverige. */
    suspend fun topList(): List<Podcast> {
        top?.let { (at, l) -> if (System.currentTimeMillis() - at < 6 * HOUR && l.isNotEmpty()) return l }
        val ids = PodcastParse.topIds(JSONObject(get("https://rss.applemarketingtools.com/api/v2/se/podcasts/top/30/podcasts.json")))
        if (ids.isEmpty()) return emptyList()
        val found = PodcastParse.itunesWithIds(JSONObject(get("https://itunes.apple.com/lookup?entity=podcast&id=${ids.joinToString(",")}")))
        // Behåll topplistans ordning.
        val ordered = found.sortedBy { (id, _) -> ids.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }.map { it.second }
        ordered.forEach { podcasts[it.id] = it }
        top = System.currentTimeMillis() to ordered
        return ordered
    }

    /** Lägg till en podd med RSS-adress. */
    suspend fun fromFeed(url: String): Podcast {
        val xml = get(url.trim())
        val (pod, eps) = withContext(Dispatchers.Default) { PodcastParse.rss(xml, url.trim()) }
        podcasts[pod.id] = pod
        feeds[pod.id] = System.currentTimeMillis() to eps.take(MAX_EPISODES)
        eps.forEach { episodes[it.id] = it }
        return pod
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "JPFM/1.0 (Android podcast player)").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    // ---------- Hur långt du lyssnat ----------

    fun progressOf(id: String): Progress? = _progress.value[id]

    /** Var avsnittet ska börja: där du slutade, eller från början om det är (nästan) klart. */
    fun startPosition(e: Episode): Long {
        val p = progressOf(e.id) ?: return 0L
        if (p.played) return 0L
        val dur = if (p.durationMs > 0) p.durationMs else e.durationMs
        return if (dur > 0 && p.positionMs > dur - 30_000) 0L else (p.positionMs - 3_000).coerceAtLeast(0)
    }

    fun saveProgress(e: Episode, positionMs: Long, durationMs: Long) {
        val dur = if (durationMs > 0) durationMs else e.durationMs
        val played = dur > 0 && positionMs >= dur * 0.95
        episodes[e.id] = e
        synchronized(lock) {
            _progress.value = _progress.value + (e.id to Progress(positionMs, dur, played, System.currentTimeMillis()))
        }
        saveSoon()
    }

    fun setPlayed(e: Episode, played: Boolean) {
        episodes[e.id] = e
        synchronized(lock) {
            _progress.value = _progress.value + (e.id to Progress(0, e.durationMs, played, System.currentTimeMillis()))
        }
        save()
    }

    // ---------- Lagring ----------

    @Volatile private var lastSave = 0L

    private fun saveSoon() {
        if (System.currentTimeMillis() - lastSave > 15_000) save()
    }

    fun save() {
        lastSave = System.currentTimeMillis()
        runCatching {
            val o = synchronized(lock) {
                JSONObject().apply {
                    put("subs", JSONArray().apply { _subscriptions.value.forEach { put(it.toJson()) } })
                    put("progress", JSONObject().apply {
                        // Bara de senaste 1000, så att filen inte växer för evigt.
                        _progress.value.entries.sortedByDescending { it.value.updatedAt }.take(1000).forEach { (k, v) ->
                            put(k, JSONObject().put("p", v.positionMs).put("d", v.durationMs).put("x", v.played).put("t", v.updatedAt))
                        }
                    })
                    // Avsnitt vi behöver kunna visa offline (påbörjade och prenumererade poddars senaste).
                    val keep = (_progress.value.keys + _subscriptions.value.flatMap { s -> cachedEpisodes(s.id).take(20).map { it.id } }).toSet()
                    put("episodes", JSONArray().apply { keep.mapNotNull { episodes[it] }.forEach { put(it.toJson()) } })
                }
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    private fun load() {
        runCatching {
            val o = JSONObject(file.readText())
            val subs = o.optJSONArray("subs")?.let { a -> (0 until a.length()).mapNotNull { Podcast.fromJson(a.optJSONObject(it)) } }.orEmpty()
            _subscriptions.value = subs
            subs.forEach { podcasts[it.id] = it }
            val prog = HashMap<String, Progress>()
            o.optJSONObject("progress")?.let { p ->
                p.keys().forEach { k ->
                    val v = p.optJSONObject(k) ?: return@forEach
                    prog[k] = Progress(v.optLong("p"), v.optLong("d"), v.optBoolean("x"), v.optLong("t"))
                }
            }
            _progress.value = prog
            o.optJSONArray("episodes")?.let { a ->
                val eps = (0 until a.length()).mapNotNull { Episode.fromJson(a.optJSONObject(it)) }
                eps.forEach { episodes[it.id] = it }
                // Senaste avsnitt per podd, så att listan finns direkt (och offline).
                eps.groupBy { it.podcastId }.forEach { (pid, l) -> feeds[pid] = 0L to l.sortedByDescending { it.publishedAt } }
            }
        }
    }

    companion object {
        private const val HOUR = 60 * 60 * 1000L
        private const val MAX_EPISODES = 300
    }
}
