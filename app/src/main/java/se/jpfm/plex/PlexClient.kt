package se.jpfm.plex

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import se.jpfm.BuildConfig
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** En server från plex.tv innan vi vet vilken adress som fungerar. */
data class ServerInfo(
    val name: String,
    val machineId: String,
    val token: String,
    val owned: Boolean,
    val candidates: List<String>,
)

/**
 * Pratar HTTP med plex.tv (inloggning, hitta servern) och med Plex Media Server. Allt går som
 * JSON genom Accept: application/json. Håller inget läge själv – [PlexSession] vet vilken server
 * som gäller. Dokumentation: https://developer.plex.tv
 */
class PlexClient(val clientId: String, private val http: OkHttpClient) {

    /** Kort timeout för att testa vilken serveradress som svarar. */
    private val quick = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val deviceModel: String = ascii(Build.MODEL ?: "Android").ifBlank { "Android" }

    /** Headers som Plex vill ha på varje anrop. Spelaren skickar samma när den hämtar musik. */
    fun baseHeaders(token: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Product", PRODUCT)
        put("X-Plex-Version", BuildConfig.VERSION_NAME)
        put("X-Plex-Client-Identifier", clientId)
        put("X-Plex-Platform", "Android")
        put("X-Plex-Platform-Version", ascii(Build.VERSION.RELEASE ?: ""))
        put("X-Plex-Device", deviceModel)
        put("X-Plex-Device-Name", "JPFM ($deviceModel)")
        put("X-Plex-Provides", "player")
        put("X-Plex-Language", "sv")
        if (token != null) put("X-Plex-Token", token)
    }

    private fun request(url: String, token: String?): Request.Builder {
        val b = Request.Builder().url(url)
        for ((k, v) in baseHeaders(token)) b.header(k, v)
        return b
    }

    /** Skickar anropet och väntar utan att låsa en tråd, så att det går att avbryta. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private suspend fun execute(req: Request, client: OkHttpClient = http): String {
        val resp = client.newCall(req).await()
        return resp.use {
            withContext(Dispatchers.IO) {
                when {
                    resp.code == 401 -> throw PlexAuthException()
                    !resp.isSuccessful -> throw PlexHttpException(resp.code)
                    else -> resp.body?.string().orEmpty()
                }
            }
        }
    }

    // ---------- Inloggning med kod ----------

    suspend fun createPin(): PlexPin {
        val body = execute(
            request("https://plex.tv/api/v2/pins", null).post("".toRequestBody()).build(),
        )
        val o = JSONObject(body)
        return PlexPin(o.getLong("id"), o.getString("code"))
    }

    /**
     * Länk som öppnar Plex inloggning i webbläsaren med koden ifylld. När du loggat in där
     * hämtar [checkPin] kontots nyckel.
     */
    fun authUrl(pin: PlexPin): String {
        val p = mapOf(
            "clientID" to clientId,
            "code" to pin.code,
            "context[device][product]" to PRODUCT,
            "context[device][platform]" to "Android",
            "context[device][device]" to deviceModel,
        ).entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "https://app.plex.tv/auth#?$p"
    }

    /** Kontots nyckel när inloggningen är klar, annars null. */
    suspend fun checkPin(pin: PlexPin): String? {
        val o = JSONObject(execute(request("https://plex.tv/api/v2/pins/${pin.id}", null).get().build()))
        return o.str("authToken")
    }

    suspend fun user(accountToken: String): PlexUser? = runCatching {
        val o = JSONObject(execute(request("https://plex.tv/api/v2/user", accountToken).get().build()))
        PlexUser(o.str("title") ?: o.str("username") ?: "Plex", o.str("thumb"))
    }.getOrNull()

    // ---------- Hitta servern ----------

