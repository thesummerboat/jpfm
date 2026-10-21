package se.jpfm.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** En radiokanal som sänder live. */
data class RadioStation(
    /** "sr:164" för Sveriges Radio, "rb:<uuid>" från Radio Browser. */
    val id: String,
    val name: String,
    val streamUrl: String,
    val logo: String? = null,
    val tagline: String? = null,
    /** Kanalens färg som ARGB, om den har en. */
    val color: Int? = null,
    val hls: Boolean = false,
    val codec: String? = null,
    val bitrate: Int? = null,
    val tags: List<String> = emptyList(),
    /** Grupp för Sveriges Radio: "Rikskanal", "Lokal kanal" … */
    val group: String? = null,
    /** Reservadress om huvudadressen inte fungerar. */
    val altUrl: String? = null,
) {
    val srChannelId: Int? get() = if (id.startsWith("sr:")) id.removePrefix("sr:").toIntOrNull() else null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", streamUrl)
        logo?.let { put("logo", it) }
        tagline?.let { put("tagline", it) }
        color?.let { put("color", it) }
        put("hls", hls)
        codec?.let { put("codec", it) }
        bitrate?.let { put("bitrate", it) }
        if (tags.isNotEmpty()) put("tags", JSONArray(tags))
        group?.let { put("group", it) }
        altUrl?.let { put("alt", it) }
    }

    companion object {
        fun fromJson(o: JSONObject?): RadioStation? {
            o ?: return null
            val id = o.optString("id").ifBlank { return null }
            val url = o.optString("url").ifBlank { return null }
            return RadioStation(
                id = id,
                name = o.optString("name").ifBlank { "Radio" },
                streamUrl = url,
                logo = o.optString("logo").ifBlank { null },
                tagline = o.optString("tagline").ifBlank { null },
                color = if (o.has("color")) o.optInt("color") else null,
                hls = o.optBoolean("hls"),
                codec = o.optString("codec").ifBlank { null },
                bitrate = if (o.has("bitrate")) o.optInt("bitrate") else null,
                tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                group = o.optString("group").ifBlank { null },
                altUrl = o.optString("alt").ifBlank { null },
            )
        }
    }
}

/** Vad som sänds just nu. */
data class NowOnAir(val title: String?, val artist: String?, val program: String?)

/** Tolkning av svaren från Sveriges Radio och Radio Browser. Ingen Android, så det går att testa. */
object RadioParse {

