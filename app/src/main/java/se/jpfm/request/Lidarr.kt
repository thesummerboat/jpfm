package se.jpfm.request

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Hur JPFM når Lidarr och var ny musik ska hamna. */
data class LidarrConfig(
    val url: String,
    val apiKey: String,
    val rootFolder: String,
    val qualityProfileId: Int,
    val metadataProfileId: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url).put("key", apiKey).put("root", rootFolder)
        .put("quality", qualityProfileId).put("metadata", metadataProfileId)

    companion object {
        fun fromJson(o: JSONObject?): LidarrConfig? {
            if (o == null) return null
            val url = o.optString("url").ifBlank { return null }
            val key = o.optString("key").ifBlank { return null }
            return LidarrConfig(url, key, o.optString("root"), o.optInt("quality"), o.optInt("metadata"))
        }

        /** "unraid:8686" → "http://unraid:8686", utan snedstreck på slutet. */
        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u
        }
    }
}

data class LidarrOption(val id: Int, val name: String)
data class LidarrRoot(val path: String, val name: String, val defaultQuality: Int, val defaultMetadata: Int, val freeSpace: Long?)
data class LidarrSetup(val version: String, val roots: List<LidarrRoot>, val quality: List<LidarrOption>, val metadata: List<LidarrOption>)

/** Ett album (eller singel/EP) som Lidarr känner till, från sökningen eller från Lidarrs egen lista. */
data class LidarrAlbum(
    val foreignAlbumId: String,
    val title: String,
    val artist: String,
    val foreignArtistId: String?,
    /** "Album", "Single", "EP" … */
    val type: String?,
    val year: Int?,
    val cover: String?,
    /** Lidarrs id om albumet redan finns där, annars 0. */
    val lidarrId: Int,
    val monitored: Boolean,
    val trackFiles: Int,
    val tracks: Int,
    /** Hela svaret från Lidarr, som skickas tillbaka när albumet läggs till. */
    val raw: JSONObject,
) {
    val complete: Boolean get() = tracks > 0 && trackFiles >= tracks
    val typeLabel: String get() = when (type?.lowercase()) {
        "single" -> "Singel"
        "ep" -> "EP"
        "album", null, "" -> "Album"
        "broadcast" -> "Sändning"
        else -> type
    }
}

/** En nedladdning som pågår i Lidarr. */
data class LidarrQueueItem(val albumId: Int, val progress: Float, val state: String?, val error: String?)

class LidarrException(message: String) : IOException(message)

/** Lidarrs API (v1). Bara det JPFM behöver: söka, önska, och se hur det går. */
class LidarrClient(base: OkHttpClient) {
    private val http = base.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun setup(url: String, key: String): LidarrSetup {
        val status = obj(get(url, key, "/api/v1/system/status"))
        val roots = LidarrParse.roots(arr(get(url, key, "/api/v1/rootfolder")))
        val quality = LidarrParse.options(arr(get(url, key, "/api/v1/qualityprofile")))
        val metadata = LidarrParse.options(arr(get(url, key, "/api/v1/metadataprofile")))
        return LidarrSetup(status.s("version") ?: "?", roots, quality, metadata)
    }

    suspend fun lookup(cfg: LidarrConfig, term: String): List<LidarrAlbum> {
        val body = get(cfg.url, cfg.apiKey, "/api/v1/album/lookup?term=${enc(term)}")
        return withContext(Dispatchers.Default) { LidarrParse.albums(arr(body)) }
    }

    suspend fun albumsById(cfg: LidarrConfig, ids: Collection<Int>): List<LidarrAlbum> {
        if (ids.isEmpty()) return emptyList()
        val q = ids.joinToString("&") { "albumIds=$it" }
        return LidarrParse.albums(arr(get(cfg.url, cfg.apiKey, "/api/v1/album?$q")))
    }

