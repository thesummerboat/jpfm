package se.jpfm.covers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import se.jpfm.plex.Library
import se.jpfm.plex.Track
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Riktiga omslag för låtar på samlingsskivor ("Top 100 Hits", "Various Artists" …): vi letar
 * upp originalsingeln eller originalalbumet och visar det omslaget i stället. Först i
 * MusicBrainz/Cover Art Archive (öppet, utan spårning), sedan Apple som reserv.
 */
class CoverRepository(base: OkHttpClient, dir: File, private val scope: CoroutineScope) {
    private val http = base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val noRedirects = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val file = File(dir, "covers.json")
    private val lock = Any()

    /** ratingKey → omslagsadress ("" = letat men inget hittat), och när vi letade. */
    private val known = HashMap<String, Pair<String, Long>>()
    private val _found = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Låtar som har ett riktigt omslag: ratingKey → bildadress. */
    val found: StateFlow<Map<String, String>> = _found.asStateFlow()

    private val wanted = LinkedHashMap<String, Track>()
    private val worker = Mutex()
    private val saveLock = Mutex()
    @Volatile private var lastMusicBrainz = 0L

    @Volatile var enabled: Boolean = true

    init {
        load()
    }

    /** Omslaget om vi redan hittat ett. */
    fun peek(ratingKey: String): String? = _found.value[ratingKey]

    /** Vill ha omslag till de här låtarna (de som ligger på samlingar), i den ordningen. */
    fun want(tracks: List<Track>, first: Boolean = false) {
        if (!enabled) return
        val add = tracks.filter { CoverMatch.isCompilation(it) && needsLookup(it.ratingKey) }
        if (add.isEmpty()) return
        synchronized(lock) {
            if (first) {
                // Det som spelas nu går före allt annat.
                val rest = LinkedHashMap(wanted)
                wanted.clear()
                add.forEach { wanted[it.ratingKey] = it }
                rest.forEach { (k, v) -> wanted.putIfAbsent(k, v) }
            } else {
                add.forEach { wanted.putIfAbsent(it.ratingKey, it) }
            }
        }
        if (!worker.isLocked) scope.launch { work() }
    }

    fun want(track: Track) = want(listOf(track))

    private fun needsLookup(key: String): Boolean {
        val k = synchronized(lock) { known[key] } ?: return true
        return k.first.isEmpty() && System.currentTimeMillis() - k.second > RETRY_AFTER
    }

    private suspend fun work() {
        if (!worker.tryLock()) return
        try {
            while (true) {
                val next = synchronized(lock) {
                    val e = wanted.entries.firstOrNull() ?: return@synchronized null
                    wanted.remove(e.key)
                    e.value
                } ?: break
                if (!needsLookup(next.ratingKey)) continue
                val url = try {
                    find(next)
                } catch (e: IOException) {
                    // Inget nät just nu: försök igen nästa gång låten visas.
                    continue
                }
                remember(next.ratingKey, url.orEmpty())
            }
        } finally {
            worker.unlock()
        }
    }

    private fun remember(key: String, url: String) {
        synchronized(lock) {
            known[key] = url to System.currentTimeMillis()
            if (url.isNotEmpty()) _found.value = _found.value + (key to url)
        }
        save()
    }

    /** Glöm alla omslag (t.ex. om de blev fel). */
    fun clear() {
        synchronized(lock) {
            known.clear()
            wanted.clear()
            _found.value = emptyMap()
        }
        save()
    }

    // ---------- Leta ----------

    private suspend fun find(t: Track): String? {
        val artist = CoverMatch.primaryArtist(t.displayArtist)
        val title = CoverMatch.cleanTitle(t.title)
        if (artist.isBlank() || title.isBlank()) return null
        musicBrainz(artist, title)?.let { return it }
        return apple(artist, title)
    }

