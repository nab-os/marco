package dev.nabos.marco.lab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.nabos.marco.vision.CalibrationBoard
import dev.nabos.marco.vision.CalibrationPhase
import dev.nabos.marco.vision.Calibrator
import dev.nabos.marco.vision.Recorder
import dev.nabos.marco.vision.Vernier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Debug-only tools for the camera work: show the calibration board, calibrate
 * this phone's camera against the other phone's board, record a walk.
 *
 * `adb shell am start -n dev.nabos.marco/.lab.LabActivity --es mode board`
 * opens straight on a mode (board, calibrate, record).
 */
class LabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra("mode")?.let { m -> Mode.entries.firstOrNull { it.name.equals(m, true) } }
        setContent {
            MaterialTheme(colorScheme = dynamicDarkColorScheme(this)) {
                Surface(Modifier.fillMaxSize()) { Lab(start ?: Mode.MENU) }
            }
        }
    }
}

private enum class Mode { MENU, BOARD, CALIBRATE, RECORD }

@Composable
private fun Lab(initial: Mode) {
    var mode by remember { mutableStateOf(initial) }
    BackHandler(mode != Mode.MENU) { mode = Mode.MENU }
    val context = LocalContext.current
    var cameraOk by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraOk = it }

    when (mode) {
        Mode.BOARD -> CalibrationBoard()
        Mode.CALIBRATE -> if (cameraOk) CalibrateMode() else NeedCamera { askCamera.launch(Manifest.permission.CAMERA) }
        Mode.RECORD -> if (cameraOk) RecordMode() else NeedCamera { askCamera.launch(Manifest.permission.CAMERA) }
        Mode.MENU -> Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Marco Lab", style = MaterialTheme.typography.headlineMedium)
            Text(if (Vernier.available) "Vernier: built in" else "Vernier: missing (build with marco.vision=true)")
            Button({ mode = Mode.BOARD }, Modifier.fillMaxWidth(), enabled = Vernier.available) { Text("Show calibration board") }
            Button({ mode = Mode.CALIBRATE }, Modifier.fillMaxWidth(), enabled = Vernier.available) { Text("Calibrate this camera") }
            OutlinedButton({ mode = Mode.RECORD }, Modifier.fillMaxWidth()) { Text("Record camera + motion") }
            Text(
                "Show the board on one phone and calibrate on the other. Recordings and calibrations go to " +
                    "Android/data/dev.nabos.marco/files/. To record with the Bluetooth signal, use Record on the Find screen.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NeedCamera(ask: () -> Unit) {
    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("The camera is needed for this.")
        Button(ask) { Text("Allow camera") }
    }
}

@Composable
private fun CalibrateMode() {
    val context = LocalContext.current
    val calibrator = remember { Calibrator(context.applicationContext) }
    DisposableEffect(calibrator) {
        calibrator.start()
        onDispose { calibrator.close() }
    }
    val s by calibrator.state.collectAsStateWithLifecycle()

    Column(
        Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(s.cameraLabel, style = MaterialTheme.typography.bodySmall)
        s.preview?.let { Image(it, null, Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height)) }
        Text(
            if (s.points >= 0) "Board read: ${s.points} points" else "Looking for the board…",
            style = MaterialTheme.typography.titleMedium,
        )
        if (s.phase == CalibrationPhase.ALIGNING) {
            Text("Poses: ${s.poses} (need ${Calibrator.MIN_POSES}, 40+ is better)")
            Text(
                "Keep the board in view and turn the phone about every axis, at a steady pace: tilt it " +
                    "forward and back, left and right, and twist it. Pause now and then so frames stay sharp.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(calibrator::align, enabled = s.poses >= Calibrator.MIN_POSES && !s.aligning) {
                Text(if (s.aligning) "Aligning…" else "Align")
            }
        } else {
            Text("Views kept: ${s.kept} / ${Calibrator.MAX_VIEWS} (need ${Calibrator.MIN_VIEWS})")
            Text(
                "Point this camera at the board, on the other phone from 10–20 cm or on a computer screen " +
                    "(vision/board) from 30–50 cm. Move slowly: tilt it every " +
                    "way, and slide it so the board visits every part of the frame, corners included.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(calibrator::calibrate, enabled = s.kept >= Calibrator.MIN_VIEWS && !s.calibrating) {
                    Text(if (s.calibrating) "Calibrating…" else "Calibrate")
                }
                OutlinedButton(calibrator::startAlignment, enabled = s.result != null) { Text("Align gyroscope") }
            }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        s.alignment?.let { a ->
            Text(
                buildString {
                    appendLine("Gyroscope alignment, ${a.pairs} of ${a.pairsFound} pairs from ${a.poses} poses")
                    appendLine("time offset   %+.1f ± %.1f ms (gyro later)".format(a.timeOffsetMs, a.timeOffsetSdMs))
                    appendLine("rotation      ± %.2f°".format(a.rotationSdDeg))
                    appendLine("off square    %.2f°".format(a.offAxisDeg))
                    appendLine("vs factory    ${a.vsFactoryDeg?.let { "%.2f°".format(it) } ?: "-"}")
                    appendLine("residual      %.3f°".format(a.rmsDeg))
                    appendLine("axis coverage %.2f${if (a.axisCoverage < 0.3) "  (turn more about every axis)" else ""}".format(a.axisCoverage))
                    appendLine("gyro bias     ${a.gyroBias.joinToString(" ") { "%.4f".format(it) }} rad/s")
                    append("Saved to ${a.savedTo.name}")
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        s.result?.let { r ->
            val f = r.factory
            Text(
                buildString {
                    appendLine("${r.model}, ${r.views} views, rms %.3f px${if (r.saved) " (saved)" else ""}".format(r.rms))
                    if (r.rmsByModel.size > 1) {
                        appendLine("models: " + r.rmsByModel.entries.joinToString("  ") { (m, v) -> "%s %.3f".format(m, v) })
                    }
                    appendLine("          vernier    factory")
                    appendLine("fx   %10.2f %10s".format(r.fx, f?.let { "%.2f".format(it.fx) } ?: "-"))
                    appendLine("fy   %10.2f %10s".format(r.fy, f?.let { "%.2f".format(it.fy) } ?: "-"))
                    appendLine("cx   %10.2f %10s".format(r.cx, f?.let { "%.2f".format(it.cx) } ?: "-"))
                    appendLine("cy   %10.2f %10s".format(r.cy, f?.let { "%.2f".format(it.cy) } ?: "-"))
                    appendLine("dist ${r.distortion.joinToString(" ") { "%.4f".format(it) }}")
                    f?.let { appendLine("factory k1k2k3p1p2 ${it.distortion.joinToString(" ") { d -> "%.4f".format(d) }}") }
                    append("Saved to ${r.savedTo.name}")
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RecordMode() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder(context.applicationContext) }
    DisposableEffect(recorder) { onDispose { recorder.stop() } }
    val s by recorder.stats.collectAsStateWithLifecycle()

    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(
            { if (s.recording) recorder.stop() else recorder.start(scope) },
            Modifier.fillMaxWidth(),
        ) { Text(if (s.recording) "Stop" else "Record") }
        Text("Frames ${s.frames} · motion ${s.imu}")
        s.dir?.let { Text("→ ${it.path.substringAfter("/files/")}", style = MaterialTheme.typography.bodySmall) }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