    suspend fun queue(cfg: LidarrConfig): List<LidarrQueueItem> =
        LidarrParse.queue(arr(get(cfg.url, cfg.apiKey, "/api/v1/queue/details?includeAlbum=false&includeArtist=false")))

    /**
     * Önskar ett album: lägger till det (och artisten om den saknas, utan att bevaka artistens
     * övriga skivor) och ber Lidarr söka direkt. Finns albumet redan bevakas det och söks igen.
     * Returnerar Lidarrs id för albumet.
     */
    suspend fun request(cfg: LidarrConfig, album: LidarrAlbum): Int {
        val existing = if (album.lidarrId > 0) album.lidarrId else findExisting(cfg, album.foreignAlbumId)
        if (existing != null && existing > 0) {
            monitorAndSearch(cfg, existing)
            return existing
        }
        val body = LidarrParse.addBody(album.raw, cfg)
        val res = try {
            send(cfg.url, cfg.apiKey, "/api/v1/album", "POST", body.toString())
        } catch (e: LidarrException) {
            // Hann någon lägga till det under tiden? Då räcker det att bevaka och söka.
            val id = findExisting(cfg, album.foreignAlbumId) ?: throw e
            monitorAndSearch(cfg, id)
            return id
        }
        val id = runCatching { JSONObject(res).optInt("id") }.getOrDefault(0)
        if (id > 0) {
            // Lidarr söker själv när albumet läggs till, men inte alltid om artisten var ny.
            runCatching { search(cfg, id) }
            return id
        }
        return findExisting(cfg, album.foreignAlbumId) ?: throw LidarrException("Lidarr svarade inte med något album")
    }

    private suspend fun findExisting(cfg: LidarrConfig, foreignAlbumId: String): Int? =
        runCatching { LidarrParse.albums(arr(get(cfg.url, cfg.apiKey, "/api/v1/album?foreignAlbumId=${enc(foreignAlbumId)}"))) }
            .getOrNull()?.firstOrNull { it.lidarrId > 0 }?.lidarrId

    private suspend fun monitorAndSearch(cfg: LidarrConfig, id: Int) {
        send(cfg.url, cfg.apiKey, "/api/v1/album/monitor", "PUT", JSONObject().put("albumIds", JSONArray().put(id)).put("monitored", true).toString())
        search(cfg, id)
    }

    private suspend fun search(cfg: LidarrConfig, id: Int) {
        send(cfg.url, cfg.apiKey, "/api/v1/command", "POST", JSONObject().put("name", "AlbumSearch").put("albumIds", JSONArray().put(id)).toString())
    }

    // ---------- HTTP ----------

    private suspend fun get(url: String, key: String, path: String): String = call(
        Request.Builder().url(url + path).header("X-Api-Key", key).header("Accept", "application/json").get().build(),
    )

    private suspend fun send(url: String, key: String, path: String, method: String, json: String): String = call(
        Request.Builder().url(url + path).header("X-Api-Key", key).header("Accept", "application/json")
            .method(method, json.toRequestBody(JSON)).build(),
    )

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 -> throw LidarrException("Fel API-nyckel")
                    !resp.isSuccessful -> throw LidarrException(LidarrParse.error(body) ?: "Lidarr svarade ${resp.code}")
                    else -> body
                }
            }
        } catch (e: LidarrException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw LidarrException("Ogiltig adress")
        } catch (e: IOException) {
            throw IOException("Når inte Lidarr – är du hemma eller på Tailscale?", e)
        }
    }

    private fun obj(s: String) = runCatching { JSONObject(s) }.getOrElse { throw LidarrException("Det där verkar inte vara Lidarr") }
    private fun arr(s: String) = runCatching { JSONArray(s) }.getOrElse { throw LidarrException("Det där verkar inte vara Lidarr") }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Text ur JSON, där null och tomt blir null (org.json gör annars "null" av null). */
