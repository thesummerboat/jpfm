package se.jpfm.request

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Vem du är för JPFM-servern. */
data class ServerMe(
    val id: Long,
    val name: String,
    /** "owner", "member", "unclaimed" eller "denied". */
    val role: String,
    val ownerName: String?,
    val lidarr: Boolean,
    val autoApprove: Boolean,
    val blocked: Boolean,
    /** Önskningar som väntar på ditt godkännande (bara för ägaren). */
    val pending: Int,
) {
    val isOwner: Boolean get() = role == "owner"
    val canRequest: Boolean get() = (role == "owner" || role == "member") && !blocked
}

enum class RequestState(val label: String) {
    QUEUED("Skickas när servern går att nå"),
    PENDING("Väntar på godkännande"),
    DENIED("Nekad"),
    APPROVED("Godkänd"),
    SEARCHING("Lidarr letar"),
    DOWNLOADING("Laddas ner"),
    IMPORTING("Läggs in"),
    DONE("Klar"),
    FAILED("Gick inte"),
    ;

    val active: Boolean get() = this == QUEUED || this == PENDING || this == APPROVED || this == SEARCHING || this == DOWNLOADING || this == IMPORTING

    companion object {
        fun of(s: String?): RequestState = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: FAILED
    }
}

/** En önskan på servern (eller i telefonen, om den inte gått att skicka än). */
data class RemoteRequest(
    val id: Long,
    val foreignAlbumId: String,
    val title: String,
    val artist: String,
    val type: String?,
    val year: Int?,
    val cover: String?,
    val song: String?,
    val userName: String,
    val state: RequestState,
    val progress: Float,
    val message: String?,
    val reason: String?,
    val createdAt: Long,
    val mine: Boolean,
)

/** Ett album (eller singel/EP) från sökningen i Lidarr. */
data class ServerAlbum(
    val foreignAlbumId: String,
    val title: String,
    val artist: String,
    val type: String?,
    val year: Int?,
    val cover: String?,
    val complete: Boolean,
    val request: RemoteRequest?,
) {
    val typeLabel: String get() = typeLabel(type)
}

fun typeLabel(type: String?): String = when (type?.lowercase()) {
    "single" -> "Singel"
    "ep" -> "EP"
    "album", null, "" -> "Album"
    "broadcast" -> "Sändning"
    else -> type
}

data class ServerUser(val id: Long, val name: String, val thumb: String?, val autoApprove: Boolean, val blocked: Boolean, val owner: Boolean, val requests: Int)

data class LidarrOption(val id: Int, val name: String)
data class LidarrRoot(val path: String, val name: String, val defaultQuality: Int, val defaultMetadata: Int)
data class LidarrSetup(val version: String, val roots: List<LidarrRoot>, val quality: List<LidarrOption>, val metadata: List<LidarrOption>)
data class LidarrSettings(val configured: Boolean, val url: String?, val hasKey: Boolean, val rootFolder: String?, val qualityProfileId: Int?, val metadataProfileId: Int?)

/** Servern svarade men sa nej, med ett meddelande att visa. */
class ServerException(message: String, val code: Int) : IOException(message)

/** Servern gick inte att nå (inte hemma, Tailscale av …). */
class ServerUnreachable(cause: Throwable?) : IOException("Når inte JPFM-servern – är du hemma eller på Tailscale?", cause)

/** JPFM-serverns API. Inloggning med din Plex-token, som servern kontrollerar mot plex.tv. */
class JpfmServerClient(base: OkHttpClient) {
    private val http = base.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun health(url: String): Boolean {
        val o = JSONObject(call(url, null, "GET", "/api/health"))
        return o.optString("app") == "jpfm-server"
    }

    suspend fun me(url: String, token: String): ServerMe = ServerParse.me(JSONObject(call(url, token, "GET", "/api/me")))

    suspend fun claim(url: String, token: String, serverId: String) {
        call(url, token, "POST", "/api/claim", JSONObject().put("serverId", serverId))
    }

