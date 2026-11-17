package dev.nabos.marco.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.nabos.marco.finder.DirectionSweep
import dev.nabos.marco.finder.PointPhase
import dev.nabos.marco.finder.PointState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dial in your frame of reference: straight up on screen is the way you face.
 * Petals show the signal measured in each direction; once the sweep is done an
 * arrow points the way to walk, with a tick of haptics when you face it.
 */
@Composable
fun PointDial(s: PointState, modifier: Modifier) {
    val heading = s.heading ?: 0.0
    // While still sweeping, show the estimate so far, faintly and without haptics.
    val estimate = s.result?.takeIf { it.clear }
    val target = estimate?.takeIf { s.phase == PointPhase.DONE }
    val facing = target != null && s.heading != null && DirectionSweep.isFacing(heading, target.headingDeg)

    val view = LocalView.current
    LaunchedEffect(facing) {
        if (facing) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }
    // Animate the arrow by its offset from straight ahead, so it never spins the long way round.
    val arrowDeg by animateFloatAsState(
        estimate?.let { DirectionSweep.delta(heading, it.headingDeg).toFloat() } ?: 0f, tween(120), label = "arrow",
    )

    val ringColor = MaterialTheme.colorScheme.outlineVariant
    val youColor = MaterialTheme.colorScheme.onSurface
    val values = s.profile.filterNotNull()
    val lo = values.minOrNull() ?: 0.0
    val hi = values.maxOrNull() ?: 0.0

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)) {
            val c = center
            val maxR = size.minDimension / 2f
            val inner = maxR * 0.22f
            drawCircle(ringColor, radius = maxR, style = Stroke(2.dp.toPx()))
            drawCircle(ringColor, radius = inner, style = Stroke(2.dp.toPx()))

            // One petal per heading bin, length and colour by relative strength.
            val n = s.profile.size
            s.profile.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val norm = if (hi - lo > 0.5) ((v - lo) / (hi - lo)).toFloat() else 0.5f
                val rel = DirectionSweep.delta(heading, i * 360.0 / n)
                val a = Math.toRadians(rel)
                val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                val outer = inner + (maxR * 0.95f - inner) * (0.15f + 0.85f * norm)
                drawLine(
                    lerp(COLD, HOT, norm).copy(alpha = if (target == null) 0.85f else 0.45f),
                    c + dir * inner, c + dir * outer,
                    strokeWidth = (maxR * 6.28f / n * 0.6f).coerceAtLeast(3f), cap = StrokeCap.Round,
                )
            }

            // "You": a small chevron pointing straight up.
            val you = Path().apply {
                moveTo(c.x, c.y - inner * 0.7f)
                lineTo(c.x - inner * 0.45f, c.y + inner * 0.45f)
                lineTo(c.x, c.y + inner * 0.2f)
                lineTo(c.x + inner * 0.45f, c.y + inner * 0.45f)
                close()
            }
            drawPath(you, youColor)

            if (estimate != null) {
                rotate(arrowDeg, c) {
                    val col = when {
                        facing -> HOT
                        target == null -> Color.Gray.copy(alpha = 0.5f)
                        else -> lerp(COLD, HOT, 0.6f)
                    }
                    drawLine(col, Offset(c.x, c.y - inner), Offset(c.x, c.y - maxR * 0.9f), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
                    val head = Path().apply {
                        moveTo(c.x, c.y - maxR)
                        lineTo(c.x - maxR * 0.1f, c.y - maxR * 0.8f)
                        lineTo(c.x + maxR * 0.1f, c.y - maxR * 0.8f)
                        close()
                    }
                    drawPath(head, col)
                }
                if (facing) drawCircle(HOT.copy(alpha = 0.25f), radius = maxR, style = Stroke(10.dp.toPx()))
            }
            if (s.phase == PointPhase.SWEEPING) {
                // Progress around the rim.
                drawArc(
                    Color.Gray.copy(alpha = 0.6f), -90f, 360f * s.coverage, useCenter = false,
                    topLeft = Offset(c.x - maxR, c.y - maxR), size = Size(maxR * 2, maxR * 2),
                    style = Stroke(4.dp.toPx()),
                )
            }
        }
    }
}

fun pointHeadline(s: PointState): String {
    val r = s.result
    return when {
        s.unsupported -> "No rotation sensor"
        s.phase == PointPhase.SWEEPING && s.tooFast -> "Slower…"
        s.phase == PointPhase.SWEEPING -> "Turn slowly ${(s.coverage * 100).roundToInt()}%"
        r == null || !r.clear -> "No clear direction"
        s.heading == null -> "…"
        DirectionSweep.isFacing(s.heading, r.headingDeg) -> "This way!"
        else -> {
            val d = DirectionSweep.delta(s.heading, r.headingDeg)
            "Turn ${if (d < 0) "left" else "right"} ${abs(d).roundToInt()}°"
        }
    }
}

fun pointDetail(s: PointState): String {
    val r = s.result
    return when {
        s.unsupported -> "This phone can't tell which way it's facing."
        s.phase == PointPhase.SWEEPING ->
            "Hold the phone against your chest and turn on the spot, a full circle in about 10 s. Your body blocks the signal from behind."
        r == null || !r.clear ->
            "The signal barely changed as you turned${r?.let { " (${it.contrastDb.roundToInt()} dB)" } ?: ""}. It may be very close, or bouncing off walls. Move a few steps and sweep again."
        else -> "Walk that way a few metres, then sweep again to refine. Signal ${r.contrastDb.roundToInt()} dB stronger than behind you."
    }
}
