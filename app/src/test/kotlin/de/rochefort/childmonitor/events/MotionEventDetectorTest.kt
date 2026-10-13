package de.rochefort.childmonitor.events

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MotionEventDetectorTest {
    private val width = 120
    private val height = 80

    private fun frame(base: Int, changedCells: IntRange = IntRange.EMPTY, delta: Int = 0): ByteArray {
        val bytes = ByteArray(width * height) { base.toByte() }
        val cellW = width / 12
        val cellH = height / 8
        for (cell in changedCells) {
            val gx = cell % 12
            val gy = cell / 12
            val x = gx * cellW + cellW / 2
            val y = gy * cellH + cellH / 2
            bytes[y * width + x] = (base + delta).coerceIn(0, 255).toByte()
        }
        return bytes
    }

    @Test fun `three sustained local changes emit motion`() {
        val detector = MotionEventDetector(cooldownMs = 5_000L)
        assertNull(detector.process(frame(50), width, height, 0L))
        assertNull(detector.process(frame(50, 0..23, 80), width, height, 500L))
        assertNull(detector.process(frame(50, 24..47, 80), width, height, 1_000L))
        val event = detector.process(frame(50, 48..71, 80), width, height, 1_500L)
        assertNotNull(event)
        assertEquals(50, event.changedPercent)
    }

    @Test fun `whole-frame exposure shift is ignored`() {
        val detector = MotionEventDetector()
        assertNull(detector.process(frame(40), width, height, 0L))
        assertNull(detector.process(frame(90), width, height, 500L))
        assertNull(detector.process(frame(130), width, height, 1_000L))
    }

    @Test fun `single noisy frame and cooldown do not spam`() {
        val detector = MotionEventDetector(cooldownMs = 5_000L)
        detector.process(frame(50), width, height, 0L)
        assertNull(detector.process(frame(50, 0..23, 80), width, height, 500L))
        assertNull(detector.process(frame(50), width, height, 1_000L))
        assertNull(detector.process(frame(50), width, height, 1_500L))
        detector.process(frame(50, 0..23, 80), width, height, 2_000L)
        detector.process(frame(50, 24..47, 80), width, height, 2_500L)
        assertNotNull(detector.process(frame(50, 48..71, 80), width, height, 3_000L))
        detector.process(frame(50, 0..23, 80), width, height, 3_500L)
        detector.process(frame(50, 24..47, 80), width, height, 4_000L)
        assertNull(detector.process(frame(50, 48..71, 80), width, height, 4_500L))
    }
}
