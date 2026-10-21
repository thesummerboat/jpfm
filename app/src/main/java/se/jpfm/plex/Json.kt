package se.jpfm.plex

import org.json.JSONArray
import org.json.JSONObject

/** Små hjälpare för Plex JSON: fält kan saknas, vara null eller tomma. */

internal fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

internal fun JSONObject.int(key: String): Int? =
    if (has(key) && !isNull(key)) {
        when (val v = opt(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: v.toDoubleOrNull()?.toInt()
            else -> null
        }
    } else null

internal fun JSONObject.long(key: String): Long? =
    if (has(key) && !isNull(key)) {
        when (val v = opt(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong()
            else -> null
        }
    } else null

internal fun JSONObject.double(key: String): Double? =
    if (has(key) && !isNull(key)) {
        when (val v = opt(key)) {
            is Number -> v.toDouble().takeIf { !it.isNaN() }
            is String -> v.toDoubleOrNull()
            else -> null
        }
    } else null

internal fun JSONObject.bool(key: String): Boolean =
    when (val v = opt(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", ignoreCase = true)
        else -> false
    }

/** Plex skickar ibland ett ensamt objekt där en lista väntas. Ger alltid en lista. */
internal fun JSONObject.objects(key: String): List<JSONObject> {
    if (!has(key) || isNull(key)) return emptyList()
    return when (val v = opt(key)) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
        is JSONObject -> listOf(v)
        else -> emptyList()
    }
}

internal fun JSONObject.container(): JSONObject = optJSONObject("MediaContainer") ?: this

/** Taggar som Genre/Mood/Style: [{"tag":"Rock"}, …] → ["Rock", …]. */
internal fun JSONObject.tags(key: String, limit: Int = 8): List<String> =
    objects(key).mapNotNull { it.str("tag") }.take(limit)

/**
 * Letar upp alla listor med ett visst namn var som helst i svaret. Plex lägger t.ex. "Stations"
 * och "PopularLeaves" på olika nivåer beroende på serverversion.
 */
internal fun JSONObject.findAll(name: String, depth: Int = 0): List<JSONObject> {
    if (depth > 6) return emptyList()
    val out = mutableListOf<JSONObject>()
    val keys = keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = opt(k)
        if (k == name) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) v.optJSONObject(i)?.let { out += it }
                is JSONObject -> out += v
            }
        } else {
            when (v) {
                is JSONObject -> out += v.findAll(name, depth + 1)
                is JSONArray -> for (i in 0 until v.length()) v.optJSONObject(i)?.let { out += it.findAll(name, depth + 1) }
            }
        }
    }
    return out
}
