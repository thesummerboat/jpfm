package se.jpfm.ui

import java.util.Calendar
import java.util.Locale

/** Text som visas i appen: tider, antal och hälsningar. Ingen Android här. */
object Format {
    /** 245000 → "4:05", 3725000 → "1:02:05". */
    fun duration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Längd på ett album eller en spellista: "47 min", "2 h 13 min". */
    fun longDuration(ms: Long): String {
        val min = (ms / 60_000).toInt()
        if (min < 60) return "$min min"
        val h = min / 60
        val m = min % 60
        return if (m == 0) "$h h" else "$h h $m min"
    }

    fun tracks(n: Int): String = if (n == 1) "1 låt" else "$n låtar"

    fun albums(n: Int): String = if (n == 1) "1 album" else "$n album"

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale("sv"), "%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale("sv"), "%.0f MB", b / (1L shl 20).toDouble())
        else -> "${b / 1024} kB"
    }

    fun greeting(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String = when (hour) {
        in 5..9 -> "God morgon"
        in 10..11 -> "God förmiddag"
        in 12..17 -> "God eftermiddag"
        in 18..22 -> "God kväll"
        else -> "God natt"
    }

    /** "FLAC · 980 kbps" för låtens tekniska info. */
    fun quality(codec: String?, bitrate: Int?): String? {
        val c = codec?.uppercase() ?: return null
        return if (bitrate != null && bitrate > 0) "$c · $bitrate kbps" else c
    }
}
