package se.jpfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.jpfm.BuildConfig
import se.jpfm.Leveling
import se.jpfm.Quality
import se.jpfm.plex.PlexAddress
import se.jpfm.plex.SessionState
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Format
import se.jpfm.ui.Jp
import se.jpfm.ui.Screen
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.LocalSettings

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val g = LocalGraph.current
    val s = LocalSettings.current
    val session by g.session.state.collectAsState()
    val used by g.downloads.usedBytes.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    GridPage("Inställningar", onBack = { vm.nav.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Group("Konto och server")
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Jp.SurfaceHigher), contentAlignment = Alignment.Center) {
                    Text((g.prefs.userName ?: "?").take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = Jp.Accent)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.prefs.userName ?: "Plex-konto", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("Inloggad med Plex", style = MaterialTheme.typography.bodySmall, color = Jp.TextDim)
                }
                TextButton(onClick = { confirmLogout = true }) { Text("Logga ut", color = Jp.Error) }
            }
            val server = g.session.server
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Dns, null, tint = Jp.TextDim)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(server?.name ?: "Ingen server", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            session is SessionState.Offline -> "Går inte att nå just nu"
                            server == null -> ""
                            server.uri.contains("relay") -> "Via Plex relä (långsammare)"
                            PlexAddress.isLocal(server.uri) -> "Hemma, lokal anslutning"
                            else -> "Fjärranslutning"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Jp.TextDim,
                    )
                }
                TextButton(onClick = { g.session.connectInBackground() }) { Text("Anslut igen") }
            }
            (session as? SessionState.Ready)?.let { ready ->
                if (ready.sections.size > 1) {
                    Choice(
                        title = "Musikbibliotek",
                        value = ready.section.title,
                        options = ready.sections.map { it.title },
                    ) { i ->
                        g.session.selectSection(ready.sections[i])
                        g.library.invalidate()
                    }
                }
            }

            Group("Uppspelning")
            Choice("Kvalitet på wifi", s.wifiQuality.label, Quality.entries.map { it.label }) { i ->
                g.prefs.updateSettings { it.copy(wifiQuality = Quality.entries[i]) }
            }
            Choice("Kvalitet på mobildata", s.mobileQuality.label, Quality.entries.map { it.label }) { i ->
                g.prefs.updateSettings { it.copy(mobileQuality = Quality.entries[i]) }
            }
            Hint("Original spelar filen som den är, utan glapp mellan låtarna. De andra kvaliteterna kodas om av Plex-servern och sparar data.")
            Choice("Ljudutjämning", s.leveling.label, Leveling.entries.map { it.label }) { i ->
                g.prefs.updateSettings { it.copy(leveling = Leveling.entries[i]) }
            }
            Hint("Använder Plex ljudanalys så att alla låtar låter lika högt. Automatisk utjämnar per album när du lyssnar på ett helt album och per låt annars.")
            Toggle("Färger från omslaget", s.dynamicColors) { on -> g.prefs.updateSettings { it.copy(dynamicColors = on) } }

            Group("Nedladdningar")
            Toggle("Ladda bara ner på wifi", s.downloadWifiOnly) { on ->
                g.prefs.updateSettings { it.copy(downloadWifiOnly = on) }
                g.downloads.setWifiOnly(on)
            }
            Row(
                Modifier.fillMaxWidth().clickable { confirmClear = true }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Ta bort allt nedladdat", style = MaterialTheme.typography.bodyLarge, color = Jp.Error)
                    Text("${Format.bytes(used)} på telefonen", style = MaterialTheme.typography.bodySmall, color = Jp.TextDim)
                }
            }

            Group("Önska musik")
            val requestServer by g.requests.url.collectAsState()
            val requestMe by g.requests.me.collectAsState()
            val reqs by g.requests.requests.collectAsState()
            Row(
                Modifier.fillMaxWidth().clickable { vm.nav.push(Screen.RequestSetup) }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CloudDownload, null, tint = Jp.TextDim)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("JPFM-servern och Lidarr", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            requestServer == null -> "Inte kopplad"
                            requestMe?.isOwner == true -> "$requestServer · ägare"
                            else -> requestServer!!
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (requestServer != null) Jp.Accent else Jp.TextDim,
                    )
                }
            }
            if (requestServer != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.nav.push(Screen.Request()) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.QueueMusic, null, tint = Jp.TextDim)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (requestMe?.isOwner == true) "Önskningar" else "Dina önskningar", style = MaterialTheme.typography.bodyLarge)
                        val pending = requestMe?.pending ?: 0
                        val active = reqs.count { it.state.active }
                        Text(
                            when {
                                pending > 0 -> "$pending väntar på ditt godkännande"
                                active > 0 -> "$active på väg"
                                else -> "Sök och önska album och låtar"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pending > 0) Jp.Accent else Jp.TextDim,
                        )
                    }
                }
            }
            Hint("Önska musik som inte finns hos dig. JPFM-servern på Unraid skickar önskningen till Lidarr – andras önskningar först efter att du godkänt dem.")

            Group("Android Auto")
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Icon(Icons.Rounded.DirectionsCar, null, tint = Jp.Accent)
                Spacer(Modifier.width(14.dp))
                Text(
                    "JPFM finns i bilen när telefonen är ansluten. Första gången: öppna Android Auto-inställningarna på telefonen, " +
                        "tryck tio gånger på Version för att slå på utvecklarläget, välj Utvecklarinställningar i menyn och slå på " +
                        "Okända källor. Säg sedan \"Spela Kent på JPFM\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Jp.TextDim,
                )
            }

            Group("Om JPFM")
            Row(
                Modifier.fillMaxWidth().clickable { vm.checkForUpdate(manual = true) }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sök efter uppdatering", style = MaterialTheme.typography.bodyLarge)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Jp.TextDim)
                }
                if (vm.update != null) TextButton(onClick = { vm.installUpdate() }) { Text("Uppdatera") }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            containerColor = Jp.SurfaceHigh,
            title = { Text("Logga ut?") },
            text = { Text("Nedladdad musik ligger kvar på telefonen.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    vm.player.controller?.stop()
                    g.session.logout()
                }) { Text("Logga ut", color = Jp.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Avbryt") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Jp.SurfaceHigh,
            title = { Text("Ta bort allt nedladdat?") },
            text = { Text("All nedladdad musik tas bort från telefonen. Du kan ladda ner igen när du vill.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    g.downloads.removeAll()
                    vm.toast("Nedladdningarna är borttagna")
                }) { Text("Ta bort", color = Jp.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Avbryt") } },
        )
    }
}

@Composable
internal fun Group(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 18.dp), color = Jp.Outline.copy(alpha = 0.5f))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Jp.Accent,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
        )
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Jp.TextFaint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Jp.Accent, checkedThumbColor = Jp.OnAccent),
        )
    }
}

@Composable
internal fun Choice(title: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(value, style = MaterialTheme.typography.bodySmall, color = Jp.Accent)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Jp.SurfaceHigh)) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    text = { Text(o) },
                    trailingIcon = { if (o == value) Icon(Icons.Rounded.Check, null, tint = Jp.Accent) },
                    onClick = {
                        open = false
                        onPick(i)
                    },
                )
            }
        }
    }
}
