package se.jpfm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import se.jpfm.plex.PlexServer
import java.util.UUID

/** Ljudutjämning: auto = albumnivå när ett album spelas i ordning, annars per låt. */
enum class Leveling(val label: String) { AUTO("Automatisk"), TRACK("Per låt"), ALBUM("Per album"), OFF("Av") }

/** Strömningskvalitet. Original = filen som den är (FLAC, MP3 …), annars omkodning på servern. */
enum class Quality(val label: String, val kbps: Int?) {
    ORIGINAL("Original", null),
    HIGH("Hög (320 kbps)", 320),
    MEDIUM("Normal (192 kbps)", 192),
    LOW("Datasnål (128 kbps)", 128),
}

data class Settings(
    val wifiQuality: Quality = Quality.ORIGINAL,
    val mobileQuality: Quality = Quality.ORIGINAL,
    val leveling: Leveling = Leveling.AUTO,
    val downloadWifiOnly: Boolean = true,
    val dynamicColors: Boolean = true,
)

/** Små inställningar som ska överleva omstart. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jpfm", Context.MODE_PRIVATE)

    /** Unikt ID för den här installationen. Plex känner igen enheten på det. */
    val clientId: String
        get() = sp.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("client_id", it).apply()
        }

    var accountToken: String?
        get() = sp.getString("account_token", null)
        set(value) { sp.edit().putString("account_token", value).apply() }

    var userName: String?
        get() = sp.getString("user_name", null)
        set(value) { sp.edit().putString("user_name", value).apply() }

    var userThumb: String?
        get() = sp.getString("user_thumb", null)
        set(value) { sp.edit().putString("user_thumb", value).apply() }

    /** Senast fungerande server, med alla kända adresser, så att appen startar utan plex.tv. */
    var server: PlexServer?
        get() = sp.getString("server", null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                val c = o.optJSONArray("candidates") ?: JSONArray()
                PlexServer(
                    name = o.getString("name"),
                    uri = o.getString("uri"),
                    token = o.getString("token"),
                    machineId = o.optString("machineId"),
                    candidates = (0 until c.length()).map { c.getString(it) },
                )
            }.getOrNull()
        }
        set(value) {
            if (value == null) {
                sp.edit().remove("server").apply()
                return
            }
            val o = JSONObject()
                .put("name", value.name)
                .put("uri", value.uri)
                .put("token", value.token)
                .put("machineId", value.machineId)
                .put("candidates", JSONArray(value.candidates))
            sp.edit().putString("server", o.toString()).apply()
        }

    var sectionKey: String?
        get() = sp.getString("section_key", null)
        set(value) { sp.edit().putString("section_key", value).apply() }

    var sectionUuid: String?
        get() = sp.getString("section_uuid", null)
        set(value) { sp.edit().putString("section_uuid", value).apply() }

    /** Senaste sökningarna, nyast först. */
    var recentSearches: List<String>
        get() = sp.getString("recent_searches", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) { sp.edit().putString("recent_searches", value.take(8).joinToString("\n")).apply() }

    /** Vald flik i biblioteket (artister, album, spellistor). */
    var libraryTab: Int
        get() = sp.getInt("library_tab", 0)
        set(value) { sp.edit().putInt("library_tab", value).apply() }

    /** Uppspelningshastighet för poddar. */
    var podcastSpeed: Float
        get() = sp.getFloat("podcast_speed", 1f)
        set(value) { sp.edit().putFloat("podcast_speed", value).apply() }

    /** Vald del av radiofliken: 0 = kanaler, 1 = poddar. */
    var radioTab: Int
        get() = sp.getInt("radio_tab", 0)
        set(value) { sp.edit().putInt("radio_tab", value).apply() }

    var albumSort: String
        get() = sp.getString("album_sort", "title") ?: "title"
        set(value) { sp.edit().putString("album_sort", value).apply() }

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun readSettings() = Settings(
        wifiQuality = enumOr(sp.getString("wifi_quality", null), Quality.ORIGINAL),
        mobileQuality = enumOr(sp.getString("mobile_quality", null), Quality.ORIGINAL),
        leveling = enumOr(sp.getString("leveling", null), Leveling.AUTO),
        downloadWifiOnly = sp.getBoolean("download_wifi_only", true),
        dynamicColors = sp.getBoolean("dynamic_colors", true),
    )

    fun updateSettings(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        sp.edit()
            .putString("wifi_quality", s.wifiQuality.name)
            .putString("mobile_quality", s.mobileQuality.name)
            .putString("leveling", s.leveling.name)
            .putBoolean("download_wifi_only", s.downloadWifiOnly)
            .putBoolean("dynamic_colors", s.dynamicColors)
            .apply()
        _settings.value = s
    }

    fun clearAccount() {
        sp.edit()
            .remove("account_token").remove("server").remove("section_key").remove("section_uuid")
            .remove("user_name").remove("user_thumb")
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