    private suspend fun musicBrainz(artist: String, title: String): String? {
        val q = "recording:\"${CoverMatch.lucene(title)}\" AND artist:\"${CoverMatch.lucene(artist)}\""
        val body = mbGet("https://musicbrainz.org/ws/2/recording?fmt=json&limit=15&query=" + URLEncoder.encode(q, "UTF-8")) ?: return null
        val groups = withContext(Dispatchers.Default) { CoverMatch.releaseGroups(JSONObject(body), artist, title) }
        for (rg in groups.take(4)) {
            val url = "https://coverartarchive.org/release-group/$rg/front-500"
            if (exists(url)) return url
        }
        return null
    }

    private suspend fun apple(artist: String, title: String): String? {
        val term = URLEncoder.encode("$artist $title", "UTF-8")
        val body = get("https://itunes.apple.com/search?media=music&entity=song&limit=10&country=se&term=$term") ?: return null
        return withContext(Dispatchers.Default) { CoverMatch.appleArtwork(JSONObject(body), artist, title) }
    }

    /** MusicBrainz vill ha högst en fråga i sekunden. */
    private suspend fun mbGet(url: String): String? {
        val wait = lastMusicBrainz + 1100 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastMusicBrainz = System.currentTimeMillis()
        return get(url)
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()).execute().use { r ->
            when {
                r.isSuccessful -> r.body?.string()
                r.code == 503 || r.code == 429 -> throw IOException("Upptagen")
                else -> null
            }
        }
    }

    private suspend fun exists(url: String): Boolean = withContext(Dispatchers.IO) {
        noRedirects.newCall(Request.Builder().url(url).head().header("User-Agent", USER_AGENT).build()).execute().use { r ->
            r.isSuccessful || r.isRedirect
        }
    }

    // ---------- Spara ----------

    private fun load() {
        runCatching {
            val o = JSONObject(file.readText())
            val found = HashMap<String, String>()
            o.keys().forEach { k ->
                val e = o.optJSONObject(k) ?: return@forEach
                val u = e.optString("u")
                known[k] = u to e.optLong("t")
                if (u.isNotEmpty()) found[k] = u
            }
            _found.value = found
        }
    }

    private fun save() {
        val snapshot = synchronized(lock) { HashMap(known) }
        scope.launch(Dispatchers.IO) {
            saveLock.withLock {
                runCatching {
                    val o = JSONObject()
                    snapshot.forEach { (k, v) -> o.put(k, JSONObject().put("u", v.first).put("t", v.second)) }
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(o.toString())
                    tmp.renameTo(file)
                }
            }
        }
    }

    companion object {
        private const val RETRY_AFTER = 30L * 24 * 3600 * 1000
        private const val USER_AGENT = "JPFM/1.0 ( https://github.com/thesummerboat/jpfm )"
    }
}

/** Vad som är en samling och vilken utgåva som är originalet. Ren logik, går att testa. */
object CoverMatch {
    private val VARIOUS = setOf(
        "various artists", "various", "va", "v.a.", "v/a", "diverse artister", "blandade artister", "div. artister",
        "diverse", "verschiedene interpreten", "artistes divers", "varios artistas", "sampler",
    )

    /** Titlar som nästan alltid är samlingar av olika artister. */
    private val STRONG = Regex(
        """(?i)\b(top\s?\d+|\d+\s+(hits|låtar|songs|tracks|classics)|now\s?(that'?s what|\d+)|bravo hits|absolute\s+\w+|ministry of sound|hitlist|the hits|hits of (the )?\d{2,4}|\d{4} hits|svensktoppen|various)\b""",
    )

    /** Titlar som kan vara samlingar om låtarna har olika artister. */
    private val WEAK = Regex("""(?i)\b(hits|collection|samling|anthems|party|summer|sommar|best of|greatest|ultimate|essential|mix|classics|gold)\b""")

    fun isCompilation(t: Track): Boolean {
        if (t.artist.trim().lowercase() in VARIOUS) return true
        if (STRONG.containsMatchIn(t.album)) return true
        return t.trackArtist != null && WEAK.containsMatchIn(t.album)
    }

