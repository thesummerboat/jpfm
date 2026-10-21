package se.jpfm.plex

import org.json.JSONObject

/** Tolkar Plex JSON till appens modeller. Ingen Android här, så allt går att testa. */
object Parse {

    fun item(o: JSONObject, forceStation: Boolean = false): MusicItem? {
        val type = o.str("type")
        if (forceStation) return station(o)
        return when (type) {
            "artist" -> artist(o)
            "album" -> album(o)
            "track" -> track(o)
            "playlist" -> {
                if (o.bool("radio") || o.str("key")?.contains("/station") == true) station(o)
                else if ((o.str("playlistType") ?: "audio") == "audio") playlist(o) else null
            }
            "station", "mix" -> station(o)
            else -> if (o.str("key") != null && type != "movie" && type != "show" && type != "episode") station(o) else null
        }
    }

    fun artist(o: JSONObject): Artist? {
        val key = o.str("ratingKey") ?: return null
        return Artist(
            ratingKey = key,
            title = o.str("title") ?: "Okänd artist",
            thumb = o.str("thumb"),
            art = o.str("art"),
            summary = o.str("summary"),
            genres = o.tags("Genre"),
            titleSort = o.str("titleSort"),
            country = o.tags("Country", 1).firstOrNull(),
        )
    }

    fun album(o: JSONObject): Album? {
        val key = o.str("ratingKey") ?: return null
        return Album(
            ratingKey = key,
            title = o.str("title") ?: "Okänt album",
            artist = o.str("parentTitle") ?: "Okänd artist",
            artistKey = o.str("parentRatingKey") ?: o.str("parentKey")?.substringAfterLast('/'),
            thumb = o.str("thumb") ?: o.str("parentThumb"),
            year = o.int("year") ?: o.str("originallyAvailableAt")?.take(4)?.toIntOrNull(),
            trackCount = o.int("leafCount"),
            genres = o.tags("Genre"),
            summary = o.str("summary"),
            studio = o.str("studio"),
            addedAt = o.long("addedAt"),
            subformat = o.tags("Subformat", 2).joinToString(", ").ifBlank { null },
            titleSort = o.str("titleSort"),
            userRating = o.double("userRating")?.toFloat(),
            ultraBlur = ultraBlur(o),
        )
    }

    fun track(o: JSONObject): Track? {
        val key = o.str("ratingKey") ?: return null
        val media = o.objects("Media").firstOrNull()
        val part = media?.objects("Part")?.firstOrNull()
        val albumArtist = o.str("grandparentTitle")
        val original = o.str("originalTitle")
        return Track(
            ratingKey = key,
            title = o.str("title") ?: "Okänd låt",
            artist = albumArtist ?: original ?: "Okänd artist",
            trackArtist = original?.takeIf { it != albumArtist },
            album = o.str("parentTitle") ?: "",
            albumKey = o.str("parentRatingKey") ?: o.str("parentKey")?.substringAfterLast('/'),
            artistKey = o.str("grandparentRatingKey") ?: o.str("grandparentKey")?.substringAfterLast('/'),
            thumb = o.str("parentThumb") ?: o.str("thumb") ?: o.str("grandparentThumb"),
            durationMs = o.long("duration") ?: media?.long("duration") ?: part?.long("duration") ?: 0L,
            index = o.int("index"),
            disc = o.int("parentIndex"),
            partKey = part?.str("key"),
            container = part?.str("container") ?: media?.str("container"),
            codec = media?.str("audioCodec"),
            bitrate = media?.int("bitrate"),
            userRating = o.double("userRating")?.toFloat(),
            year = o.int("parentYear") ?: o.int("year"),
            ratingCount = o.int("ratingCount"),
            viewCount = o.int("viewCount"),
            playQueueItemId = o.long("playQueueItemID"),
        )
    }

    fun playlist(o: JSONObject): Playlist? {
        val key = o.str("ratingKey") ?: return null
        return Playlist(
            ratingKey = key,
            title = o.str("title") ?: "Spellista",
            thumb = o.str("composite") ?: o.str("thumb"),
            trackCount = o.int("leafCount") ?: 0,
            durationMs = o.long("duration"),
            smart = o.bool("smart"),
            summary = o.str("summary"),
        )
    }

    fun station(o: JSONObject): Station? {
        val key = o.str("key") ?: return null
        return Station(
            ratingKey = o.str("ratingKey") ?: o.str("guid") ?: key,
            title = o.str("title") ?: "Radio",
            thumb = o.str("thumb") ?: o.str("composite"),
            key = key,
            subtitle = o.str("summary") ?: o.str("subtitle"),
        )
    }

