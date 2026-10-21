package se.jpfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.jpfm.ui.Jp

/**
 * Sida för album, artist och spellista: innehållet rullar under en genomskinlig rad med
 * tillbakaknapp, som får bakgrund och titel när rubriken rullat bort.
 */
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    barColor: Color = Jp.Background,
    state: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val fade by remember {
        derivedStateOf {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / with(density) { 220.dp.toPx() }).coerceIn(0f, 1f)
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), content = content)
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { }
                .background(barColor.copy(alpha = fade * 0.96f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Tillbaka")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).graphicsLayer { alpha = fade },
            )
            actions()
        }
    }
}
