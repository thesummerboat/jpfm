package se.jpfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.jpfm.plex.ChartSong
import se.jpfm.request.LidarrAlbum
import se.jpfm.request.LidarrConfig
import se.jpfm.request.LidarrException
import se.jpfm.request.LidarrSetup
import se.jpfm.request.RequestMatch
import se.jpfm.request.RequestState
import se.jpfm.request.SongRequest
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.EmptyBox
import se.jpfm.ui.Jp
import se.jpfm.ui.Load
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.Screen
import se.jpfm.ui.SectionHeader

// ---------- Önska musik ----------

/**
 * Sök i Lidarr och önska album eller singlar som inte finns hos dig, och se hur det går med
 * dina önskningar. Från topplistan slår vi först upp vilken skiva låten ligger på.
 */
@Composable
fun RequestScreen(vm: AppViewModel, initialQuery: String, song: ChartSong?) {
    val g = LocalGraph.current
    val cfg by g.requests.config.collectAsState()
    val requests by g.requests.requests.collectAsState()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var albumHint by rememberSaveable { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Load<List<LidarrAlbum>>?>(null) }
    var unreachable by remember { mutableStateOf(false) }

    // Låt från topplistan: vilket album eller vilken singel ligger den på?
    LaunchedEffect(song?.appleId) {
        val id = song?.appleId ?: return@LaunchedEffect
        if (albumHint != null) return@LaunchedEffect
        val album = g.requests.appleAlbumOf(id) ?: return@LaunchedEffect
        albumHint = album
        query = "${song.artist} ${RequestMatch.cleanAlbum(album)}"
    }

    LaunchedEffect(query, cfg) {
        val q = query.trim()
        if (cfg == null || q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(500)
        results = Load.Loading
        unreachable = false
        results = try {
            val fallback = albumHint?.let { RequestMatch.cleanAlbum(it) } ?: song?.title
            val list = g.requests.lookup(q, fallback)
            Load.Ready(if (song != null) RequestMatch.rank(list, song.artist, albumHint, song.title) else list)
        } catch (e: LidarrException) {
            Load.Failed(e.message ?: "Lidarr svarade inte")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            unreachable = true
            Load.Failed(e.message ?: "Når inte Lidarr")
        }
    }

    // Håll koll på hur det går medan sidan visas.
    LaunchedEffect(cfg) {
        if (cfg == null) return@LaunchedEffect
        while (true) {
            g.requests.sync()
            delay(15_000)
        }
    }

    GridPage("Önska musik", onBack = { vm.nav.back() }) {
        if (cfg == null) {
            EmptyBox(
                Icons.Rounded.CloudDownload,
                "Koppla Lidarr",
                "Önska album och låtar som inte finns hos dig. JPFM skickar önskningen till Lidarr på din server, som laddar ner musiken och lägger den där Plex hittar den.",
                action = {
                    Button(
                        onClick = { vm.nav.push(Screen.LidarrSetup) },
                        colors = ButtonDefaults.buttonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent),
                    ) { Text("Koppla Lidarr") }
                },
            )
            return@GridPage
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "search") { SearchField(query, { query = it; albumHint = null }, "Sök artist, album eller låt") }
            if (song != null) {
                item(key = "song") {
                    Text(
                        "Du önskar låten ”${song.title}” av ${song.artist}." +
                            (albumHint?.let { " Den ligger på ”${RequestMatch.cleanAlbum(it)}”." } ?: "") +
                            " Lidarr hämtar hela skivan eller singeln.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Jp.TextDim,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }
            when (val r = results) {
                null -> Unit
                is Load.Loading -> item(key = "loading") { LoadingBox() }
                is Load.Failed -> item(key = "failed") {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(r.message, style = MaterialTheme.typography.bodyMedium, color = Jp.TextDim)
                        if (unreachable && query.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = {
                                vm.requestLater(query.trim(), song?.artist ?: "", song?.title)
                            }) {
                                Icon(Icons.Rounded.Schedule, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Spara och skicka senare")
                            }
                        }
                    }
                }
                is Load.Ready -> {
                    if (r.data.isEmpty()) {
                        item(key = "empty") { EmptyBox(Icons.Rounded.Album, "Inget hittat", "Prova bara albumets eller artistens namn.") }
                    }
                    items(r.data.take(30), key = { "r:${it.foreignAlbumId}" }) { a ->
                        val req = requests.firstOrNull { it.id == a.foreignAlbumId }
                        LidarrAlbumRow(
                            a,
                            best = song != null && a === r.data.firstOrNull(),
                            request = req,
                            onRequest = { vm.requestAlbum(a, song?.title) },
                        )
                    }
                }
            }
            if (requests.isNotEmpty()) {
                item(key = "mine-h") {
                    SectionHeader("Dina önskningar", action = if (requests.any { it.state == RequestState.DONE }) "Rensa klara" else null) {
                        g.requests.clearFinished()
                    }
                }
                items(requests, key = { "m:${it.id}" }) { r -> RequestRow(r, onRemove = { g.requests.remove(r.id) }) }
            }
        }
    }
}

@Composable
private fun LidarrAlbumRow(a: LidarrAlbum, best: Boolean, request: SongRequest?, onRequest: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(a.cover, Modifier.size(60.dp), size = 180, shape = RoundedCornerShape(6.dp), placeholder = Icons.Rounded.Album)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (best) {
                Text("BÄSTA TRÄFF", style = MaterialTheme.typography.labelSmall, color = Jp.Accent, fontWeight = FontWeight.Bold)
            }
            Text(a.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(a.artist.ifBlank { null }, a.typeLabel, a.year?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Jp.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            a.complete -> StatusChip("Finns", Jp.TextDim)
            request != null && request.state != RequestState.FAILED -> StatusChip(request.state.label, Jp.Accent)
            else -> FilledTonalButton(
                onClick = onRequest,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("Önska", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Jp.SurfaceHigher).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** En önskan och hur det går: söker, laddar ner (med procent), klar. */
@Composable
fun RequestRow(r: SongRequest, onRemove: (() -> Unit)?, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Artwork(r.cover, Modifier.size(52.dp), size = 156, shape = RoundedCornerShape(6.dp), placeholder = Icons.Rounded.Album)
            if (r.state == RequestState.DONE) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp).clip(RoundedCornerShape(50)).background(Jp.Accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = Jp.OnAccent, modifier = Modifier.size(12.dp)) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.song ?: r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val what = listOfNotNull(r.artist.ifBlank { null }, if (r.song != null && r.song != r.title) r.title else null).joinToString(" · ")
            if (what.isNotBlank()) {
                Text(what, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val status = when (r.state) {
                RequestState.DOWNLOADING -> "${r.state.label} ${(r.progress * 100).toInt()} %"
                else -> r.state.label
            } + (r.message?.let { " · $it" } ?: "")
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = when (r.state) {
                    RequestState.DONE -> Jp.Accent
                    RequestState.FAILED -> Jp.Error
                    else -> Jp.TextFaint
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (r.state == RequestState.DOWNLOADING || r.state == RequestState.IMPORTING) {
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { r.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Jp.Accent,
                    trackColor = Jp.SurfaceHigher,
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Ta bort", tint = Jp.TextFaint) }
        }
    }
}

// ---------- Koppla Lidarr ----------

@Composable
fun LidarrSetupScreen(vm: AppViewModel) {
    val g = LocalGraph.current
    val current by g.requests.config.collectAsState()
    var url by rememberSaveable { mutableStateOf(current?.url ?: "http://") }
    var key by rememberSaveable { mutableStateOf(current?.apiKey ?: "") }
    var setup by remember { mutableStateOf<LidarrSetup?>(null) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var root by rememberSaveable { mutableStateOf(current?.rootFolder) }
    var quality by rememberSaveable { mutableStateOf(current?.qualityProfileId) }
    var metadata by rememberSaveable { mutableStateOf(current?.metadataProfileId) }
    val scope = rememberCoroutineScope()

    fun test() {
        testing = true
        error = null
        scope.launch {
            try {
                val s = g.requests.testConnection(url, key)
                setup = s
                val r = s.roots.firstOrNull { it.path == root } ?: s.roots.firstOrNull()
                root = r?.path
                if (quality == null || s.quality.none { it.id == quality }) quality = r?.defaultQuality?.takeIf { id -> s.quality.any { it.id == id } } ?: s.quality.firstOrNull()?.id
                if (metadata == null || s.metadata.none { it.id == metadata }) metadata = r?.defaultMetadata?.takeIf { id -> s.metadata.any { it.id == id } } ?: s.metadata.firstOrNull()?.id
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                setup = null
                error = e.message ?: "Gick inte att ansluta"
            } finally {
                testing = false
            }
        }
    }

    // Redan kopplad: hämta profilerna direkt så att de går att ändra.
    LaunchedEffect(Unit) { if (current != null) test() }

    GridPage("Lidarr", onBack = { vm.nav.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Text(
                "Lidarr laddar ner musik åt dig på servern och lägger den där Plex hittar den. Installera Lidarr på Unraid (Apps → sök \"Lidarr\"), " +
                    "låt den spara i samma musikmapp som Plex läser, och fyll i adressen här.",
                style = MaterialTheme.typography.bodyMedium,
                color = Jp.TextDim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Field("Adress", url, { url = it; setup = null }, "http://192.168.1.10:8686 eller http://tower:8686", KeyboardType.Uri)
            Hint("Hemma: serverns adress. Borta: Tailscale-namnet eller -adressen (100.x.y.z), med Tailscale igång på telefonen.")
            Field("API-nyckel", key, { key = it.trim(); setup = null }, "Finns i Lidarr under Settings → General", KeyboardType.Password, secret = true)
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { test() },
                    enabled = !testing && url.length > 8 && key.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent),
                ) { Text(if (testing) "Ansluter …" else "Testa anslutningen") }
                Spacer(Modifier.width(12.dp))
                setup?.let { Text("Ansluten till Lidarr ${it.version}", style = MaterialTheme.typography.bodySmall, color = Jp.Accent) }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Jp.Error, modifier = Modifier.padding(horizontal = 20.dp)) }

            setup?.let { s ->
                if (s.roots.isEmpty()) {
                    Hint("Lidarr har ingen musikmapp än. Lägg till en under Settings → Media Management → Root Folders (samma mapp som Plex läser).")
                } else {
                    Group("Var musiken hamnar")
                    Choice("Musikmapp", s.roots.firstOrNull { it.path == root }?.let { r -> "${r.name} (${r.path})" } ?: "Välj", s.roots.map { "${it.name} (${it.path})" }) { i ->
                        root = s.roots[i].path
                    }
                    Choice("Kvalitetsprofil", s.quality.firstOrNull { it.id == quality }?.name ?: "Välj", s.quality.map { it.name }) { i -> quality = s.quality[i].id }
                    Choice("Metadataprofil", s.metadata.firstOrNull { it.id == metadata }?.name ?: "Välj", s.metadata.map { it.name }) { i -> metadata = s.metadata[i].id }
                    Hint("Vill du kunna önska singlar och EP: välj en metadataprofil som tar med Single och EP (Settings → Profiles i Lidarr).")
                    val ready = root != null && quality != null && metadata != null
                    Button(
                        onClick = {
                            g.requests.saveConfig(LidarrConfig(LidarrConfig.normalizeUrl(url), key.trim(), root!!, quality!!, metadata!!))
                            vm.toast("Lidarr är kopplat")
                            vm.nav.back()
                        },
                        enabled = ready,
                        colors = ButtonDefaults.buttonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(),
                    ) { Text("Spara", fontWeight = FontWeight.Bold) }
                }
            }
            if (current != null) {
                TextButton(
                    onClick = {
                        g.requests.saveConfig(null)
                        vm.toast("Lidarr är bortkopplat")
                        vm.nav.back()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) { Text("Koppla från Lidarr", color = Jp.Error) }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint, color = Jp.TextFaint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Jp.Accent, focusedLabelColor = Jp.Accent, cursorColor = Jp.Accent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