    /** api.sr.se/api/v2/channels → kanaler. Rikskanalerna först, sedan lokala P4 i bokstavsordning. */
    fun srChannels(root: JSONObject): List<RadioStation> {
        val arr = root.optJSONArray("channels") ?: return emptyList()
        val list = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = c.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val name = c.optString("name").ifBlank { return@mapNotNull null }
            val live = c.optJSONObject("liveaudio")?.optString("url")?.ifBlank { null }
            RadioStation(
                id = "sr:$id",
                name = name,
                // AAC i hög kvalitet; servern skickar vidare till närmaste kantserver.
                streamUrl = srStreamUrl(id, live),
                altUrl = live,
                logo = c.optString("image").ifBlank { null } ?: c.optString("imagetemplate").ifBlank { null },
                tagline = c.optString("tagline").ifBlank { null },
                color = parseColor(c.optString("color")),
                group = c.optString("channeltype").ifBlank { null },
            )
        }
        val order = listOf("Rikskanal", "Lokal kanal", "Minoritet och språk", "Extrakanaler", "Fler kanaler")
        return list.sortedWith(
            compareBy<RadioStation>({ order.indexOf(it.group).let { i -> if (i < 0) order.size else i } }, { rikskanalOrder(it.name) }, { it.name }),
        )
    }

    private fun rikskanalOrder(name: String): Int = when {
        name.startsWith("P1") -> 0
        name.startsWith("P2") -> 1
        name.startsWith("P3") -> 2
        name.startsWith("P4") -> 3
        else -> 4
    }

    fun srStreamUrl(id: Int, fallback: String?): String =
        // Sveriges Radios dokumenterade adress; skickar vidare till närmaste server (https).
        "http://sverigesradio.se/topsy/direkt/srapi/$id-hi-aac-http".takeIf { id > 0 } ?: fallback.orEmpty()

    fun parseColor(hex: String?): Int? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6) return null
        return (0xFF000000L or (h.toLongOrNull(16) ?: return null)).toInt()
    }

    /** Radio Browser: lista med kanaler. */
    fun radioBrowser(arr: JSONArray): List<RadioStation> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val uuid = o.optString("stationuuid").ifBlank { return@mapNotNull null }
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.ifBlank { return@mapNotNull null }
            if (o.has("lastcheckok") && o.optInt("lastcheckok", 1) == 0) return@mapNotNull null
            val tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(4)
            RadioStation(
                id = "rb:$uuid",
                name = o.optString("name").trim().ifBlank { "Radio" },
                streamUrl = url,
                logo = o.optString("favicon").ifBlank { null }?.takeIf { it.startsWith("http") },
                tagline = tags.take(3).joinToString(" · ").ifBlank { null },
                hls = o.optInt("hls", 0) == 1 || url.contains(".m3u8"),
                codec = o.optString("codec").ifBlank { null },
                bitrate = o.optInt("bitrate", 0).takeIf { it > 0 },
                tags = tags,
            )
        }.distinctBy { it.name.lowercase() to it.streamUrl }

    /** SR: låten som spelas (playlists/rightnow) och programmet (scheduledepisodes/rightnow). */
    fun srNow(playlist: JSONObject?, schedule: JSONObject?, channelId: Int): NowOnAir {
        val pl = playlist?.optJSONObject("playlist")
            ?: playlist?.optJSONArray("channels")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optInt("id") == channelId }
                    ?.optJSONObject("playlists")?.optJSONObject("playlist")
            }
        val song = when (val s = pl?.opt("song")) {
            is JSONObject -> s
            is JSONArray -> s.optJSONObject(0)
            else -> null
        }
        val ep = schedule?.optJSONObject("channel")?.optJSONObject("currentscheduledepisode")
        return NowOnAir(
            title = song?.optString("title")?.ifBlank { null },
            artist = song?.optString("artist")?.ifBlank { null },
            program = ep?.optString("title")?.ifBlank { null } ?: ep?.optJSONObject("program")?.optString("name")?.ifBlank { null },
        )
    }

    /** Strömmens titel ("Artist - Låt") delad i artist och låt. */
    fun splitStreamTitle(raw: String?): Pair<String?, String?> {
        val t = raw?.trim()?.trim('\'', '"')?.ifBlank { null } ?: return null to null
        val idx = t.indexOf(" - ")
        return if (idx > 0) t.substring(0, idx).trim() to t.substring(idx + 3).trim() else null to t
    }
}

/**
 * Radiokanaler: Sveriges Radio (öppet API) och Radio Browser (öppen databas med kanaler från
 * hela världen). Favoriterna sparas på telefonen.
 */
class RadioRepository(private val http: OkHttpClient, dir: File) {
    private val favFile = File(dir, "radio-favorites.json")
    private val cacheFile = File(dir, "radio-cache.json")

    private val _favorites = MutableStateFlow(loadFavorites())
    val favorites: StateFlow<List<RadioStation>> = _favorites.asStateFlow()

    @Volatile private var srCache: Pair<Long, List<RadioStation>>? = null
    @Volatile private var seCache: Pair<Long, List<RadioStation>>? = null
    private val known = java.util.concurrent.ConcurrentHashMap<String, RadioStation>()

    init {
        _favorites.value.forEach { known[it.id] = it }
        loadCache()
    }

    fun station(id: String): RadioStation? = known[id]

    fun isFavorite(id: String): Boolean = _favorites.value.any { it.id == id }

    fun toggleFavorite(s: RadioStation) {
        val list = _favorites.value
        _favorites.value = if (list.any { it.id == s.id }) list.filter { it.id != s.id } else list + s
        known[s.id] = s
        saveFavorites()
    }

    fun moveFavorite(from: Int, to: Int) {
        val list = _favorites.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _favorites.value = list
        saveFavorites()
    }

    // ---------- Sveriges Radio ----------

    suspend fun srChannels(force: Boolean = false): List<RadioStation> {
        val c = srCache
        if (!force && c != null && System.currentTimeMillis() - c.first < DAY && c.second.isNotEmpty()) return c.second
        return try {
            val list = RadioParse.srChannels(JSONObject(get("https://api.sr.se/api/v2/channels?format=json&pagination=false")))
            if (list.isNotEmpty()) {
                srCache = System.currentTimeMillis() to list
                list.forEach { known[it.id] = it }
                saveCache()
            }
            list
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            c?.second ?: FALLBACK_SR
        }
    }

    suspend fun srNow(channelId: Int): NowOnAir? = runCatching {
        val pl = runCatching { JSONObject(get("https://api.sr.se/api/v2/playlists/rightnow?channelid=$channelId&format=json")) }.getOrNull()
        val sc = runCatching { JSONObject(get("https://api.sr.se/api/v2/scheduledepisodes/rightnow?channelid=$channelId&format=json")) }.getOrNull()
        RadioParse.srNow(pl, sc, channelId)
    }.getOrNull()