    suspend fun lidarr(url: String, token: String): LidarrSettings = ServerParse.lidarrSettings(JSONObject(call(url, token, "GET", "/api/lidarr")))

    suspend fun testLidarr(url: String, token: String, lidarrUrl: String, apiKey: String?): LidarrSetup =
        ServerParse.setup(
            JSONObject(
                call(url, token, "POST", "/api/lidarr/test", JSONObject().put("url", lidarrUrl).apply { if (!apiKey.isNullOrBlank()) put("apiKey", apiKey) }),
            ),
        )

    suspend fun saveLidarr(url: String, token: String, lidarrUrl: String, apiKey: String?, root: String, quality: Int, metadata: Int) {
        val body = JSONObject()
            .put("url", lidarrUrl)
            .put("rootFolder", root)
            .put("qualityProfileId", quality)
            .put("metadataProfileId", metadata)
        if (!apiKey.isNullOrBlank()) body.put("apiKey", apiKey)
        call(url, token, "PUT", "/api/lidarr", body)
    }

    suspend fun search(url: String, token: String, term: String, fallback: String?): List<ServerAlbum> {
        val q = buildString {
            append("/api/search?term=").append(java.net.URLEncoder.encode(term, "UTF-8"))
            if (!fallback.isNullOrBlank()) append("&fallback=").append(java.net.URLEncoder.encode(fallback, "UTF-8"))
        }
        return withContext(Dispatchers.Default) { ServerParse.albums(JSONArray(call(url, token, "GET", q))) }
    }

    suspend fun requests(url: String, token: String): List<RemoteRequest> = ServerParse.requests(JSONArray(call(url, token, "GET", "/api/requests")))

    suspend fun request(url: String, token: String, foreignAlbumId: String, song: String?): RemoteRequest =
        ServerParse.request(
            JSONObject(call(url, token, "POST", "/api/requests", JSONObject().put("foreignAlbumId", foreignAlbumId).apply { song?.let { put("song", it) } })),
        ) ?: throw ServerException("Konstigt svar från servern", 500)

    suspend fun approve(url: String, token: String, id: Long) {
        call(url, token, "POST", "/api/requests/$id/approve")
    }

    suspend fun deny(url: String, token: String, id: Long, reason: String?) {
        call(url, token, "POST", "/api/requests/$id/deny", JSONObject().apply { reason?.let { put("reason", it) } })
    }

    suspend fun delete(url: String, token: String, id: Long) {
        call(url, token, "DELETE", "/api/requests/$id")
    }

    suspend fun users(url: String, token: String): List<ServerUser> = ServerParse.users(JSONArray(call(url, token, "GET", "/api/users")))

    suspend fun updateUser(url: String, token: String, id: Long, autoApprove: Boolean? = null, blocked: Boolean? = null) {
        val body = JSONObject()
        autoApprove?.let { body.put("autoApprove", it) }
        blocked?.let { body.put("blocked", it) }
        call(url, token, "PUT", "/api/users/$id", body)
    }

    private suspend fun call(url: String, token: String?, method: String, path: String, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val full = (url + path).toHttpUrlOrNull() ?: throw ServerException("Ogiltig adress", 0)
            val b = Request.Builder().url(full).header("Accept", "application/json")
            token?.let { b.header("X-Plex-Token", it) }
            val reqBody = (body ?: if (method == "GET" || method == "DELETE") null else JSONObject())?.toString()?.toRequestBody(JSON)
            b.method(method, reqBody)
            try {
                http.newCall(b.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw ServerException(ServerParse.detail(text) ?: "Servern svarade ${resp.code}", resp.code)
                    }
                    text
                }
            } catch (e: ServerException) {
                throw e
            } catch (e: IOException) {
                throw ServerUnreachable(e)
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** "tower:8687" → "http://tower:8687", utan snedstreck på slutet. */
        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u
        }
    }
}

