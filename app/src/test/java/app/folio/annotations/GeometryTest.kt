package app.folio.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    private fun line() = floatArrayOf(
        0.1f, 0.5f, 1f, 0.3f, 0.5f, 1f, 0.5f, 0.5f, 1f, 0.7f, 0.5f, 1f, 0.9f, 0.5f, 1f
    )

    @Test fun eraserFarAwayDoesNothing() {
        assertNull(splitByEraser(line(), 1000f, 1000f, floatArrayOf(500f, 100f), 50f))
    }

    @Test fun eraserInMiddleSplitsStrokeInTwo() {
        val parts = splitByEraser(line(), 1000f, 1000f, floatArrayOf(500f, 500f), 50f)
        assertNotNull(parts)
        assertEquals(2, parts!!.size)
        assertEquals(6, parts[0].size)
        assertEquals(6, parts[1].size)
    }

    @Test fun eraserOverWholeStrokeRemovesIt() {
        val er = floatArrayOf(100f, 500f, 300f, 500f, 500f, 500f, 700f, 500f, 900f, 500f)
        val parts = splitByEraser(line(), 1000f, 1000f, er, 60f)
        assertNotNull(parts)
        assertTrue(parts!!.isEmpty())
    }

    @Test fun rotateClockwiseMapsPoint() {
        val r = rotateGeometry("PEN", floatArrayOf(0.2f, 0.1f, 1f), 1)
        assertEquals(0.9f, r[0], 1e-5f)
        assertEquals(0.2f, r[1], 1e-5f)
        assertEquals(1f, r[2], 1e-5f)
    }

    @Test fun fourQuarterTurnsIsIdentity() {
        val src = floatArrayOf(0.2f, 0.1f, 1f, 0.6f, 0.7f, 0.5f)
        val r = rotateGeometry("PEN", rotateGeometry("PEN", rotateGeometry("PEN", rotateGeometry("PEN", src, 1), 1), 1), 1)
        for (i in src.indices) assertEquals(src[i], r[i], 1e-5f)
    }

    @Test fun rotateTextRectKeepsOrder() {
        val r = rotateGeometry("TEXT_HL", floatArrayOf(0.1f, 0.2f, 0.4f, 0.3f), 1)
        assertTrue(r[0] <= r[2])
        assertTrue(r[1] <= r[3])
    }

    @Test fun underlineIsSnappedToHorizontalLine() {
        val r = snapUnderline(floatArrayOf(0.2f, 0.40f, 1f, 0.5f, 0.44f, 1f, 0.8f, 0.42f, 1f))
        assertEquals(6, r.size)
        assertEquals(0.2f, r[0], 1e-5f)
        assertEquals(0.8f, r[3], 1e-5f)
        assertEquals(r[1], r[4], 1e-5f)
    }
}
