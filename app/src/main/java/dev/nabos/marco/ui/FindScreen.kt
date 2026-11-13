package dev.nabos.marco.ui

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nabos.marco.core.Peer
import dev.nabos.marco.finder.FinderEngine
import dev.nabos.marco.finder.FinderState
import dev.nabos.marco.finder.LinkPhase
import kotlin.math.roundToInt

class FinderViewModel(app: Application) : AndroidViewModel(app) {
    val engine = FinderEngine(app, viewModelScope)
    override fun onCleared() = engine.stop()
}

private val COLD = Color(0xFF3D7BFF)
private val HOT = Color(0xFFFF3B1F)

@Composable
fun FindScreen(modifier: Modifier, peer: Peer, onBack: () -> Unit) {
    val peerName = peer.name
    val vm: FinderViewModel = viewModel()
    val s by vm.engine.state.collectAsStateWithLifecycle()

    // Scan only while this screen is visible, and keep the screen on meanwhile
    // (Android stops delivering unfiltered scan results when the screen is off).
    val view = LocalView.current
    DisposableEffect(peer.id) {
        view.keepScreenOn = true
        vm.engine.start(peer)
        onDispose {
            vm.engine.stop()
            view.keepScreenOn = false
        }
    }

    Column(
        modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                "Finding $peerName",
                Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(72.dp)) // balances the Back button so the title is centred
        }

        Text(phaseLine(s), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)

        HeatGauge(s, Modifier.fillMaxWidth().weight(1f))

        Text(
            heatLabel(s),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = if (s.stale) MaterialTheme.colorScheme.onSurfaceVariant else lerp(COLD, HOT, s.level),
        )
        Text(detailLine(s), style = MaterialTheme.typography.bodyMedium)

        s.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }

        val ringActive = s.ringing || s.ringQueued
        Button(
            onClick = { if (ringActive) vm.engine.stopRinging() else vm.engine.ring() },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = if (ringActive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors(),
        ) {
            Text(
                when {
                    s.ringing -> "Stop ringing"
                    s.ringQueued -> "Will ring when reachable… (cancel)"
                    else -> "Ring $peerName"
                },
                fontSize = 20.sp,
            )
        }

        ToggleRow("Proximity beeps", "Beeps faster as you get closer", s.sound, vm.engine::setSound)
        ToggleRow(
            "Auto-ring when close",
            "Rings $peerName once the signal is strong (about ${FinderEngine.AUTO_RING_RSSI.roundToInt()} dBm)",
            s.autoRing,
            vm.engine::setAutoRing,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Concentric rings that fill and redden as the signal strengthens, pulsing when close. */
@Composable
private fun HeatGauge(s: FinderState, modifier: Modifier) {
    val level by animateFloatAsState(if (s.stale) 0f else s.level, tween(400), label = "level")
    val color by animateColorAsState(
        if (s.stale) Color.Gray.copy(alpha = 0.4f) else lerp(COLD, HOT, s.level), tween(400), label = "color",
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "pulse",
    )
    val ringColor = MaterialTheme.colorScheme.outlineVariant

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)) {
            val maxR = size.minDimension / 2f
            for (i in 1..4) drawCircle(ringColor, radius = maxR * i / 4f, style = Stroke(width = 2.dp.toPx()))
            val core = maxR * (0.18f + 0.72f * level)
            if (!s.stale) {
                // Expanding echo ring, like a sonar ping.
                drawCircle(color.copy(alpha = (1f - pulse) * 0.5f), radius = core + (maxR - core) * pulse, style = Stroke(6.dp.toPx()))
            }
            drawCircle(color, radius = core)
        }
    }
}

private fun heatLabel(s: FinderState): String {
    if (s.stale) return "…"
    val word = when {
        s.level < 0.2f -> "Freezing"
        s.level < 0.4f -> "Cold"
        s.level < 0.6f -> "Warm"
        s.level < 0.8f -> "Hot"
        else -> "Burning!"
    }
    val arrow = when (s.trend) {
        1 -> " ▲"
        -1 -> " ▼"
        else -> ""
    }
    return word + arrow
}

private fun detailLine(s: FinderState): String {
    val r = s.rssi ?: return "Walk around slowly. Hold the phone in front of you."
    return "${r.roundToInt()} dBm (raw ${s.rawRssi}) · turn slowly: your body blocks the signal"
}

private fun phaseLine(s: FinderState): String = when (s.phase) {
    LinkPhase.SEARCHING -> "Searching… Make sure the other phone has Bluetooth on."
    LinkPhase.SEEN -> "Seen nearby, connecting for faster updates…"
    LinkPhase.CONNECTING -> "Connecting…"
    LinkPhase.CONNECTED -> "Connected · live signal"
}