private fun JSONObject.s(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

/** Tolkar serverns svar. Ingen Android, så det går att testa. */
object ServerParse {
    fun me(o: JSONObject) = ServerMe(
        id = o.optLong("id"),
        name = o.s("name") ?: "",
        role = o.s("role") ?: "denied",
        ownerName = o.s("ownerName"),
        lidarr = o.optBoolean("lidarr"),
        autoApprove = o.optBoolean("autoApprove"),
        blocked = o.optBoolean("blocked"),
        pending = o.optInt("pending"),
    )

    fun request(o: JSONObject): RemoteRequest? {
        val id = o.optLong("id").takeIf { it > 0 } ?: return null
        return RemoteRequest(
            id = id,
            foreignAlbumId = o.s("foreign_album_id") ?: "",
            title = o.s("title") ?: "",
            artist = o.s("artist") ?: "",
            type = o.s("type"),
            year = if (o.has("year") && !o.isNull("year")) o.optInt("year") else null,
            cover = o.s("cover"),
            song = o.s("song"),
            userName = o.s("user_name") ?: "",
            state = RequestState.of(o.s("status")),
            progress = o.optDouble("progress", 0.0).toFloat(),
            message = o.s("message"),
            reason = o.s("reason"),
            createdAt = (o.optDouble("created", 0.0) * 1000).toLong(),
            mine = o.optBoolean("mine"),
        )
    }

    fun requests(a: JSONArray): List<RemoteRequest> = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::request) }

    fun albums(a: JSONArray): List<ServerAlbum> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val id = o.s("foreignAlbumId") ?: return@mapNotNull null
        ServerAlbum(
            foreignAlbumId = id,
            title = o.s("title") ?: "",
            artist = o.s("artist") ?: "",
            type = o.s("type"),
            year = if (o.has("year") && !o.isNull("year")) o.optInt("year") else null,
            cover = o.s("cover"),
            complete = o.optBoolean("complete"),
            request = o.optJSONObject("request")?.let(::request),
        )
    }

    fun users(a: JSONArray): List<ServerUser> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        ServerUser(
            id = o.optLong("id"),
            name = o.s("name") ?: "",
            thumb = o.s("thumb"),
            autoApprove = o.optBoolean("auto_approve"),
            blocked = o.optBoolean("blocked"),
            owner = o.optBoolean("owner"),
            requests = o.optInt("requests"),
        )
    }

    fun setup(o: JSONObject): LidarrSetup {
        fun opts(key: String) = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { LidarrOption(it.optInt("id"), it.s("name") ?: "Profil ${it.optInt("id")}") }
        }.orEmpty()
        val roots = o.optJSONArray("roots")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { r ->
                val path = r.s("path") ?: return@mapNotNull null
                LidarrRoot(path, r.s("name") ?: path, r.optInt("defaultQualityProfileId"), r.optInt("defaultMetadataProfileId"))
            }
        }.orEmpty()
        return LidarrSetup(o.s("version") ?: "?", roots, opts("quality"), opts("metadata"))
    }

    fun lidarrSettings(o: JSONObject) = LidarrSettings(
        configured = o.optBoolean("configured"),
        url = o.s("url"),
        hasKey = o.optBoolean("hasKey"),
        rootFolder = o.s("rootFolder"),
        qualityProfileId = if (o.has("qualityProfileId")) o.optInt("qualityProfileId") else null,
        metadataProfileId = if (o.has("metadataProfileId")) o.optInt("metadataProfileId") else null,
    )

    /** FastAPI-fel: {"detail": "..."} eller {"detail": [{"msg": "..."}]}. */
    fun detail(body: String): String? = runCatching {
        when (val d = JSONObject(body).opt("detail")) {
            is String -> d.ifBlank { null }
            is JSONArray -> d.optJSONObject(0)?.s("msg")
            else -> null
        }
    }.getOrNull()
}
