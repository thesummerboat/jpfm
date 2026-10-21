package se.jpfm.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import okhttp3.Request
import se.jpfm.JpfmApp
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Ger omslag till Android Auto och mediaspelaren i notisen. Bilen kan inte prata med Plex
 * själv, så den får en content://-adress och vi hämtar bilden från servern (eller från
 * telefonen om albumet är nedladdat).
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val path = uri.getQueryParameter("p")?.takeIf { ALLOWED.matches(it) } ?: throw FileNotFoundException(uri.toString())
        val size = uri.getQueryParameter("s")?.toIntOrNull()?.coerceIn(64, 1200) ?: DEFAULT_SIZE
        val ctx = context ?: throw FileNotFoundException("Ingen context")
        val file = ensure(ctx.applicationContext as JpfmApp, path, size)
            ?: throw FileNotFoundException("Omslaget går inte att hämta just nu")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "se.jpfm.artwork"

        /** Bara omslag från musikbiblioteket – inte vilka adresser som helst på servern. */
        private val ALLOWED = Regex("""^/(library/metadata|library/collections|playlists)/\d+/(thumb|art|composite)(/\d+)?$""")
        const val DEFAULT_SIZE = 600

        fun uri(thumb: String, size: Int = DEFAULT_SIZE): Uri = Uri.Builder()
            .scheme("content").authority(AUTHORITY).appendPath("art")
            .appendQueryParameter("p", thumb)
            .appendQueryParameter("s", size.toString())
            .build()

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Omslag som sparats för offline. Raderas inte av systemet. */
        fun offlineFile(app: JpfmApp, thumb: String): File =
            File(File(app.filesDir, "offline-art").apply { mkdirs() }, hash(thumb) + ".jpg")

        private fun cacheFile(app: JpfmApp, thumb: String, size: Int): File =
            File(File(app.cacheDir, "art").apply { mkdirs() }, hash("$thumb@$size") + ".jpg")

        /** Hämtar omslaget om det inte redan finns på telefonen. Körs utanför huvudtråden. */
        fun ensure(app: JpfmApp, thumb: String, size: Int = DEFAULT_SIZE, offline: Boolean = false): File? {
            val off = offlineFile(app, thumb)
            if (off.exists() && off.length() > 0) return off
            val cached = cacheFile(app, thumb, size)
            if (!offline && cached.exists() && cached.length() > 0) return cached
            val target = if (offline) off else cached
            val g = app.graph
            val server = g.session.server ?: return null
            val url = g.client.imageUrl(server, thumb, if (offline) 800 else size)
            return try {
                g.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    resp.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return null
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        return null
                    }
                    target
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