private fun JSONObject.s(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

/** Tolkar Lidarrs svar. Ingen Android, så det går att testa. */
object LidarrParse {
    fun albums(a: JSONArray): List<LidarrAlbum> = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { album(it) } }

    fun album(o: JSONObject): LidarrAlbum? {
        val foreign = o.s("foreignAlbumId") ?: return null
        val artist = o.optJSONObject("artist")
        val stats = o.optJSONObject("statistics")
        val images = o.optJSONArray("images")
        val cover = o.s("remoteCover")
            ?: images?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.s("coverType").equals("cover", true) }
                    ?.s("remoteUrl")
            }
        return LidarrAlbum(
            foreignAlbumId = foreign,
            title = o.s("title").orEmpty(),
            artist = artist?.s("artistName").orEmpty(),
            foreignArtistId = artist?.s("foreignArtistId"),
            type = o.s("albumType"),
            year = o.s("releaseDate")?.take(4)?.toIntOrNull(),
            cover = cover,
            lidarrId = o.optInt("id"),
            monitored = o.optBoolean("monitored"),
            trackFiles = stats?.optInt("trackFileCount") ?: 0,
            tracks = stats?.let { s -> s.optInt("trackCount").takeIf { it > 0 } ?: s.optInt("totalTrackCount") } ?: 0,
            raw = o,
        )
    }

    /** Det som skickas för att lägga till ett album: bevaka bara det, inte artistens andra skivor. */
    fun addBody(raw: JSONObject, cfg: LidarrConfig): JSONObject {
        val o = JSONObject(raw.toString())
        val foreign = o.s("foreignAlbumId").orEmpty()
        o.put("monitored", true)
        o.put("anyReleaseOk", true)
        o.put("addOptions", JSONObject().put("searchForNewAlbum", true))
        val artist = o.optJSONObject("artist") ?: JSONObject().also { o.put("artist", it) }
        artist.put("qualityProfileId", cfg.qualityProfileId)
        artist.put("metadataProfileId", cfg.metadataProfileId)
        artist.put("rootFolderPath", cfg.rootFolder)
        artist.put("monitored", true)
        artist.put("monitorNewItems", "none")
        artist.put(
            "addOptions",
            JSONObject()
                .put("monitor", "none")
                .put("albumsToMonitor", JSONArray().put(foreign))
                .put("monitored", true)
                .put("searchForMissingAlbums", false),
        )
        return o
    }

    fun roots(a: JSONArray): List<LidarrRoot> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val path = o.s("path") ?: return@mapNotNull null
        LidarrRoot(
            path = path,
            name = o.s("name") ?: path,
            defaultQuality = o.optInt("defaultQualityProfileId"),
            defaultMetadata = o.optInt("defaultMetadataProfileId"),
            freeSpace = if (o.has("freeSpace") && !o.isNull("freeSpace")) o.optLong("freeSpace") else null,
        )
    }

    fun options(a: JSONArray): List<LidarrOption> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        LidarrOption(o.optInt("id"), o.s("name") ?: "Profil ${o.optInt("id")}")
    }.filter { it.id > 0 }

    fun queue(a: JSONArray): List<LidarrQueueItem> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val albumId = o.optInt("albumId").takeIf { it > 0 } ?: return@mapNotNull null
        val size = o.optDouble("size", 0.0)
        val left = o.optDouble("sizeleft", 0.0)
        LidarrQueueItem(
            albumId = albumId,
            progress = if (size > 0) ((size - left) / size).toFloat().coerceIn(0f, 1f) else 0f,
            state = o.s("trackedDownloadState") ?: o.s("status"),
            error = o.s("errorMessage"),
        )
    }

    /** Lidarrs felmeddelanden: [{"errorMessage": "..."}] eller {"message": "..."}. */
    fun error(body: String): String? = runCatching {
        val t = body.trim()
        when {
            t.startsWith("[") -> JSONArray(t).optJSONObject(0)?.s("errorMessage")
            t.startsWith("{") -> JSONObject(t).let { it.s("message") ?: it.s("error") }
            else -> null
        }
    }.getOrNull()
}
