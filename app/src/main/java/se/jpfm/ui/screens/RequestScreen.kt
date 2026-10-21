package se.jpfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.jpfm.plex.ChartSong
import se.jpfm.request.LidarrSetup
import se.jpfm.request.RemoteRequest
import se.jpfm.request.RequestMatch
import se.jpfm.request.RequestState
import se.jpfm.request.ServerAlbum
import se.jpfm.request.ServerUnreachable
import se.jpfm.request.ServerUser
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
 * Sök och önska album eller singlar som inte finns hos dig, och se hur det går. Ägaren ser
 * också allas önskningar och godkänner eller nekar dem. Från topplistan slår vi först upp
 * vilken skiva låten ligger på.
 */
@Composable
fun RequestScreen(vm: AppViewModel, initialQuery: String, song: ChartSong?) {
    val g = LocalGraph.current
    val url by g.requests.url.collectAsState()
    val me by g.requests.me.collectAsState()
    val requests by g.requests.requests.collectAsState()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var albumHint by rememberSaveable { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Load<List<ServerAlbum>>?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    var denying by remember { mutableStateOf<RemoteRequest?>(null) }

    // Låt från topplistan: vilket album eller vilken singel ligger den på?
    LaunchedEffect(song?.appleId) {
        val id = song?.appleId ?: return@LaunchedEffect
        if (albumHint != null) return@LaunchedEffect
        val album = g.requests.appleAlbumOf(id) ?: return@LaunchedEffect
        albumHint = album
        query = "${song.artist} ${RequestMatch.cleanAlbum(album)}"
    }

    LaunchedEffect(query, url) {
        val q = query.trim()
        if (url == null || q.length < 2) {
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
        } catch (e: ServerUnreachable) {
            unreachable = true
            Load.Failed(e.message ?: "Når inte servern")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Load.Failed(e.message ?: "Något gick fel")
        }
    }

    // Håll koll på hur det går medan sidan visas.
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        while (true) {
            g.requests.sync()
            delay(15_000)
        }
    }

    GridPage("Önska musik", onBack = { vm.nav.back() }) {
        if (url == null) {
            EmptyBox(
                Icons.Rounded.CloudDownload,
                "Koppla JPFM-servern",
                "Önska album och låtar som inte finns hos dig. Önskningen går till JPFM-servern hemma, " +
                    "som skickar den vidare till Lidarr – efter ditt godkännande om någon annan önskar.",
                action = {
                    Button(
                        onClick = { vm.nav.push(Screen.RequestSetup) },
                        colors = ButtonDefaults.buttonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent),
                    ) { Text("Koppla") }
                },
            )
            return@GridPage
        }
        val m = me
        val toApprove = if (m?.isOwner == true) requests.filter { it.state == RequestState.PENDING && !it.mine } else emptyList()
        val rest = requests.filter { it !in toApprove }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (m != null && !m.lidarr) {
                item(key = "nolidarr") {
                    Notice(
                        if (m.isOwner) "Koppla Lidarr till JPFM-servern för att kunna önska." else "${m.ownerName ?: "Ägaren"} har inte kopplat Lidarr än.",
                        if (m.isOwner) "Koppla Lidarr" else null,
                    ) { vm.nav.push(Screen.RequestSetup) }
                }
            }
            if (m?.blocked == true) {
                item(key = "blocked") { Notice("Du kan inte önska musik här.", null) {} }
            }
            if (toApprove.isNotEmpty()) {
                item(key = "approve-h") { SectionHeader("Att godkänna", subtitle = "Önskningar från de du delar med") }
                items(toApprove, key = { "a:${it.id}" }) { r ->
                    ApproveRow(r, onApprove = { vm.decide(r, true) }, onDeny = { denying = r })
                }
            }
            item(key = "search") { SearchField(query, { query = it; albumHint = null }, "Sök artist, album eller låt") }
            if (song != null) {
                item(key = "song") {
                    Text(
                        "Du önskar låten ”${song.title}” av ${song.artist}." +
                            (albumHint?.let { " Den ligger på ”${RequestMatch.cleanAlbum(it)}”." } ?: "") +
                            " Hela skivan eller singeln hämtas.",
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
                            OutlinedButton(onClick = { vm.requestLater(query.trim(), song?.artist ?: "", song?.title) }) {
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
                        // Det vi vet nu går före det som stod i sökresultatet.
                        val req = requests.firstOrNull { it.foreignAlbumId == a.foreignAlbumId } ?: a.request
                        AlbumResultRow(
                            a,
                            best = song != null && a === r.data.firstOrNull(),
                            request = req,
                            canRequest = m?.canRequest == true && m.lidarr,
                            onRequest = { vm.requestAlbum(a, song?.title) },
                        )
                    }
                }
            }
            if (rest.isNotEmpty()) {
                item(key = "mine-h") {
                    SectionHeader(if (m?.isOwner == true) "Önskningar" else "Dina önskningar")
                }
                items(rest, key = { "m:${it.id}" }) { r ->
                    RequestRow(
                        r,
                        showWho = m?.isOwner == true && !r.mine,
                        onRemove = if (r.mine || m?.isOwner == true) ({ vm.removeRequest(r) }) else null,
                    )
                }
            }
        }
    }

    denying?.let { r ->
        var reason by remember(r.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { denying = null },
            containerColor = Jp.SurfaceHigh,
            title = { Text("Neka ”${r.title}”?") },
            text = {
                Column {
                    Text("${r.userName} får se att önskan nekades.", color = Jp.TextDim)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Skäl (valfritt)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Jp.Accent, focusedLabelColor = Jp.Accent, cursorColor = Jp.Accent),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.decide(r, false, reason.trim().ifBlank { null })
                    denying = null
                }) { Text("Neka", color = Jp.Error) }
            },
            dismissButton = { TextButton(onClick = { denying = null }) { Text("Avbryt") } },
        )
    }
}

