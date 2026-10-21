package se.jpfm.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import se.jpfm.plex.PlexClient
import se.jpfm.plex.PlexSession
import java.io.IOException
import java.util.UUID

/**
 * Översätter appens egna adresser till serverns riktiga när musiken hämtas:
 *  - jpfm://track/123?part=/library/parts/… → originalfilen (direktuppspelning)
 *  - jpfm://transcode/123?kbps=320 → Plex omkodning (HLS, AAC)
 * Plex-nyckeln skickas som header, så att även omkodningens delfiler får den.
 */
class StreamResolver(private val session: PlexSession, private val client: PlexClient) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        val server = session.server
        return when {
            uri.scheme == TrackItems.SCHEME -> {
                server ?: throw IOException("Plex-servern går inte att nå")
                val real = when (uri.authority) {
                    "track" -> {
                        val part = uri.getQueryParameter("part") ?: throw IOException("Låten saknar fil")
                        Uri.parse(server.uri + part)
                    }
                    "transcode" -> transcodeUrl(server.uri, uri.lastPathSegment.orEmpty(), uri.getQueryParameter("kbps")?.toIntOrNull() ?: 320)
                    else -> throw IOException("Okänd adress: $uri")
                }
                dataSpec.buildUpon().setUri(real).setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers(server.token)).build()
            }
            // Delfiler från omkodningen pekar på servern: lägg till nyckeln.
            server != null && uri.toString().startsWith(server.uri) ->
                dataSpec.withAdditionalHeaders(headers(server.token))
            else -> dataSpec
        }
    }

    private fun headers(token: String): Map<String, String> = mapOf(
        "X-Plex-Token" to token,
        "X-Plex-Client-Identifier" to client.clientId,
        "X-Plex-Product" to PlexClient.PRODUCT,
        "X-Plex-Platform" to "Android",
    )

    private fun transcodeUrl(base: String, ratingKey: String, kbps: Int): Uri {
        val profile = "add-transcode-target(type=musicProfile&context=streaming&protocol=hls&container=mpegts&audioCodec=aac)" +
            "+add-limitation(scope=musicCodec&scopeName=*&type=upperBound&name=audio.bitrate&value=$kbps)"
        return Uri.parse("$base/music/:/transcode/universal/start.m3u8").buildUpon()
            .appendQueryParameter("path", "/library/metadata/$ratingKey")
            .appendQueryParameter("mediaIndex", "0")
            .appendQueryParameter("partIndex", "0")
            .appendQueryParameter("protocol", "hls")
            .appendQueryParameter("directPlay", "0")
            .appendQueryParameter("directStream", "0")
            .appendQueryParameter("directStreamAudio", "0")
            .appendQueryParameter("hasMDE", "1")
            .appendQueryParameter("musicBitrate", kbps.toString())
            .appendQueryParameter("maxAudioBitrate", kbps.toString())
            .appendQueryParameter("session", UUID.randomUUID().toString())
            .appendQueryParameter("X-Plex-Session-Identifier", UUID.randomUUID().toString())
            .appendQueryParameter("X-Plex-Client-Identifier", client.clientId)
            .appendQueryParameter("X-Plex-Product", PlexClient.PRODUCT)
            .appendQueryParameter("X-Plex-Platform", "Android")
            .appendQueryParameter("X-Plex-Client-Profile-Extra", profile)
            .build()
    }
}
