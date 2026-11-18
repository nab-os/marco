package dev.nabos.marco.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.nabos.marco.finder.MapState
import dev.nabos.marco.finder.SignalMap
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The signal map seen from above, turned so the way you face is up, centred
 * on you: where the other phone likely is (warmer = likelier), your path, and
 * the most likely spot ringed by how sure the map is, once it is sure enough to
 * point (before that, only the heat). Rings every metre. You
 * are the arrow, in [youColor]: the signal's hot/cold colour.
 */
@Composable
fun SignalMapView(m: MapState, youColor: Color, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val trail = MaterialTheme.colorScheme.primary
    val you = youColor
    Canvas(modifier) {
        val here = m.here ?: return@Canvas
        val e = m.estimate
        val reach = maxOf(
            e?.let { hypot(it.x - here.x, it.y - here.y) + it.spreadM } ?: 0.0,
            m.acoustic?.let { hypot(it.x - here.x, it.y - here.y) + it.spreadM } ?: 0.0,
        )
        val radiusM = (reach * 1.15).coerceIn(MIN_RADIUS_M, MAX_RADIUS_M).toFloat()
        val scalePx = size.minDimension / 2 / radiusM
        // The world drawn in metres around you, y flipped (screen y points down) and
        // turned so your heading points up.
        val headingDeg = Math.toDegrees(atan2(-here.headingY.toDouble(), here.headingX.toDouble())).toFloat()
        fun local(x: Double, y: Double) = Offset((x - here.x).toFloat(), -(y - here.y).toFloat())

        clipRect {
            translate(size.width / 2, size.height / 2) {
                for (r in 1..radiusM.toInt()) drawCircle(ring, r * scalePx, Offset.Zero, style = Stroke(1.dp.toPx()))
                rotate(-90f - headingDeg, Offset.Zero) {
                    scale(scalePx, scalePx, Offset.Zero) {
                        e?.let { heat(it, ::local) }
                        val points = m.track.path
                        if (points.size > 1) {
                            val step = max(1, points.size / MAX_PATH_POINTS)
                            val line = Path()
                            points.forEachIndexed { i, p ->
                                if (i % step != 0 && i != points.size - 1) return@forEachIndexed
                                val o = local(p.x.toDouble(), p.y.toDouble())
                                if (i == 0) line.moveTo(o.x, o.y) else line.lineTo(o.x, o.y)
                            }
                            drawPath(line, trail, style = Stroke(3.dp.toPx() / scalePx))
                        }
                        // The spot only once the map is sure enough to point: before, its peak wanders over the blob.
                        e?.takeIf { it.spreadM <= UNSURE_M }?.let {
                            val spot = local(it.x, it.y)
                            drawCircle(HOT, it.spreadM.toFloat(), spot, style = Stroke(2.dp.toPx() / scalePx))
                            drawCircle(HOT, 6.dp.toPx() / scalePx, spot)
                        }
                        // By sound, while the other phone rings: sharper, in green.
                        m.acoustic?.let {
                            val spot = local(it.x, it.y)
                            drawCircle(SOUND, it.spreadM.toFloat(), spot, style = Stroke(2.dp.toPx() / scalePx))
                            drawCircle(SOUND, 5.dp.toPx() / scalePx, spot)
                        }
                    }
                }
                // You, facing up.
                val s = 12.dp.toPx()
                val arrow = Path().apply {
                    moveTo(0f, -s)
                    lineTo(s * 0.7f, s * 0.8f)
                    lineTo(0f, s * 0.4f)
                    lineTo(-s * 0.7f, s * 0.8f)
                    close()
                }
                drawPath(arrow, you)
            }
        }
    }
}

/** The probability as cells, warmer and more opaque where likelier; the faintest left out. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.heat(e: SignalMap.Estimate, local: (Double, Double) -> Offset) {
    val peak = e.probability.max()
    if (peak <= 0f) return
    val cell = e.cellM.toFloat()
    for (i in e.probability.indices) {
        val level = e.probability[i] / peak
        if (level < 0.03f) continue
        val o = local(e.originX + (i % e.cols) * e.cellM, e.originY + (i / e.cols) * e.cellM)
        drawRect(lerp(COLD, HOT, level).copy(alpha = 0.15f + 0.6f * level), Offset(o.x - cell / 2, o.y - cell / 2), Size(cell, cell))
    }
}

/**
 * Where the phone is across the camera feed, 0 (left edge) to 1 (right edge), from
 * the angle between where the camera points and the phone; outside that range when
 * it is out of view (beyond ±1 means behind). Null without a target or a lens.
 */