    /** Kontots servrar med alla adresser, bästa adressen först (lokal, sedan direkt, sist relä). */
    suspend fun servers(accountToken: String): List<ServerInfo> {
        val body = execute(
            request("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=0", accountToken).get().build(),
        )
        val arr = JSONArray(body)
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("provides").split(",").contains("server") }
            .map { s ->
                ServerInfo(
                    name = s.str("name") ?: "Plex",
                    machineId = s.str("clientIdentifier") ?: "",
                    token = s.str("accessToken") ?: accountToken,
                    owned = s.bool("owned"),
                    candidates = candidateUris(s.optJSONArray("connections")),
                )
            }
            .sortedByDescending { it.owned }
    }

    private fun candidateUris(conns: JSONArray?): List<String> {
        if (conns == null) return emptyList()
        val ranked = mutableListOf<Pair<Int, String>>()
        for (i in 0 until conns.length()) {
            val c = conns.optJSONObject(i) ?: continue
            val local = c.bool("local")
            val relay = c.bool("relay")
            val base = when {
                local && !relay -> 0
                !relay -> 10
                else -> 20
            }
            c.str("uri")?.let { ranked += base to it.trimEnd('/') }
            // Reserv om routern blockerar plex.direct-adresser: vanlig http på lokal IP.
            val address = c.str("address")
            if (local && !relay && address != null && !address.contains(':')) {
                ranked += (base + 1) to "http://$address:${c.int("port") ?: 32400}"
            }
        }
        return ranked.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * Provar alla adresser samtidigt och tar den bästa som svarar. En lokal adress vinner direkt;
     * annars väntar vi en kort stund efter första svaret ifall en bättre hinner svara.
     */
    suspend fun fastestReachable(candidates: List<String>, token: String): String? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null
        val jobs = candidates.mapIndexed { rank, uri -> rank to async { isReachable(uri, token) } }
        var best: Int? = null
        val pending = jobs.toMutableList()
        var firstSuccessAt = 0L
        while (pending.isNotEmpty()) {
            val waitMs = if (best == null) Long.MAX_VALUE else (600 - (System.currentTimeMillis() - firstSuccessAt)).coerceAtLeast(0)
            val done = withTimeoutOrNull(waitMs) {
                select<Pair<Int, Boolean>> {
                    for ((rank, d) in pending) d.onAwait { rank to it }
                }
            } ?: break
            pending.removeAll { it.first == done.first }
            if (done.second && (best == null || done.first < best)) {
                if (best == null) firstSuccessAt = System.currentTimeMillis()
                best = done.first
                if (PlexAddress.isLocal(candidates[done.first]) || done.first == 0) break
            }
        }
        pending.forEach { it.second.cancel() }
        best?.let { candidates[it] }
    }

    suspend fun isReachable(uri: String, token: String): Boolean = try {
        execute(request("$uri/identity", token).get().build(), quick)
        true
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
    }

    // ---------- Anrop mot servern ----------

    suspend fun get(server: PlexServer, path: String): JSONObject {
        val body = execute(request(url(server, path), server.token).get().build())
        // Stora bibliotek ger svar på flera MB: tolka utanför huvudtråden.
        return withContext(Dispatchers.Default) { if (body.isBlank()) JSONObject() else JSONObject(body) }
    }

    suspend fun text(server: PlexServer, path: String, accept: String? = null): String {
        val b = request(url(server, path), server.token).get()
        if (accept != null) b.header("Accept", accept)
        return execute(b.build())
    }

    suspend fun put(server: PlexServer, path: String): JSONObject? {
        val body = execute(request(url(server, path), server.token).put("".toRequestBody()).build())
        return body.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    suspend fun post(server: PlexServer, path: String): JSONObject? {
        val body = execute(request(url(server, path), server.token).post("".toRequestBody()).build())
        return body.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    suspend fun delete(server: PlexServer, path: String) {
        execute(request(url(server, path), server.token).delete().build())
    }

    fun url(server: PlexServer, path: String): String =
        if (path.startsWith("http")) path else server.uri + (if (path.startsWith("/")) path else "/$path")

    /** Bild via Plex bildskalare: rätt storlek, snabbare och mindre data. */
    fun imageUrl(server: PlexServer, path: String, size: Int): String =
        if (path.startsWith("http") && !path.contains("/library/")) path
        else "${server.uri}/photo/:/transcode?width=$size&height=$size&minSize=1&upscale=1" +
            "&url=${enc(path)}&X-Plex-Token=${server.token}"

    companion object {
        const val PRODUCT = "JPFM"
        fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")
    }
}

open class PlexHttpException(val code: Int) : IOException("Plex svarade HTTP $code")
class PlexAuthException : PlexHttpException(401)

private fun ascii(value: String): String = value.filter { it.code in 32..126 }
