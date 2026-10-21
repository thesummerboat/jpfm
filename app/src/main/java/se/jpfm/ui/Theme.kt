package se.jpfm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import se.jpfm.R

object Jp {
    val Background = Color(0xFF0B0D12)
    val Surface = Color(0xFF12151C)
    val SurfaceHigh = Color(0xFF1A1E27)
    val SurfaceHigher = Color(0xFF232836)
    val Outline = Color(0xFF2C3241)
    /** Norrsken. */
    val Accent = Color(0xFF4FE3B0)
    val AccentDeep = Color(0xFF1FA67F)
    val Aurora2 = Color(0xFF3BB4E6)
    val Aurora3 = Color(0xFF9A7BFF)
    val OnAccent = Color(0xFF04140E)
    val Text = Color(0xFFF1F3F7)
    val TextDim = Color(0xFFA1AABA)
    val TextFaint = Color(0xFF6B7385)
    val Error = Color(0xFFFF8A80)
    val Love = Color(0xFFFF5C8A)

    val auroraBrush = Brush.linearGradient(listOf(Accent, Aurora2, Aurora3))
}

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val InterDisplayFamily = FontFamily(
    Font(R.font.interdisplay_bold, FontWeight.Bold),
    Font(R.font.interdisplay_black, FontWeight.Black),
)

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = InterDisplayFamily, fontWeight = FontWeight.Black, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-1.2).sp),
    displayMedium = TextStyle(fontFamily = InterDisplayFamily, fontWeight = FontWeight.Black, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.9).sp),
    displaySmall = TextStyle(fontFamily = InterDisplayFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontFamily = InterDisplayFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = InterDisplayFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = InterDisplayFamily, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
)

@Composable
fun JpfmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Jp.Accent,
            onPrimary = Jp.OnAccent,
            primaryContainer = Jp.AccentDeep,
            onPrimaryContainer = Jp.Text,
            secondary = Jp.Aurora2,
            tertiary = Jp.Aurora3,
            background = Jp.Background,
            onBackground = Jp.Text,
            surface = Jp.Surface,
            onSurface = Jp.Text,
            surfaceVariant = Jp.SurfaceHigh,
            onSurfaceVariant = Jp.TextDim,
            surfaceContainerLowest = Jp.Background,
            surfaceContainerLow = Jp.Surface,
            surfaceContainer = Jp.SurfaceHigh,
            surfaceContainerHigh = Jp.SurfaceHigh,
            surfaceContainerHighest = Jp.SurfaceHigher,
            outline = Jp.Outline,
            outlineVariant = Jp.Outline,
            error = Jp.Error,
        ),
        typography = typography,
        content = content,
    )
}
