package se.jpfm.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import se.jpfm.playback.QueueBuilder
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.EmptyBox
import se.jpfm.ui.Format
import se.jpfm.ui.Jp
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.LocalSettings
import se.jpfm.ui.PlayRow
import se.jpfm.ui.Screen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(vm: AppViewModel) {
    val g = LocalGraph.current
    val cols by g.downloads.collections.collectAsState()
    val states by g.downloads.states.collectAsState()
    val used by g.downloads.usedBytes.collectAsState()
    val settings = LocalSettings.current
    val failed = states.count { it.value == Download.STATE_FAILED }
    val active = states.count { it.value == Download.STATE_DOWNLOADING || it.value == Download.STATE_QUEUED || it.value == Download.STATE_RESTARTING }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "top") {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                Text("Nedladdat", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Spelas utan nät – i bilen, på fjället och utomlands. ${Format.bytes(used)} används.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Jp.TextDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item(key = "wifi") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Jp.SurfaceHigh)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Wifi, null, tint = Jp.TextDim, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ladda bara ner på wifi", style = MaterialTheme.typography.bodyLarge)
                    if (active > 0) Text("$active låtar kvar att ladda ner", style = MaterialTheme.typography.bodySmall, color = Jp.Accent)
                }
                Switch(
                    checked = settings.downloadWifiOnly,
                    onCheckedChange = { on ->
                        g.prefs.updateSettings { it.copy(downloadWifiOnly = on) }
                        g.downloads.setWifiOnly(on)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Jp.Accent, checkedThumbColor = Jp.OnAccent),
                )
            }
        }
        if (failed > 0) {
            item(key = "failed") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = Jp.Error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("$failed låtar gick inte att ladda ner", style = MaterialTheme.typography.bodySmall, color = Jp.Error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { g.downloads.retryFailed() }) { Text("Försök igen") }
                }
            }
        }
        if (cols.isEmpty()) {
            item(key = "empty") {
                EmptyBox(
                    Icons.Rounded.DownloadForOffline,
                    "Inget nedladdat än",
                    "Tryck på pilen på ett album eller en spellista så sparas musiken på telefonen i originalkvalitet.",
                )
            }
        } else {
            item(key = "shuffle") {
                PlayRow(
                    onPlay = { vm.playOffline(QueueBuilder.ALL_OFFLINE, shuffle = true) },
                    onShuffle = null,
                    playLabel = "Blanda allt nedladdat",
                    playIcon = Icons.Rounded.Shuffle,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(cols, key = { it.id }) { c ->
                val p = g.downloads.progress(c, states)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { vm.nav.push(Screen.Offline(c.id, c.title)) },
                            onLongClick = { vm.nav.push(Screen.Offline(c.id, c.title)) },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(c.thumb, Modifier.size(64.dp), size = 192, shape = RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.subtitle, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (p.complete) Format.tracks(p.total) else "${p.done} av ${p.total} låtar",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (p.complete) Jp.Accent else Jp.TextFaint,
                        )
                    }
                    if (!p.complete) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { p.fraction },
                                modifier = Modifier.size(24.dp),
                                color = Jp.Accent,
                                trackColor = Jp.SurfaceHigher,
                                strokeWidth = 2.5.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}
