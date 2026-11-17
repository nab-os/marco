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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.nabos.marco.vision.CalibrationBoard
import dev.nabos.marco.vision.Coverage
import dev.nabos.marco.vision.Calibrator
import dev.nabos.marco.vision.Recorder
import dev.nabos.marco.vision.Vernier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

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

private enum class Mode { MENU, BOARD, CALIBRATE, RECORD, STEREO }

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
        Mode.STEREO -> if (cameraOk) StereoMode() else NeedCamera { askCamera.launch(Manifest.permission.CAMERA) }
        Mode.MENU -> Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Marco Lab", style = MaterialTheme.typography.headlineMedium)
            Text(if (Vernier.available) "Vernier: built in" else "Vernier: missing (build with marco.vision=true)")
            Button({ mode = Mode.BOARD }, Modifier.fillMaxWidth(), enabled = Vernier.available) { Text("Show calibration board") }
            Button({ mode = Mode.CALIBRATE }, Modifier.fillMaxWidth(), enabled = Vernier.available) { Text("Calibrate this camera") }
            OutlinedButton({ mode = Mode.RECORD }, Modifier.fillMaxWidth()) { Text("Record camera + motion") }
            OutlinedButton({ mode = Mode.STEREO }, Modifier.fillMaxWidth()) { Text("Stereo test") }
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
        if (s.result == null) {
            s.preview?.let { preview ->
                Box(Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height)) {
                    Image(preview, null, Modifier.fillMaxSize())
                    s.coverage?.let { CoverageGrid(it, Modifier.fillMaxSize()) }
                }
            }
            LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
            Text(
                when {
                    s.finishing -> "Calibrating…"
                    s.points < 0 -> "Looking for the board…"
                    else -> "views ${s.views}/${Calibrator.READY_VIEWS} · frame %d%%/%d%% · poses ${s.poses}/${Calibrator.READY_POSES}"
                        .format((s.covered * 100).roundToInt(), (Calibrator.READY_COVERAGE * 100).roundToInt())
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (s.squarePx > 0f) {
                val hint = when {
                    s.squarePx < Calibrator.SMALL_SQUARE_PX -> " — tiny: come closer, or use bigger squares (marco-board +)"
                    s.squarePx > Calibrator.LARGE_SQUARE_PX -> " — big: step back, or use smaller squares"
                    else -> ""
                }
                Text(
                    "Squares look %.1f px$hint".format(s.squarePx),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hint.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Hold the board in view — the other phone at 10–20 cm, or a screen running vision/board at " +
                    "30–50 cm — and keep moving: tilt and turn the phone every way, and bring the board into " +
                    "the shaded cells. It finishes on its own.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(calibrator::finish, enabled = s.views >= Calibrator.MIN_VIEWS && s.running) { Text("Finish now") }
                OutlinedButton(calibrator::restart, enabled = !s.finishing) { Text("Start over") }
            }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        s.result?.let { r ->
            OutlinedButton(calibrator::restart) { Text("Calibrate again") }
            val a = r.alignment
            Text(
                buildString {
                    if (a != null) {
                        appendLine("Gyroscope alignment, ${a.pairs} pairs from ${a.poses} poses")
                        appendLine("time offset   %+.1f ± %.1f ms (gyro later)".format(a.timeOffsetMs, a.timeOffsetSdMs))
                        appendLine("rotation      ± %.2f°".format(a.rotationSdDeg))
                        appendLine("off square    %.2f°".format(a.offAxisDeg))
                        appendLine("vs factory    ${a.vsFactoryDeg?.let { "%.2f°".format(it) } ?: "-"}")
                        appendLine("residual      %.3f°".format(a.rmsDeg))
                        appendLine("axis coverage %.2f${if (a.axisCoverage < 0.3) "  (turn more about every axis)" else ""}".format(a.axisCoverage))
                    } else {
                        appendLine("No gyroscope alignment: ${r.alignmentError ?: "no gyroscope"}")
                    }
                    appendLine()
                    val f = r.factory
                    appendLine("${r.model}, ${r.views} views, rms %.3f px".format(r.rms))
                    if (r.rmsByModel.size > 1) {
                        appendLine("models: " + r.rmsByModel.entries.joinToString("  ") { (m, v) -> "%s %.3f".format(m, v) })
                    }
                    appendLine("          vernier    factory")
                    appendLine("fx   %10.2f %10s".format(r.fx, f?.let { "%.2f".format(it.fx) } ?: "-"))
                    appendLine("fy   %10.2f %10s".format(r.fy, f?.let { "%.2f".format(it.fy) } ?: "-"))
                    appendLine("cx   %10.2f %10s".format(r.cx, f?.let { "%.2f".format(it.cx) } ?: "-"))
                    appendLine("cy   %10.2f %10s".format(r.cy, f?.let { "%.2f".format(it.cy) } ?: "-"))
                    appendLine("dist ${r.distortion.joinToString(" ") { "%.4f".format(it) }}")
                    append("Saved to ${r.savedTo.name}")
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The coverage grid over the preview: cells the board hasn't visited yet are shaded. */
@Composable
private fun CoverageGrid(coverage: Coverage, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val left = size.width * coverage.inset
        val top = size.height * coverage.inset
        val cellWidth = (size.width - 2 * left) / coverage.columns
        val cellHeight = (size.height - 2 * top) / coverage.rows
        coverage.cells.forEachIndexed { index, visited ->
            val topLeft = Offset(left + index % coverage.columns * cellWidth, top + index / coverage.columns * cellHeight)
            val cell = Size(cellWidth, cellHeight)
            if (!visited) drawRect(Color.Black.copy(alpha = 0.45f), topLeft, cell)
            drawRect(line.copy(alpha = 0.6f), topLeft, cell, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun StereoMode() {
    val context = LocalContext.current
    val probe = remember { StereoProbe(context.applicationContext) }
    DisposableEffect(probe) {
        probe.start()
        onDispose { probe.stop() }
    }
    val s by probe.state.collectAsStateWithLifecycle()
    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(s.lenses, style = MaterialTheme.typography.bodySmall)
        Text("Both lenses at once: ${s.supported.ifEmpty { "…" }}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.previews.forEach { p ->
                p?.let { Image(it, null, Modifier.weight(1f).aspectRatio(it.width.toFloat() / it.height)) }
            }
        }
        Text(
            buildString {
                s.counts.indices.forEach { i -> appendLine("lens ${i + 1}: ${s.counts[i]} frames, %.1f fps".format(s.fps[i])) }
                append("gap to nearest frame: median ${s.medianGapMs?.let { "%.2f".format(it) } ?: "-"} ms, max ${s.maxGapMs?.let { "%.2f".format(it) } ?: "-"} ms")
            },
            fontFamily = FontFamily.Monospace,
        )
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
