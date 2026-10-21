package se.jpfm.playback

import android.os.Bundle
import org.json.JSONObject

/**
 * Var kön kommer ifrån. Följer med spelaren som spellistans metadata, så att tjänsten vet om
 * kön ska fyllas på (radio) och om ljudutjämningen ska gå på albumnivå.
 */
data class QueueContext(
    val kind: Kind,
    val title: String,
    /** Albumets, spellistans eller artistens nyckel (för mixar: låtfiltret). */
    val id: String? = null,
    /** Spelkön på servern för Plex radio och mixar. */
    val playQueueId: Long? = null,
) {
    enum class Kind { ALBUM, PLAYLIST, ARTIST, STATION, SONIC_RADIO, SHUFFLE_LIBRARY, TRACKS, DOWNLOADS, LIVE, PODCAST, MIX }

    /** Fylls på av sig själv när den börjar ta slut. */
    val endless: Boolean get() = kind == Kind.STATION || kind == Kind.SONIC_RADIO || kind == Kind.SHUFFLE_LIBRARY || kind == Kind.MIX

    fun toBundle(): Bundle = Bundle().apply {
        putString(K_KIND, kind.name)
        putString(K_TITLE, title)
        id?.let { putString(K_ID, it) }
        playQueueId?.let { putLong(K_PQ, it) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put(K_KIND, kind.name)
        put(K_TITLE, title)
        id?.let { put(K_ID, it) }
        playQueueId?.let { put(K_PQ, it) }
    }

    companion object {
        private const val K_KIND = "jpfm.ctx.kind"
        private const val K_TITLE = "jpfm.ctx.title"
        private const val K_ID = "jpfm.ctx.id"
        private const val K_PQ = "jpfm.ctx.pq"

        fun fromBundle(b: Bundle?): QueueContext? {
            val kind = b?.getString(K_KIND)?.let { k -> Kind.entries.firstOrNull { it.name == k } } ?: return null
            return QueueContext(
                kind = kind,
                title = b.getString(K_TITLE).orEmpty(),
                id = b.getString(K_ID),
                playQueueId = if (b.containsKey(K_PQ)) b.getLong(K_PQ) else null,
            )
        }

        fun fromJson(o: JSONObject?): QueueContext? {
            val kind = o?.optString(K_KIND)?.let { k -> Kind.entries.firstOrNull { it.name == k } } ?: return null
            return QueueContext(
                kind = kind,
                title = o.optString(K_TITLE),
                id = o.optString(K_ID).ifBlank { null },
                playQueueId = if (o.has(K_PQ)) o.optLong(K_PQ) else null,
            )
        }
    }
}
