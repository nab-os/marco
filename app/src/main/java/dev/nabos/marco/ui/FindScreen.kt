package dev.nabos.marco.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nabos.marco.core.Peer
import dev.nabos.marco.finder.FinderEngine
import dev.nabos.marco.finder.FinderState
import dev.nabos.marco.finder.LinkPhase
import dev.nabos.marco.finder.SignalMapSession
import dev.nabos.marco.vision.Recorder
import kotlin.math.roundToInt

class FinderViewModel(app: Application) : AndroidViewModel(app) {
    val engine = FinderEngine(app, viewModelScope)
    val map = SignalMapSession(app, viewModelScope, engine)
    private val recorderLazy = lazy { Recorder(app) }
    /** Debug builds only: records camera, motion and RSSI for tuning the signal map. */
    val recorder by recorderLazy

    /** The map starts by itself once, unless refused. */
    var mapOffered = false

    fun startRecording() {
        map.stop() // both need the cameras
        engine.fastPoll = true
        recorder.start(viewModelScope, engine.samples)
    }

    fun stopRecording() {
        if (!recorderLazy.isInitialized()) return
        recorder.stop()
        engine.fastPoll = map.state.value.running
    }

    fun startMap() {
        stopRecording()
        map.start()
    }

    override fun onCleared() {
        stopRecording()
        map.stop()
        engine.stop()
    }
}

internal val COLD = Color(0xFF3D7BFF)
internal val HOT = Color(0xFFFF3B1F)

/**
 * Finding one phone: the signal map, started by itself, with the Bluetooth
 * signal's hot/cold reading in its corner and as the colour of your arrow.
 * When the map can't run (no calibrated camera, no camera permission, a build
 * without tracking), the reading stands in, with the reason.
 */
@Composable
fun FindScreen(modifier: Modifier, peer: Peer, onBack: () -> Unit) {
    val peerName = peer.name
    val vm: FinderViewModel = viewModel()
    val s by vm.engine.state.collectAsStateWithLifecycle()
    val m by vm.map.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.startMap() }
    // The ring is placed by sound if the microphone is allowed; it rings either way.
    val askMicThenRing = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.engine.ring() }
    val ring = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.engine.ring()
        else askMicThenRing.launch(Manifest.permission.RECORD_AUDIO)
    }
    val startMap = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) vm.startMap()
        else askCamera.launch(Manifest.permission.CAMERA)
    }
    val mapUsable = m.running && m.track.error == null
    // Android takes the camera from an app in the background, which the tracking reports
    // as an error: stop the map on the way out, start it again on return (and the first time).
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!vm.map.available) return@LifecycleEventEffect
        if (!vm.mapOffered) {
            vm.mapOffered = true
            startMap()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            vm.startMap()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.map.stop() }

    // Scan only while this screen is visible, and keep the screen on meanwhile
    // (Android stops delivering unfiltered scan results when the screen is off).
    val view = LocalView.current
    DisposableEffect(peer.id) {
        view.keepScreenOn = true
        vm.engine.start(peer)
        onDispose {
            vm.stopRecording()
            vm.map.stop()
            vm.engine.stop()
            view.keepScreenOn = false
        }
    }

    val heat = if (s.stale) MaterialTheme.colorScheme.onSurfaceVariant else lerp(COLD, HOT, s.level)

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
            // Same width as the Back button, so the title stays centred.
            if (debuggable()) RecordToggle(vm, Modifier.width(72.dp)) else Spacer(Modifier.width(72.dp))
        }

        Text(phaseLine(s), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)

        if (mapUsable) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                SignalMapView(m, youColor = if (s.stale) MaterialTheme.colorScheme.onSurface else heat, Modifier.fillMaxSize())
                Text(heatLine(s), Modifier.align(Alignment.TopStart), style = MaterialTheme.typography.labelLarge, color = heat)
                // What the cameras see: the map is drawn from them.
                Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
                    m.preview?.let { frame ->
                        Box(Modifier.width(120.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))) {
                            Image(frame.asImageBitmap(), "What the cameras see", Modifier.fillMaxWidth())
                            // A bar where the phone is across the view; an arrow at the edge when it's out of it.
                            targetAcrossFeed(m)?.let { u -> FeedBar(u, targetColour(m), Modifier.matchParentSize()) }
                        }
                    }
                    Text(
                        if (m.preview != null) "Cameras tracking you" else "Cameras starting…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(mapHeadline(m), fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(mapDetail(m), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(heatLine(s), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = heat, textAlign = TextAlign.Center)
            }
            Text(
                m.track.error ?: if (vm.map.available) "The map needs the camera." else "This build has no camera tracking: follow the signal.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (vm.map.available && m.track.error == null) TextButton(onClick = startMap) { Text("Start the map") }
        }

        s.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }

        val ringActive = s.ringing || s.ringQueued
        Button(
            onClick = { if (ringActive) vm.engine.stopRinging() else ring() },
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

private fun debuggable(context: Context) = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** A vertical bar at [u] (0..1 across) over the feed, or a chevron at the edge it's beyond. */
@Composable
private fun FeedBar(u: Float, colour: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (u in 0f..1f) {
            val x = u * w
            drawRect(colour.copy(alpha = 0.35f), Offset(x - 5.dp.toPx(), 0f), Size(10.dp.toPx(), h))
            drawRect(colour, Offset(x - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), h))
        } else {
            val atRight = u > 1f
            val x = if (atRight) w - 10.dp.toPx() else 10.dp.toPx()
            val dir = if (atRight) 1f else -1f
            val chevron = Path().apply {
                moveTo(x - dir * 6.dp.toPx(), h / 2 - 9.dp.toPx())
                lineTo(x + dir * 2.dp.toPx(), h / 2)
                lineTo(x - dir * 6.dp.toPx(), h / 2 + 9.dp.toPx())
            }
            drawPath(chevron, colour, style = Stroke(3.dp.toPx()))
        }
    }
}

@Composable
private fun debuggable() = debuggable(LocalContext.current)

/** Debug: record camera + motion + RSSI while finding, for tuning the signal map offline. */
@Composable
private fun RecordToggle(vm: FinderViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val stats by vm.recorder.stats.collectAsStateWithLifecycle()
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.startRecording() }
    TextButton(
        onClick = {
            when {
                stats.recording -> vm.stopRecording()
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> vm.startRecording()
                else -> askCamera.launch(Manifest.permission.CAMERA)
            }
        },
        modifier = modifier,
    ) {
        Text(if (stats.recording) "■ ${stats.rssi}" else "● Rec", color = if (stats.recording) HOT else Color.Unspecified)
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

/** The signal as hot/cold with its trend and level: "Warm ▲ · −72 dBm". */
private fun heatLine(s: FinderState): String {
    val r = s.rssi ?: return "…"
    if (s.stale) return "… · last ${r.roundToInt()} dBm"
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
    return "$word$arrow · ${r.roundToInt()} dBm"
}

private fun phaseLine(s: FinderState): String = when (s.phase) {
    LinkPhase.SEARCHING -> "Searching… Make sure the other phone has Bluetooth on."
    LinkPhase.SEEN -> "Seen nearby, connecting for faster updates…"
    LinkPhase.CONNECTING -> "Connecting…"
    LinkPhase.CONNECTED -> "Connected · live signal"
}
