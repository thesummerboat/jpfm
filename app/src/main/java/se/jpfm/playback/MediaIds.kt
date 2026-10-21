package se.jpfm.playback

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Id:n för allt i bläddringsträdet (Android Auto) och för spelbara saker. Ett id beskriver
 * vad det är och, för låtar, var de spelas ifrån – så att ett tryck på låt 3 i ett album
 * spelar hela albumet från låt 3.
 */
object MediaIds {
    const val ROOT = "root"
    const val HOME = "home"
    const val PLAYLISTS = "playlists"
    const val ARTISTS = "artists"
    const val DOWNLOADS = "downloads"
    const val LIBRARY = "library"
    const val RADIO = "radio"
    const val PODCASTS = "podcasts"
    const val NEW_EPISODES = "podnew"
    const val SHUFFLE_ALL = "play:shuffle"

    fun artist(key: String) = "artist:$key"
    fun album(key: String) = "album:$key"
    fun playlist(key: String) = "playlist:$key"
    fun hub(index: Int) = "hub:$index"
    fun letter(l: String) = "letter:${enc(l)}"
    fun offline(collectionId: String) = "offline:${enc(collectionId)}"
    fun station(key: String) = "station:${enc(key)}"
    fun shuffleArtist(key: String) = "play:artist:$key"
    fun playAlbum(key: String) = "play:album:$key"
    fun playPlaylist(key: String) = "play:playlist:$key"
    fun playOffline(collectionId: String) = "play:offline:${enc(collectionId)}"
    /** En mix: låtfilter och titel, t.ex. "play:mix:album.genre%3D12%7CRock". */
    fun playMix(trackFilter: String, title: String) = "play:mix:${enc("$trackFilter|$title")}"
    /** Topplistor: "se" (Sverige) eller "me" (det du spelat mest). */
    fun playHot(which: String) = "play:hot:$which"

    /** En låt, eventuellt i ett sammanhang: "track:123@album:45". */
    fun track(ratingKey: String, context: String? = null) =
        if (context == null) "track:$ratingKey" else "track:$ratingKey@$context"

    data class Id(val type: String, val value: String, val context: String? = null)

    fun parse(id: String): Id {
        if (id in setOf(ROOT, HOME, PLAYLISTS, ARTISTS, DOWNLOADS, LIBRARY, RADIO, PODCASTS, NEW_EPISODES)) return Id(id, "")
        if (id == SHUFFLE_ALL) return Id("shuffle", "")
        if (id.startsWith("play:")) {
            val rest = id.removePrefix("play:")
            val type = rest.substringBefore(':')
            val value = rest.substringAfter(':', "")
            return Id("play-$type", if (type == "offline" || type == "mix") dec(value) else value)
        }
        val type = id.substringBefore(':')
        val rest = id.substringAfter(':', "")
        return when (type) {
            "track" -> {
                val ctx = rest.substringAfter('@', "").ifEmpty { null }
                Id("track", rest.substringBefore('@'), ctx)
            }
            "station", "letter", "offline" -> Id(type, dec(rest))
            else -> Id(type, rest)
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

    /** Bokstaven en artist sorteras under: A–Ö, eller # för siffror och tecken. */
    fun letterOf(name: String): String {
        val t = name.trim().let { if (it.lowercase().startsWith("the ")) it.substring(4) else it }
        val c = t.firstOrNull()?.uppercaseChar() ?: return "#"
        return when {
            c in 'A'..'Z' || c == 'Å' || c == 'Ä' || c == 'Ö' -> c.toString()
            c == 'É' || c == 'È' -> "E"
            c == 'Ü' -> "U"
            c == 'Ø' || c == 'Ó' -> "Ö"
            c == 'Æ' -> "Ä"
            else -> "#"
        }
    }

    val LETTERS: List<String> = ('A'..'Z').map { it.toString() } + listOf("Å", "Ä", "Ö", "#")
}