    /** "Avicii feat. Aloe Blacc" → "Avicii". */
    fun primaryArtist(a: String): String =
        a.split(Regex("""(?i)\s(feat\.?|ft\.?|featuring|&|x|med|with|vs\.?)\s|,""")).first().trim()

    /** "Wake Me Up (Radio Edit)" → "Wake Me Up". */
    fun cleanTitle(t: String): String =
        t.replace(Regex("""\s*[(\[][^)\]]*(edit|mix|version|remaster|remastered|radio|original|feat|ft\.)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+-\s+(radio edit|single version|remaster(ed)?.*)$""", RegexOption.IGNORE_CASE), "")
            .trim()

    /** Citattecken och backslash får inte förstöra MusicBrainz sökfråga. */
    fun lucene(s: String): String = s.replace("\\", " ").replace("\"", " ").trim()

    /**
     * Ur MusicBrainz svar: release-grupper där låten finns i original (singel, album eller EP,
     * inte samlingar eller livealbum), äldst först.
     */
    fun releaseGroups(root: JSONObject, artist: String, title: String): List<String> {
        val wantArtist = Library.norm(artist)
        val wantTitle = Library.norm(title)
        val candidates = mutableListOf<Pair<String, String>>() // datum, rg-id
        val recordings = root.optJSONArray("recordings") ?: return emptyList()
        for (i in 0 until recordings.length()) {
            val rec = recordings.optJSONObject(i) ?: continue
            if (rec.optInt("score") < 80) continue
            val recTitle = Library.norm(rec.optString("title"))
            if (recTitle != wantTitle && !(recTitle.startsWith(wantTitle) && wantTitle.length >= 4)) continue
            val credit = credits(rec.optJSONArray("artist-credit"))
            if (credit.none { val c = Library.norm(it); c == wantArtist || (c.isNotEmpty() && (c.contains(wantArtist) || wantArtist.contains(c))) }) continue
            val releases = rec.optJSONArray("releases") ?: continue
            for (j in 0 until releases.length()) {
                val rel = releases.optJSONObject(j) ?: continue
                val status = rel.optString("status")
                if (status.isNotEmpty() && !status.equals("Official", true)) continue
                val rg = rel.optJSONObject("release-group") ?: continue
                val type = rg.optString("primary-type")
                if (type !in setOf("Single", "Album", "EP")) continue
                val secondary = rg.optJSONArray("secondary-types")
                if (secondary != null && secondary.length() > 0) continue
                val id = rg.optString("id")
                if (id.isBlank()) continue
                val date = rel.optString("date").ifBlank { "9999" }
                candidates += date to id
            }
        }
        return candidates.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun credits(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.flatMap {
            listOfNotNull(it.optString("name").ifBlank { null }, it.optJSONObject("artist")?.optString("name")?.ifBlank { null })
        }
    }

    /** Ur Apples sökning: omslaget till en utgåva som inte är en samling, i 600 × 600. */
    fun appleArtwork(root: JSONObject, artist: String, title: String): String? {
        val wantArtist = Library.norm(artist)
        val wantTitle = Library.norm(title)
        val results = root.optJSONArray("results") ?: return null
        val hits = (0 until results.length()).mapNotNull { results.optJSONObject(it) }.filter { o ->
            val a = Library.norm(o.optString("artistName"))
            val t = Library.norm(cleanTitle(o.optString("trackName")))
            (a == wantArtist || a.contains(wantArtist) || wantArtist.contains(a) && a.isNotEmpty()) &&
                (t == wantTitle || (t.startsWith(wantTitle) && wantTitle.length >= 4))
        }
        val best = hits.firstOrNull { o ->
            val c = o.optString("collectionName")
            !STRONG.containsMatchIn(c) && !o.optString("collectionArtistName").equals("Various Artists", true)
        } ?: return null
        return best.optString("artworkUrl100").ifBlank { null }?.replace("100x100bb", "600x600bb")
    }
}
