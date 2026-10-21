package se.jpfm.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.jpfm.AppGraph
import se.jpfm.Settings
import se.jpfm.playback.ArtworkProvider
import se.jpfm.plex.PlexServer
import se.jpfm.plex.UltraBlur

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("Ingen AppGraph") }

/**
 * Omslaget att visa för en låt: originalsingelns eller albumets för låtar på samlingsskivor
 * (när vi hittat det), annars albumets. Ber om att det letas fram om det behövs.
 */
@Composable
fun trackThumb(t: se.jpfm.plex.Track): String? {
    val g = LocalGraph.current
    if (!LocalSettings.current.compilationCovers || !se.jpfm.covers.CoverMatch.isCompilation(t)) return t.thumb
    val found by g.covers.found.collectAsState()
    LaunchedEffect(t.ratingKey) { g.covers.want(t) }
    return found[t.ratingKey] ?: t.thumb
}
val LocalServer = staticCompositionLocalOf<PlexServer?> { null }
val LocalSettings = compositionLocalOf { Settings() }

/**
 * Bild från Plex i rätt storlek. Cachen nycklas på bildens sökväg och inte på serverns adress,
 * så att omslagen finns kvar när telefonen byter nät, och nedladdade omslag visas offline.
 */
fun artModel(context: Context, g: AppGraph, server: PlexServer?, thumb: String, size: Int): ImageRequest {
    val key = "$thumb@$size"
    val offline = ArtworkProvider.offlineFile(g.app, thumb)
    val data: Any = when {
        // Radiologotyper och poddomslag: direkt från webben.
        thumb.startsWith("http") -> thumb
        offline.exists() -> offline
        server != null -> g.client.imageUrl(server, thumb, size)
        // Utan server: en adress som inte går att nå, men cachen hittar bilden på nyckeln.
        else -> "http://offline.invalid/${key.hashCode()}"
    }
    return ImageRequest.Builder(context)
        .data(data)
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .size(size)
        .build()
}

@Composable
fun Artwork(
    thumb: String?,
    modifier: Modifier = Modifier,
    size: Int = 300,
    shape: Shape = RoundedCornerShape(8.dp),
    placeholder: ImageVector = Icons.Rounded.MusicNote,
    contentDescription: String? = null,
) {
    val g = LocalGraph.current
    val server = LocalServer.current
    val context = LocalContext.current
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Jp.SurfaceHigher, Jp.SurfaceHigh))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, contentDescription = null, tint = Jp.TextFaint, modifier = Modifier.fillMaxSize(0.38f))
        if (thumb != null) {
            val model = remember(thumb, size, server?.uri) { artModel(context, g, server, thumb, size) }
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Färger från ett omslag, för bakgrunder som matchar musiken. */
data class ArtColors(val base: Color, val accent: Color, val deep: Color) {
    companion object {
        val Default = ArtColors(Color(0xFF1B2433), Jp.Accent, Color(0xFF0E131C))

        fun fromUltraBlur(u: UltraBlur): ArtColors {
            val tl = Color(u.topLeft)
            val br = Color(u.bottomRight)
            val base = lerp(tl, br, 0.4f)
            return ArtColors(darken(base, 0.55f), brighten(Color(u.topRight)), darken(Color(u.bottomLeft), 0.75f))
        }

        fun fromPalette(p: Palette): ArtColors {
            fun c(rgb: Int?) = rgb?.let { Color(it) }
            val dominant = c(p.dominantSwatch?.rgb) ?: Default.base
            val vibrant = c(p.vibrantSwatch?.rgb) ?: c(p.lightVibrantSwatch?.rgb) ?: c(p.mutedSwatch?.rgb) ?: Jp.Accent
            val deep = c(p.darkMutedSwatch?.rgb) ?: c(p.darkVibrantSwatch?.rgb) ?: dominant
            return ArtColors(darken(dominant, 0.5f), brighten(vibrant), darken(deep, 0.75f))
        }

        private fun darken(c: Color, amount: Float): Color = lerp(c, Color.Black, amount).copy(alpha = 1f)

        /** Accentfärgen måste synas mot mörk botten. */
        private fun brighten(c: Color): Color {
            var x = c
            var i = 0
            while (x.luminance() < 0.35f && i < 6) {
                x = lerp(x, Color.White, 0.18f)
                i++
            }
            return x
        }
    }
}

private val colorCache = LruCache<String, ArtColors>(200)

@Composable
fun rememberArtColors(thumb: String?, ultraBlur: UltraBlur? = null, enabled: Boolean = true): ArtColors {
    val g = LocalGraph.current
    val server = LocalServer.current
    val context = LocalContext.current
    val key = thumb ?: ""
    var colors by remember(key, ultraBlur) {
        mutableStateOf(
            when {
                !enabled -> ArtColors.Default
                ultraBlur != null -> ArtColors.fromUltraBlur(ultraBlur)
                else -> colorCache.get(key) ?: ArtColors.Default
            },
        )
    }
    LaunchedEffect(key, ultraBlur, enabled) {
        if (!enabled || ultraBlur != null || thumb == null || colorCache.get(key) != null) return@LaunchedEffect
        val request = artModel(context, g, server, thumb, 160).newBuilder()
            .allowHardware(false)
            .memoryCacheKey("$thumb@160:sw")
            .build()
        val result = context.imageLoader.execute(request)
        val bitmap = (result as? SuccessResult)?.drawable?.toBitmap() ?: return@LaunchedEffect
        val c = withContext(Dispatchers.Default) { ArtColors.fromPalette(Palette.from(bitmap).maximumColorCount(16).generate()) }
        colorCache.put(key, c)
        colors = c
    }
    return colors
}

/** Färgen som tonas ut mot appens bakgrund. */
fun ArtColors.backgroundBrush(height: Float): Brush = Brush.verticalGradient(
    0f to base,
    0.55f to lerp(base, Jp.Background, 0.6f),
    1f to Jp.Background,
    endY = height,
)

fun Color.over(bg: Color, alpha: Float): Color = copy(alpha = alpha).compositeOver(bg)
