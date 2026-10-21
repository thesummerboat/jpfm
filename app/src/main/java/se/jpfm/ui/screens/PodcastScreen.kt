package se.jpfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.jpfm.podcast.Podcast
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.ErrorBox
import se.jpfm.ui.Jp
import se.jpfm.ui.Load
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.LocalSettings
import se.jpfm.ui.backgroundBrush
import se.jpfm.ui.rememberArtColors
import se.jpfm.ui.rememberLoadable

/** En podd: följ, beskrivning och alla avsnitt. */
@Composable
fun PodcastScreen(vm: AppViewModel, podcast: Podcast) {
    val g = LocalGraph.current
    val subs by g.podcasts.subscriptions.collectAsState()
    val progress by g.podcasts.progress.collectAsState()
    val following = subs.any { it.id == podcast.id }
    val episodes = rememberLoadable("pod", podcast.id) { force -> g.podcasts.feed(podcast, force) }
    val info = g.podcasts.podcast(podcast.id) ?: podcast
    val colors = rememberArtColors(info.image, enabled = LocalSettings.current.dynamicColors)
    var expanded by remember { mutableStateOf(false) }

    DetailScaffold(info.title, onBack = { vm.nav.back() }, barColor = colors.deep) {
        item(key = "header") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.backgroundBrush(1400f))
                    .statusBarsPadding()
                    .padding(top = 56.dp, start = 20.dp, end = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(info.image, Modifier.size(210.dp).shadow(24.dp, RoundedCornerShape(12.dp)), size = 630, shape = RoundedCornerShape(12.dp), placeholder = Icons.Rounded.Headset)
                Spacer(Modifier.height(18.dp))
                Text(info.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                info.author?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.titleSmall, color = colors.accent, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val newest = episodes.data?.firstOrNull { progress[it.id]?.played != true } ?: episodes.data?.firstOrNull()
                    if (newest != null) {
                        Button(
                            onClick = { vm.playEpisode(newest) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Jp.OnAccent),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if ((progress[newest.id]?.positionMs ?: 0) > 0) "Fortsätt" else "Senaste", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (following) {
                        OutlinedButton(onClick = { vm.toggleSubscription(info) }, shape = RoundedCornerShape(50)) {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Följer")
                        }
                    } else {
                        OutlinedButton(onClick = { vm.toggleSubscription(info) }, shape = RoundedCornerShape(50)) {
                            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Följ")
                        }
                    }
                }
                info.description?.let { d ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        d,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Jp.TextDim,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Visa mindre" else "Läs mer", color = Jp.Text) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        when (val st = episodes.state) {
            is Load.Loading -> item { LoadingBox() }
            is Load.Failed -> item { ErrorBox(st.message) { episodes.retry() } }
            is Load.Ready -> {
                item(key = "count") {
                    Text(
                        "${st.data.size} avsnitt",
                        style = MaterialTheme.typography.labelLarge,
                        color = Jp.TextDim,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                items(st.data, key = { it.id }) { e -> EpisodeRow(vm, e, progress[e.id]) }
            }
        }
        item(key = "pad") { Box(Modifier.height(24.dp)) }
    }
}
