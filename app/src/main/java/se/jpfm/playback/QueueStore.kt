package se.jpfm.playback

import org.json.JSONObject
import se.jpfm.plex.Track
import se.jpfm.plex.TrackJson
import java.io.File

/** Kön som den såg ut senast, så att musiken kan fortsätta efter omstart (och i bilen). */
data class SavedQueue(
    val tracks: List<Track>,
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
        // Mycket långa köer (radio) sparas runt låten som spelas.
        val from = (q.index - 100).coerceAtLeast(0)
        val slice = q.tracks.subList(from, (from + 500).coerceAtMost(q.tracks.size))
        val o = JSONObject()
            .put("tracks", TrackJson.listToJson(slice))
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
        val tracks = TrackJson.listFromJson(o.optJSONArray("tracks"))
        if (tracks.isEmpty()) return null
        return SavedQueue(
            tracks = tracks,
            index = o.optInt("index").coerceIn(0, tracks.size - 1),
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
