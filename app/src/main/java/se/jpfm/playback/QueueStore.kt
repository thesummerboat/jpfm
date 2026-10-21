package se.jpfm.playback

import androidx.media3.common.MediaItem
import org.json.JSONArray
import org.json.JSONObject
import se.jpfm.plex.Track
import se.jpfm.plex.TrackJson
import se.jpfm.podcast.Episode
import se.jpfm.radio.RadioStation
import java.io.File

/** Något i kön: en låt från Plex, en radiokanal eller ett poddavsnitt. */
sealed interface SavedEntry {
    data class Song(val track: Track) : SavedEntry
    data class Live(val station: RadioStation) : SavedEntry
    data class Pod(val episode: Episode) : SavedEntry

    companion object {
        fun of(item: MediaItem): SavedEntry? =
            TrackItems.track(item)?.let { Song(it) }
                ?: ExtItems.station(item)?.let { Live(it) }
                ?: ExtItems.episode(item)?.let { Pod(it) }
    }
}

/** Kön som den såg ut senast, så att musiken kan fortsätta efter omstart (och i bilen). */
data class SavedQueue(
    val entries: List<SavedEntry>,
    val index: Int,
    val positionMs: Long,
    val context: QueueContext?,
    val shuffle: Boolean,
    val repeatMode: Int,
)

class QueueStore(dir: File) {
    private val file = File(dir, "queue.json")

    @Synchronized
    fun save(q: SavedQueue) {
        // Mycket långa köer (radio) sparas runt det som spelas.
        val from = (q.index - 100).coerceAtLeast(0)
        val slice = q.entries.subList(from, (from + 500).coerceAtMost(q.entries.size))
        val arr = JSONArray()
        for (e in slice) {
            arr.put(
                when (e) {
                    is SavedEntry.Song -> JSONObject().put("k", "t").put("v", TrackJson.toJson(e.track))
                    is SavedEntry.Live -> JSONObject().put("k", "l").put("v", e.station.toJson())
                    is SavedEntry.Pod -> JSONObject().put("k", "e").put("v", e.episode.toJson())
                },
            )
        }
        val o = JSONObject()
            .put("items", arr)
            .put("index", q.index - from)
            .put("position", q.positionMs)
            .put("shuffle", q.shuffle)
            .put("repeat", q.repeatMode)
        q.context?.let { o.put("context", it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun load(): SavedQueue? {
        if (!file.exists()) return null
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val entries = o.optJSONArray("items")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val v = e.optJSONObject("v")
                when (e.optString("k")) {
                    "t" -> v?.let { TrackJson.fromJson(it) }?.let { SavedEntry.Song(it) }
                    "l" -> RadioStation.fromJson(v)?.let { SavedEntry.Live(it) }
                    "e" -> Episode.fromJson(v)?.let { SavedEntry.Pod(it) }
                    else -> null
                }
            }
        }
            // Äldre versioner sparade bara låtar.
            ?: TrackJson.listFromJson(o.optJSONArray("tracks")).map { SavedEntry.Song(it) }
        if (entries.isEmpty()) return null
        return SavedQueue(
            entries = entries,
            index = o.optInt("index").coerceIn(0, entries.size - 1),
            positionMs = o.optLong("position").coerceAtLeast(0),
            context = QueueContext.fromJson(o.optJSONObject("context")),
            shuffle = o.optBoolean("shuffle"),
            repeatMode = o.optInt("repeat"),
        )
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