    // ---------- Radio Browser ----------

    suspend fun popularInSweden(force: Boolean = false): List<RadioStation> {
        val c = seCache
        if (!force && c != null && System.currentTimeMillis() - c.first < DAY && c.second.isNotEmpty()) return c.second
        return try {
            val list = RadioParse.radioBrowser(
                JSONArray(rb("/json/stations/bycountrycodeexact/SE?hidebroken=true&order=clickcount&reverse=true&limit=80")),
            )
                // Sveriges Radio har vi redan direkt från SR.
                .filterNot { s -> s.name.matches(Regex("""(?i)^(sr\s*)?p[1-4]\b.*""")) || s.name.contains("Sveriges Radio", true) }
                .take(50)
            if (list.isNotEmpty()) {
                seCache = System.currentTimeMillis() to list
                list.forEach { known[it.id] = it }
                saveCache()
            }
            list
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            c?.second ?: emptyList()
        }
    }

    suspend fun search(query: String): List<RadioStation> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val list = RadioParse.radioBrowser(JSONArray(rb("/json/stations/search?name=$q&hidebroken=true&order=clickcount&reverse=true&limit=40")))
        list.forEach { known[it.id] = it }
        return list
    }

    /** Talar om för Radio Browser att kanalen spelades, så att populära kanaler hamnar högt. */
    suspend fun countClick(s: RadioStation) {
        if (!s.id.startsWith("rb:")) return
        runCatching { rb("/json/url/${s.id.removePrefix("rb:")}") }
    }

    private var rbServer: String? = null

    /** Radio Browser har flera speglar; ta en som svarar. */
    private suspend fun rb(path: String): String {
        val servers = listOfNotNull(rbServer) + RB_SERVERS.shuffled()
        var last: IOException? = null
        for (s in servers.distinct()) {
            try {
                val body = get("https://$s$path")
                rbServer = s
                return body
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("Radio Browser svarar inte")
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    // ---------- Lagring ----------

    private fun loadFavorites(): List<RadioStation> = runCatching {
        val arr = JSONArray(favFile.readText())
        (0 until arr.length()).mapNotNull { RadioStation.fromJson(arr.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun saveFavorites() {
        runCatching {
            val tmp = File(favFile.parentFile, favFile.name + ".tmp")
            tmp.writeText(JSONArray().apply { _favorites.value.forEach { put(it.toJson()) } }.toString())
            tmp.renameTo(favFile)
        }
    }

    private fun loadCache() {
        runCatching {
            val o = JSONObject(cacheFile.readText())
            fun list(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { RadioStation.fromJson(a.optJSONObject(it)) } }.orEmpty()
            val sr = list("sr")
            val se = list("se")
            if (sr.isNotEmpty()) srCache = o.optLong("srAt") to sr
            if (se.isNotEmpty()) seCache = o.optLong("seAt") to se
            (sr + se).forEach { known[it.id] = it }
        }
    }

    private fun saveCache() {
        runCatching {
            val o = JSONObject()
            srCache?.let { (at, l) -> o.put("srAt", at).put("sr", JSONArray().apply { l.forEach { put(it.toJson()) } }) }
            seCache?.let { (at, l) -> o.put("seAt", at).put("se", JSONArray().apply { l.forEach { put(it.toJson()) } }) }
            cacheFile.writeText(o.toString())
        }
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
        const val USER_AGENT = "JPFM/1.0 (Android; github.com/thesummerboat/jpfm)"
        private val RB_SERVERS = listOf(
            "de1.api.radio-browser.info",
            "de2.api.radio-browser.info",
            "fi1.api.radio-browser.info",
            "nl1.api.radio-browser.info",
            "at1.api.radio-browser.info",
            "all.api.radio-browser.info",
        )

        /** Om SR:s API inte svarar: de stora kanalerna. */
        val FALLBACK_SR = listOf(
            RadioStation("sr:132", "P1", RadioParse.srStreamUrl(132, null), group = "Rikskanal", color = 0xFF31A1BD.toInt(), altUrl = "https://sverigesradio.se/topsy/direkt/srapi/132.mp3"),
            RadioStation("sr:164", "P3", RadioParse.srStreamUrl(164, null), group = "Rikskanal", color = 0xFF19A972.toInt(), altUrl = "https://sverigesradio.se/topsy/direkt/srapi/164.mp3"),
        )
    }
}
