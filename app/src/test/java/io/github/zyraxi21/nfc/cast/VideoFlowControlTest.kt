package io.github.zyraxi21.nfc.cast

import org.junit.Assert.*
import org.junit.Test

class VideoFlowControlTest {
    @Test fun slowReceiverNeverAllowsMoreThanTwoUnacknowledgedFrames() {
        val flow = VideoFlowControl()
        assertTrue(flow.reserve(1, 0)); assertTrue(flow.reserve(2, 0))
        repeat(1000) { assertFalse(flow.reserve(3L + it, 1000)) }
        assertTrue(flow.acknowledge(1, 1000))
        assertTrue(flow.reserve(1005, 1000))
        assertFalse(flow.reserve(1006, 1001))
        assertTrue(flow.bitrate < 384_000)
    }
    @Test fun unrelatedAcknowledgementsDoNotReleaseWindowOrIncreaseBitrate() {
        val flow = VideoFlowControl()
        flow.reserve(1, 0); flow.reserve(2, 0)
        assertFalse(flow.acknowledge(999, 1)); assertFalse(flow.reserve(3, 2))
        assertEquals(384_000, flow.bitrate)
    }
    @Test fun sustainedCongestionReducesBitrateWithoutDisconnectingAndCanRecover() {
        val flow = VideoFlowControl()
        flow.reserve(1, 0)
        assertTrue(flow.congested(1100))
        repeat(50) { flow.reduce() }
        assertEquals(96_000, flow.bitrate)
        flow.acknowledge(1, 5000)
        repeat(30) { flow.reserve(it.toLong(), 6000); flow.acknowledge(it.toLong(), 6050) }
        assertTrue(flow.bitrate > 96_000)
        flow.resetFrames(); assertTrue(flow.reserve(999, 7000))
    }
}
