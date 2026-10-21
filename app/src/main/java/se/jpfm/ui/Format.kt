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

    /** Datum för ett poddavsnitt: "Idag", "Igår", "12 okt", "3 jan 2024". */
    fun date(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return ""
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        fun dayIndex(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
        val diff = dayIndex(today) - dayIndex(cal)
        val months = listOf("jan", "feb", "mar", "apr", "maj", "jun", "jul", "aug", "sep", "okt", "nov", "dec")
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val m = months[cal.get(Calendar.MONTH)]
        return when {
            diff == 0 -> "Idag"
            diff == 1 -> "Igår"
            cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "$d $m"
            else -> "$d $m ${cal.get(Calendar.YEAR)}"
        }
    }

    /** Kvar av ett avsnitt: "23 min kvar". */
    fun remaining(positionMs: Long, durationMs: Long): String {
        val left = ((durationMs - positionMs) / 60_000).coerceAtLeast(1)
        return if (left >= 60) "${left / 60} h ${left % 60} min kvar" else "$left min kvar"
    }

    /** "FLAC · 980 kbps" för låtens tekniska info. */
    fun quality(codec: String?, bitrate: Int?): String? {
        val c = codec?.uppercase() ?: return null
        return if (bitrate != null && bitrate > 0) "$c · $bitrate kbps" else c
    }
}
