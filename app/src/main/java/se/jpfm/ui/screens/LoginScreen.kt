package se.jpfm.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.jpfm.plex.SessionState
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Jp
import se.jpfm.ui.OfflineBanner
import kotlin.math.PI
import kotlin.math.sin

/** Norrsken som böljar långsamt i bakgrunden. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier.fillMaxSize()) {
        val bands = listOf(
            Triple(Jp.Accent, 0.22f, 0f),
            Triple(Jp.Aurora2, 0.30f, 1.7f),
            Triple(Jp.Aurora3, 0.38f, 3.1f),
        )
        for ((color, yFrac, shift) in bands) {
            val path = Path()
            val steps = 48
            for (i in 0..steps) {
                val x = size.width * i / steps
                val y = size.height * yFrac + sin(i / steps.toFloat() * 2.4f * PI.toFloat() + phase + shift) * size.height * 0.05f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                Brush.verticalGradient(listOf(color.copy(alpha = 0.0f), color.copy(alpha = 0.55f), color.copy(alpha = 0f))),
                style = Stroke(width = size.height * 0.09f),
                alpha = 0.55f,
            )
        }
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Jp.Background), startY = size.height * 0.35f, endY = size.height * 0.75f))
        drawCircle(Jp.Accent.copy(alpha = 0.04f), radius = size.width, center = Offset(size.width * 0.8f, size.height * 1.1f))
    }
}

@Composable
fun LoginScreen(vm: AppViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (vm.loginPin == null) vm.beginLogin() }
    Box(Modifier.fillMaxSize().background(Jp.Background)) {
        AuroraBackground(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text("JP", style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 80.sp), color = Jp.Text)
            Text("FM", style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 84.sp), color = Jp.Accent)
            Spacer(Modifier.height(18.dp))
            Text("Hela din musiksamling. Överallt.", style = MaterialTheme.typography.titleMedium, color = Jp.TextDim, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))

            val pin = vm.loginPin
            val error = vm.loginError
            when {
                error != null -> {
                    Text(error, color = Jp.Error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { vm.beginLogin() }, colors = ButtonDefaults.buttonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent)) {
                        Text("Försök igen")
                    }
                }
                pin == null -> CircularProgressIndicator(color = Jp.Accent)
                else -> {
                    Button(
                        onClick = {
                            vm.loginUrl?.let { url ->
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (e: ActivityNotFoundException) {
                                    vm.toast("Ingen webbläsare hittades")
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Logga in med Plex", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(22.dp))
                    Text("eller gå till plex.tv/link på datorn och skriv", style = MaterialTheme.typography.bodyMedium, color = Jp.TextDim, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        pin.code,
                        style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 10.sp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Jp.SurfaceHigh)
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Appen fortsätter av sig själv när du loggat in.", style = MaterialTheme.typography.bodySmall, color = Jp.TextFaint)
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** Visas medan appen letar efter servern första gången. */
@Composable
fun ConnectingScreen(state: SessionState, onRetry: () -> Unit, onLogout: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Jp.Background)) {
        AuroraBackground(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (state) {
                is SessionState.NoMusicLibrary -> {
                    Text("Inget musikbibliotek", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${state.serverName} har inget bibliotek av typen Musik. Lägg till ett i Plex och försök igen.",
                        color = Jp.TextDim,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = onRetry) { Text("Försök igen") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onLogout) { Text("Logga ut") }
                }
                is SessionState.Offline -> {
                    Box(Modifier.fillMaxWidth()) { OfflineBanner(state.reason, onRetry) }
                }
                else -> {
                    CircularProgressIndicator(color = Jp.Accent, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(
                        (state as? SessionState.Connecting)?.serverName?.let { "Ansluter till $it …" } ?: "Letar efter din Plex-server …",
                        color = Jp.TextDim,
                    )
                }
            }
        }
    }
}