    fun ultraBlur(o: JSONObject): UltraBlur? {
        val u = o.objects("UltraBlurColors").firstOrNull() ?: return null
        val tl = color(u.str("topLeft")) ?: return null
        val tr = color(u.str("topRight")) ?: tl
        val bl = color(u.str("bottomLeft")) ?: tl
        val br = color(u.str("bottomRight")) ?: tr
        return UltraBlur(tl, tr, bl, br)
    }

    /** "4c2a1e" → 0xFF4C2A1E. */
    fun color(hex: String?): Int? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6) return null
        val v = h.toLongOrNull(16) ?: return null
        return (0xFF000000L or v).toInt()
    }

    fun details(meta: JSONObject): TrackDetails {
        val streams = meta.objects("Media").flatMap { m -> m.objects("Part").flatMap { it.objects("Stream") } }
        val audio = streams.firstOrNull { it.int("streamType") == 2 }
        val lyric = streams
            .filter { it.int("streamType") == 4 && it.str("key") != null }
            // Tidsatt låttext (LRC) först.
            .sortedByDescending { (if (it.bool("timed")) 2 else 0) + (if (it.str("format") == "lrc") 1 else 0) }
            .firstOrNull()
        return TrackDetails(
            gainDb = audio?.double("gain"),
            albumGainDb = audio?.double("albumGain"),
            lyrics = lyric?.let { LyricsRef(it.str("key")!!, it.str("format") ?: it.str("codec"), it.bool("timed")) },
            ultraBlur = ultraBlur(meta),
            userRating = meta.double("userRating")?.toFloat(),
        )
    }

    fun hubs(root: JSONObject): List<Hub> = root.container().objects("Hub").mapNotNull { hub(it) }

    fun hub(h: JSONObject): Hub? {
        val context = h.str("context")
        val identifier = h.str("hubIdentifier") ?: context ?: return null
        val stations = context?.contains("station") == true || identifier.contains("station") ||
            context?.contains("mix") == true || identifier.contains("mix")
        val raw = h.objects("Metadata") + h.objects("Directory")
        val items = raw.mapNotNull { o ->
            // Vanliga spellistor i en mix-rad ska ändå vara spellistor.
            val forceStation = stations && o.str("type") != "album" && o.str("type") != "artist" &&
                o.str("type") != "track" && !(o.str("type") == "playlist" && !o.bool("radio") && o.str("key")?.startsWith("/playlists/") == true)
            item(o, forceStation)
        }
        if (items.isEmpty()) return null
        return Hub(identifier, h.str("title") ?: "", items, context)
    }

    fun metadata(root: JSONObject): List<JSONObject> = root.container().objects("Metadata")

    fun tracks(root: JSONObject): List<Track> = metadata(root).mapNotNull { track(it) }
    fun albums(root: JSONObject): List<Album> = metadata(root).mapNotNull { album(it) }
    fun artists(root: JSONObject): List<Artist> = metadata(root).mapNotNull { artist(it) }
    fun playlists(root: JSONObject): List<Playlist> = metadata(root).mapNotNull { playlist(it) }

    fun search(root: JSONObject): SearchResult {
        val hubs = root.container().objects("Hub")
        val all = hubs.flatMap { it.objects("Metadata") + it.objects("Directory") }
        return SearchResult(
            artists = all.filter { it.str("type") == "artist" }.mapNotNull { artist(it) },
            albums = all.filter { it.str("type") == "album" }.mapNotNull { album(it) },
            tracks = all.filter { it.str("type") == "track" }.mapNotNull { track(it) },
            playlists = all.filter { it.str("type") == "playlist" && (it.str("playlistType") ?: "audio") == "audio" && !it.bool("radio") }
                .mapNotNull { playlist(it) },
        )
    }

    fun playQueue(root: JSONObject): PlayQueue {
        val mc = root.container()
        val tracks = mc.objects("Metadata").mapNotNull { track(it) }
        val selectedId = mc.long("playQueueSelectedItemID")
        val selected = tracks.indexOfFirst { it.playQueueItemId == selectedId }.coerceAtLeast(0)
        return PlayQueue(mc.long("playQueueID") ?: 0L, tracks, selected)
    }

    /** Artistens radiostation, om servern har en ("includeStations=1"). */
    fun stationOf(root: JSONObject): Station? =
        root.findAll("Stations").flatMap { s -> s.objects("Metadata") + s.objects("Playlist") + listOf(s) }
            .firstNotNullOfOrNull { o -> o.str("key")?.takeIf { "station" in it }?.let { station(o) } }

    /** Populära låtar för en artist ("includePopularLeaves=1"). */
    fun popularOf(root: JSONObject): List<Track> =
        root.findAll("PopularLeaves").flatMap { it.objects("Metadata") }.mapNotNull { track(it) }
}
