package dev.nabos.marco.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverageTest {
    // a b c
    // d e f
    private val image = listOf('a', 'b', 'c', 'd', 'e', 'f')

    private fun turned(degrees: Int): String {
        val (w, h) = if (degrees % 180 == 0) 3 to 2 else 2 to 3
        return (0 until h).joinToString("/") { y ->
            (0 until w).joinToString("") { x ->
                val (sx, sy) = upright(x, y, 3, 2, degrees)
                image[sy * 3 + sx].toString()
            }
        }
    }

    @Test fun turnsClockwise() {
        assertEquals("abc/def", turned(0))
        assertEquals("da/eb/fc", turned(90))
        assertEquals("fed/cba", turned(180))
        assertEquals("cf/be/ad", turned(270))
    }

    @Test fun gridTurnsWithThePreview() {
        // Only the sensor's top-left cell visited: upright on a portrait phone (90°), it's the top-right one.
        val grid = Coverage(columns = 3, rows = 2, inset = 0.1f, cells = listOf(true, false, false, false, false, false))
        val turned = grid.rotated(90)
        assertEquals(2 to 3, turned.columns to turned.rows)
        assertEquals(listOf(false, true, false, false, false, false), turned.cells)
    }
}
