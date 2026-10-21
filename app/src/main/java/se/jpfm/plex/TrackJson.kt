package se.jpfm.plex

import org.json.JSONArray
import org.json.JSONObject

/** Sparar låtar som JSON: kön som ska överleva omstart, nedladdat och det som skickas till spelaren. */
object TrackJson {

    fun toJson(t: Track): JSONObject = JSONObject().apply {
        put("rk", t.ratingKey)
        put("t", t.title)
        put("a", t.artist)
        t.trackArtist?.let { put("ta", it) }
        put("al", t.album)
        t.albumKey?.let { put("alk", it) }
        t.artistKey?.let { put("ark", it) }
        t.thumb?.let { put("th", it) }
        put("d", t.durationMs)
        t.index?.let { put("i", it) }
        t.disc?.let { put("di", it) }
        t.partKey?.let { put("p", it) }
        t.container?.let { put("c", it) }
        t.codec?.let { put("co", it) }
        t.bitrate?.let { put("br", it) }
        t.userRating?.let { put("ur", it.toDouble()) }
        t.year?.let { put("y", it) }
        t.playQueueItemId?.let { put("pq", it) }
    }

    fun fromJson(o: JSONObject): Track? {
        val rk = o.str("rk") ?: return null
        return Track(
            ratingKey = rk,
            title = o.str("t") ?: "",
            artist = o.str("a") ?: "",
            trackArtist = o.str("ta"),
            album = o.str("al") ?: "",
            albumKey = o.str("alk"),
            artistKey = o.str("ark"),
            thumb = o.str("th"),
            durationMs = o.long("d") ?: 0L,
            index = o.int("i"),
            disc = o.int("di"),
            partKey = o.str("p"),
            container = o.str("c"),
            codec = o.str("co"),
            bitrate = o.int("br"),
            userRating = o.double("ur")?.toFloat(),
            year = o.int("y"),
            playQueueItemId = o.long("pq"),
        )
    }

    fun encode(t: Track): String = toJson(t).toString()

    fun decode(s: String?): Track? = s?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    fun listToJson(tracks: List<Track>): JSONArray = JSONArray().apply { tracks.forEach { put(toJson(it)) } }

    fun listFromJson(arr: JSONArray?): List<Track> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
}
