package se.jpfm.charts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import se.jpfm.plex.ChartSong
import java.io.File
import java.io.IOException

/**
 * Topplistor: det som spelas mest i Sverige just nu (Apple Musics publika lista, utan konto
 * och utan spårning). Appen letar sedan upp låtarna i ditt eget bibliotek.
 */
class ChartRepository(private val http: OkHttpClient, dir: File) {
    private val file = File(dir, "chart-se.json")

    @Volatile private var memory: Pair<Long, List<ChartSong>>? = null

    suspend fun sweden(force: Boolean = false): List<ChartSong> {
        memory?.let { (at, list) -> if (!force && System.currentTimeMillis() - at < TTL && list.isNotEmpty()) return list }
        return try {
            val body = get(URL_SE)
            val list = withContext(Dispatchers.Default) { ChartParse.apple(JSONObject(body)) }
            if (list.isNotEmpty()) {
                memory = System.currentTimeMillis() to list
                withContext(Dispatchers.IO) { runCatching { file.writeText(body) } }
            }
            list
        } catch (e: Exception) {
            // Utan nät: senast hämtade listan duger.
            val saved = withContext(Dispatchers.IO) { runCatching { ChartParse.apple(JSONObject(file.readText())) }.getOrNull() }
            if (!saved.isNullOrEmpty()) saved else throw e
        }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "JPFM/1.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    companion object {
        private const val TTL = 6 * 60 * 60 * 1000L
        const val URL_SE = "https://rss.marketingtools.apple.com/api/v2/se/music/most-played/100/songs.json"
    }
}

object ChartParse {
    fun apple(root: JSONObject): List<ChartSong> {
        val results = root.optJSONObject("feed")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val artist = o.optString("artistName").trim()
            val title = o.optString("name").trim()
            if (artist.isEmpty() || title.isEmpty()) return@mapNotNull null
            ChartSong(artist, title, o.optString("artworkUrl100").ifBlank { null })
        }
    }
}