@Composable
private fun Notice(text: String, action: String?, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Jp.SurfaceHigh)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Jp.Accent) }
    }
}

@Composable
private fun AlbumResultRow(a: ServerAlbum, best: Boolean, request: RemoteRequest?, canRequest: Boolean, onRequest: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(a.cover, Modifier.size(60.dp), size = 180, shape = RoundedCornerShape(6.dp), placeholder = Icons.Rounded.Album)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (best) Text("BÄSTA TRÄFF", style = MaterialTheme.typography.labelSmall, color = Jp.Accent, fontWeight = FontWeight.Bold)
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
            request != null && request.state != RequestState.FAILED && request.state != RequestState.DENIED ->
                StatusChip(request.state.label, Jp.Accent)
            canRequest -> FilledTonalButton(
                onClick = onRequest,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("Önska", fontWeight = FontWeight.Bold) }
            else -> Unit
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 2,
        modifier = Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(12.dp)).background(Jp.SurfaceHigher).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** En önskan från någon annan som väntar på ditt beslut. */
@Composable
private fun ApproveRow(r: RemoteRequest, onApprove: () -> Unit, onDeny: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(r.cover, Modifier.size(56.dp), size = 168, shape = RoundedCornerShape(6.dp), placeholder = Icons.Rounded.Album)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.song ?: r.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(r.artist.ifBlank { null }, if (r.song != null && r.song != r.title) r.title else se.jpfm.request.typeLabel(r.type)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Jp.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Önskad av ${r.userName}", style = MaterialTheme.typography.labelMedium, color = Jp.Accent)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            OutlinedButton(onClick = onDeny) { Text("Neka", color = Jp.Error) }
            Button(onClick = onApprove, colors = ButtonDefaults.buttonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent)) {
                Text("Godkänn", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** En önskan och hur det går: väntar, nekad, letar, laddar ner (med procent), klar. */
@Composable
fun RequestRow(r: RemoteRequest, onRemove: (() -> Unit)?, onClick: (() -> Unit)? = null, showWho: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Artwork(r.cover, Modifier.size(52.dp), size = 156, shape = RoundedCornerShape(6.dp), placeholder = Icons.Rounded.Album)
            val badge = when (r.state) {
                RequestState.DONE -> Icons.Rounded.Check to Jp.Accent
                RequestState.DENIED -> Icons.Rounded.Block to Jp.Error
                else -> null
            }
            if (badge != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp).clip(CircleShape).background(badge.second),
                    contentAlignment = Alignment.Center,
                ) { Icon(badge.first, null, tint = Jp.OnAccent, modifier = Modifier.size(12.dp)) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.song ?: r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val what = listOfNotNull(
                r.artist.ifBlank { null },
                if (r.song != null && r.song != r.title) r.title else null,
                if (showWho && r.userName.isNotBlank()) "från ${r.userName}" else null,
            ).joinToString(" · ")
            if (what.isNotBlank()) {
                Text(what, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val status = when (r.state) {
                RequestState.DOWNLOADING -> "${r.state.label} ${(r.progress * 100).toInt()} %"
                RequestState.DENIED -> r.state.label + (r.reason?.let { ": $it" } ?: "")
                else -> r.state.label
            } + (r.message?.let { " · $it" } ?: "")
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = when (r.state) {
                    RequestState.DONE -> Jp.Accent
                    RequestState.FAILED, RequestState.DENIED -> Jp.Error
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

// ---------- Koppla JPFM-servern (och Lidarr för ägaren) ----------

@Composable
fun RequestSetupScreen(vm: AppViewModel) {
    val g = LocalGraph.current
    val currentUrl by g.requests.url.collectAsState()
    val me by g.requests.me.collectAsState()
    val scope = rememberCoroutineScope()

    var serverUrl by rememberSaveable { mutableStateOf(currentUrl ?: "http://") }
    var connecting by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }

    // Lidarr (bara ägaren).
    var lidarrUrl by rememberSaveable { mutableStateOf("http://") }
    var key by rememberSaveable { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf<LidarrSetup?>(null) }
    var testing by remember { mutableStateOf(false) }
    var lidarrError by remember { mutableStateOf<String?>(null) }
    var root by rememberSaveable { mutableStateOf<String?>(null) }
    var quality by rememberSaveable { mutableStateOf<Int?>(null) }
    var metadata by rememberSaveable { mutableStateOf<Int?>(null) }
    var users by remember { mutableStateOf<List<ServerUser>>(emptyList()) }

    fun loadOwnerStuff() {
        scope.launch {
            runCatching { g.requests.lidarrSettings() }.getOrNull()?.let { s ->
                if (s.url != null) lidarrUrl = s.url
                hasKey = s.hasKey
                root = s.rootFolder
                quality = s.qualityProfileId
                metadata = s.metadataProfileId
            }
            users = runCatching { g.requests.users() }.getOrDefault(emptyList())
        }
    }

    fun connect() {
        connecting = true
        serverError = null
        scope.launch {
            try {
                val m = g.requests.connect(serverUrl)
                if (m.isOwner) loadOwnerStuff()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                serverError = e.message ?: "Gick inte att ansluta"
            } finally {
                connecting = false
            }
        }
    }

    fun test() {
        testing = true
        lidarrError = null
        scope.launch {
            try {
                val s = g.requests.testLidarr(lidarrUrl, key.ifBlank { null })
                setup = s
                val r = s.roots.firstOrNull { it.path == root } ?: s.roots.firstOrNull()
                root = r?.path
                if (quality == null || s.quality.none { it.id == quality }) quality = r?.defaultQuality?.takeIf { id -> s.quality.any { it.id == id } } ?: s.quality.firstOrNull()?.id
                if (metadata == null || s.metadata.none { it.id == metadata }) metadata = r?.defaultMetadata?.takeIf { id -> s.metadata.any { it.id == id } } ?: s.metadata.firstOrNull()?.id
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                setup = null
                lidarrError = e.message ?: "Gick inte att ansluta"
            } finally {
                testing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (currentUrl != null) {
            g.requests.sync()
            if (g.requests.me.value?.isOwner == true) loadOwnerStuff()
        }
    }

    GridPage("Önska musik", onBack = { vm.nav.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Group("JPFM-servern")
            Text(
                "Önskningar går via JPFM-servern på din Unraid. Den kollar med Plex vem du är, låter ägaren godkänna andras önskningar och skickar dem till Lidarr.",
                style = MaterialTheme.typography.bodyMedium,
                color = Jp.TextDim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Field("Serverns adress", serverUrl, { serverUrl = it }, "http://tower:8687", KeyboardType.Uri)
            Hint("Med Tailscale på telefonen fungerar samma adress även borta (t.ex. http://tower:8687 eller http://100.x.y.z:8687).")
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { connect() },
                    enabled = !connecting && serverUrl.length > 8,
                    colors = ButtonDefaults.buttonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent),
                ) { Text(if (connecting) "Ansluter …" else if (currentUrl != null) "Anslut igen" else "Anslut") }
                Spacer(Modifier.width(12.dp))
                me?.let { m ->
                    Text(
                        when {
                            m.isOwner -> "Ansluten som ägare"
                            m.blocked -> "Blockerad"
                            m.autoApprove -> "Ansluten – dina önskningar godkänns direkt"
                            else -> "Ansluten – ${m.ownerName ?: "ägaren"} godkänner dina önskningar"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Jp.Accent,
                    )
                }
            }
            serverError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Jp.Error, modifier = Modifier.padding(horizontal = 20.dp)) }

            if (me?.isOwner == true) {
                Group("Lidarr")
                Field("Lidarrs adress (sett från servern)", lidarrUrl, { lidarrUrl = it; setup = null }, "http://192.168.1.10:8686", KeyboardType.Uri)
                Field(
                    "API-nyckel",
                    key,
                    { key = it.trim(); setup = null },
                    if (hasKey) "Sparad – fyll bara i för att byta" else "Lidarr → Settings → General",
                    KeyboardType.Password,
                    secret = true,
                )
                Hint("Nyckeln sparas på JPFM-servern, aldrig i någons telefon.")
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { test() }, enabled = !testing && lidarrUrl.length > 8 && (key.isNotBlank() || hasKey)) {
                        Text(if (testing) "Ansluter …" else "Testa anslutningen")
                    }
                    Spacer(Modifier.width(12.dp))
                    setup?.let { Text("Lidarr ${it.version}", style = MaterialTheme.typography.bodySmall, color = Jp.Accent) }
                    if (setup == null && me?.lidarr == true) Text("Kopplad", style = MaterialTheme.typography.bodySmall, color = Jp.Accent)
                }
                lidarrError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Jp.Error, modifier = Modifier.padding(horizontal = 20.dp)) }
                setup?.let { s ->
                    if (s.roots.isEmpty()) {
                        Hint("Lidarr har ingen musikmapp än. Lägg till en under Settings → Media Management → Root Folders (samma mapp som Plex läser).")
                    } else {
                        Choice("Musikmapp", s.roots.firstOrNull { it.path == root }?.let { r -> "${r.name} (${r.path})" } ?: "Välj", s.roots.map { "${it.name} (${it.path})" }) { i ->
                            root = s.roots[i].path
                        }
                        Choice("Kvalitetsprofil", s.quality.firstOrNull { it.id == quality }?.name ?: "Välj", s.quality.map { it.name }) { i -> quality = s.quality[i].id }
                        Choice("Metadataprofil", s.metadata.firstOrNull { it.id == metadata }?.name ?: "Välj", s.metadata.map { it.name }) { i -> metadata = s.metadata[i].id }
                        Hint("Vill du kunna önska singlar och EP: välj en metadataprofil som tar med Single och EP (Settings → Profiles i Lidarr).")
                        Button(
                            onClick = {
                                val r = root
                                val q = quality
                                val md = metadata
                                if (r != null && q != null && md != null) {
                                    scope.launch {
                                        try {
                                            g.requests.saveLidarr(lidarrUrl, key.ifBlank { null }, r, q, md)
                                            hasKey = true
                                            key = ""
                                            setup = null
                                            vm.toast("Lidarr är kopplat")
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            lidarrError = e.message
                                        }
                                    }
                                }
                            },
                            enabled = root != null && quality != null && metadata != null,
                            colors = ButtonDefaults.buttonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(),
                        ) { Text("Spara", fontWeight = FontWeight.Bold) }
                    }
                }

                val others = users.filter { !it.owner }
                Group("De du delar med")
                if (others.isEmpty()) {
                    Hint("När någon du delar Plex-biblioteket med ansluter till JPFM-servern dyker de upp här. Deras önskningar väntar på ditt godkännande.")
                }
                others.forEach { u ->
                    UserRow(
                        u,
                        onAutoApprove = { on ->
                            scope.launch {
                                runCatching { g.requests.updateUser(u.id, autoApprove = on) }
                                    .onSuccess { users = users.map { if (it.id == u.id) it.copy(autoApprove = on) else it } }
                                    .onFailure { vm.toast(it.message ?: "Gick inte") }
                            }
                        },
                        onBlock = { on ->
                            scope.launch {
                                runCatching { g.requests.updateUser(u.id, blocked = on) }
                                    .onSuccess { users = users.map { if (it.id == u.id) it.copy(blocked = on) else it } }
                                    .onFailure { vm.toast(it.message ?: "Gick inte") }
                            }
                        },
                    )
                }
            }

            if (currentUrl != null) {
                TextButton(
                    onClick = {
                        g.requests.disconnect()
                        vm.toast("JPFM-servern är bortkopplad")
                        vm.nav.back()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                ) { Text("Koppla från JPFM-servern", color = Jp.Error) }
            }
        }
    }
}

@Composable
private fun UserRow(u: ServerUser, onAutoApprove: (Boolean) -> Unit, onBlock: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(u.thumb, Modifier.size(40.dp), size = 120, shape = CircleShape)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(u.name, style = MaterialTheme.typography.bodyLarge)
                Text("${u.requests} önskningar", style = MaterialTheme.typography.bodySmall, color = Jp.TextDim)
            }
        }
        SmallToggle("Godkänn alltid", u.autoApprove && !u.blocked, enabled = !u.blocked, onAutoApprove)
        SmallToggle("Blockera", u.blocked, enabled = true, onBlock)
    }
}

@Composable
private fun SmallToggle(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(start = 52.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Jp.Text else Jp.TextFaint, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Jp.Accent, checkedThumbColor = Jp.OnAccent),
        )
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
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Jp.Accent, focusedLabelColor = Jp.Accent, cursorColor = Jp.Accent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
