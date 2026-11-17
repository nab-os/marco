package dev.nabos.marco.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingHistoryTest {
    @Test fun interpolatesBetweenReadings() {
        val h = HeadingHistory()
        h.add(1_000, 10.0)
        h.add(1_100, 20.0)
        assertEquals(15.0, h.at(1_050)!!, 1e-9)
        assertEquals(20.0, h.at(1_500)!!, 1e-9) // newer than the history: latest
    }

    @Test fun interpolatesTheShortWayAcrossNorth() {
        val h = HeadingHistory()
        h.add(0, 350.0)
        h.add(100, 10.0)
        assertEquals(0.0, h.at(50)!!, 1e-9)
    }

    @Test fun forgetsOldReadings() {
        val h = HeadingHistory(keepMs = 1_000)
        h.add(0, 0.0)
        h.add(5_000, 90.0)
        assertNull(h.at(100))
    }
}