fun targetAcrossFeed(m: MapState): Float? {
    val here = m.here ?: return null
    if (m.previewHalfFovTan <= 0f) return null
    val (tx, ty) = m.acoustic?.let { it.x to it.y }
        ?: m.estimate?.takeIf { it.spreadM <= UNSURE_M }?.let { it.x to it.y }
        ?: return null
    val dx = tx - here.x
    val dy = ty - here.y
    val ahead = dx * here.headingX + dy * here.headingY
    val right = dx * here.headingY - dy * here.headingX
    if (ahead <= 0.0) return if (right >= 0) 2f else -2f // behind: an arrow at the edge
    val u = 0.5 + (right / ahead) / (2 * m.previewHalfFovTan)
    return u.toFloat().coerceIn(-1.5f, 2.5f)
}

/** The target's colour: the sound fix when there is one, else the signal map's. */
fun targetColour(m: MapState): Color = if (m.acoustic != null) SOUND else HOT

/** What to do next, in words: where the phone likely is from where you stand. */
fun mapHeadline(m: MapState): String {
    val here = m.here ?: return "Starting the cameras…"
    m.acoustic?.takeIf { it.spreadM < SOUND_SURE_M }?.let { fix ->
        val dx = fix.x - here.x
        val dy = fix.y - here.y
        val distance = hypot(dx, dy)
        if (distance < 0.5) return "Right here"
        return "%.1f m %s".format(distance, direction(here.headingX.toDouble(), here.headingY.toDouble(), dx, dy))
    }
    val e = m.estimate ?: return "Walk around"
    if (e.spreadM > UNSURE_M) return "Keep walking"
    val dx = e.x - here.x
    val dy = e.y - here.y
    val distance = hypot(dx, dy)
    if (distance < max(1.0, e.spreadM)) return "Right around here"
    return "≈ ${distance.roundToInt()} m ${direction(here.headingX.toDouble(), here.headingY.toDouble(), dx, dy)}"
}

fun mapDetail(m: MapState): String {
    m.track.error?.let { return it }
    m.acoustic?.let { fix ->
        return "By its ring: within about %.0f cm, from %d chirps".format(maxOf(fix.spreadM, 0.1) * 100, fix.chirps) +
            if (m.listening) " · listening" else ""
    }
    if (m.listening) return "Listening to the ring: walk a metre or two and it will place the phone by sound (${m.chirps} chirps so far)."
    m.micError?.let { return it }
    val e = m.estimate
    return when {
        m.here == null -> "Hold the phone up, cameras facing the room."
        e == null -> "Walk across the room with the phone held up: the map needs readings from different spots (${m.readings} so far)."
        e.spreadM > UNSURE_M -> "Turn a corner or cross the room: walking in a straight line can't tell left from right. ${m.readings} readings."
        else -> "Within about ${max(1, e.spreadM.roundToInt())} m · ${m.readings} readings · rings every metre"
    }
}

/** The way to (dx, dy) from someone facing (hx, hy), in words. */
private fun direction(hx: Double, hy: Double, dx: Double, dy: Double): String {
    // Clockwise from ahead: right is (hy, −hx).
    val deg = Math.toDegrees(atan2(dx * hy - dy * hx, dx * hx + dy * hy))
    return when {
        deg in -22.5..22.5 -> "ahead"
        deg in 22.5..67.5 -> "ahead, to the right"
        deg in 67.5..112.5 -> "to your right"
        deg in 112.5..157.5 -> "behind you, to the right"
        deg in -67.5..-22.5 -> "ahead, to the left"
        deg in -112.5..-67.5 -> "to your left"
        deg in -157.5..-112.5 -> "behind you, to the left"
        else -> "behind you"
    }
}

private val SOUND = Color(0xFF4CD37B)
/** A sound fix surer than this leads the words. */
private const val SOUND_SURE_M = 1.0
private const val MIN_RADIUS_M = 3.0
private const val MAX_RADIUS_M = 15.0
private const val MAX_PATH_POINTS = 600
/** Past this the map is too unsure to point anywhere. */
private const val UNSURE_M = 3.0
