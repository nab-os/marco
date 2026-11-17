package dev.nabos.marco.vision

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

/** Side of a board square on screen, about what a code-5 board needs to fit a phone. */
const val BOARD_SQUARE_MM = 2.5

/** Window brightness while shown: full brightness blows the board out in a dim room. */
private const val BOARD_BRIGHTNESS = 0.5f

/**
 * Vernier's coded checkerboard over the whole screen, one board pixel per
 * screen pixel, for the other phone to calibrate its camera on. Hides the
 * system bars and sets a steady brightness while shown.
 *
 * Squares are a whole number of pixels, sized from the screen's density; that
 * density only sets the board's scale, which the lens calibration doesn't need.
 */
@Composable
fun CalibrationBoard(modifier: Modifier = Modifier) {
    val activity = LocalContext.current.findActivity()
    val bounds = activity.windowManager.currentWindowMetrics.bounds
    val xdpi = activity.resources.displayMetrics.xdpi
    val squarePx = (BOARD_SQUARE_MM / 25.4 * xdpi).roundToInt().coerceAtLeast(8)
    val board = remember(bounds.width(), bounds.height(), squarePx) {
        boardBitmap(bounds.width(), bounds.height(), squarePx)
    }

    DisposableEffect(activity) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        val brightness = window.attributes.screenBrightness
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = BOARD_BRIGHTNESS }
        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
    }

    Canvas(modifier.fillMaxSize().background(Color.Black)) {
        board?.let {
            // Pixel for pixel: no scaling, no filtering.
            drawImage(it, dstOffset = IntOffset.Zero, dstSize = IntSize(it.width, it.height), filterQuality = FilterQuality.None)
        }
    }
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("CalibrationBoard needs an Activity")
}

private fun boardBitmap(width: Int, height: Int, squarePx: Int): ImageBitmap? {
    if (!Vernier.available) return null
    val grey = Vernier.renderBoard(width, height, squarePx.toDouble()) ?: return null
    val pixels = IntArray(grey.size) { i ->
        val g = grey[i].toInt() and 0xFF
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
