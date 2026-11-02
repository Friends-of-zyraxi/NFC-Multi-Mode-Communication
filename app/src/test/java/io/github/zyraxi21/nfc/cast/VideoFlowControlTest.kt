package io.github.zyraxi21.nfc.cast

import org.junit.Assert.*
import org.junit.Test

class VideoFlowControlTest {
    @Test fun slowReceiverNeverAllowsMoreThanFourUnacknowledgedFrames() {
        val flow = VideoFlowControl()
        assertTrue(flow.reserve(1, 0)); assertTrue(flow.reserve(2, 0)); assertTrue(flow.reserve(3, 0)); assertTrue(flow.reserve(4, 0))
        repeat(1000) { assertFalse(flow.reserve(5L + it, 1000)) }
        assertTrue(flow.acknowledge(1, 1000))
        assertTrue(flow.reserve(1005, 1000))
        assertFalse(flow.reserve(1006, 1001))
        assertTrue(flow.bitrate < 8_000_000)
    }
    @Test fun unrelatedAcknowledgementsDoNotReleaseWindowOrIncreaseBitrate() {
        val flow = VideoFlowControl()
        flow.reserve(1, 0); flow.reserve(2, 0); flow.reserve(3, 0); flow.reserve(4, 0)
        assertFalse(flow.acknowledge(999, 1)); assertFalse(flow.reserve(5, 2))
        assertEquals(8_000_000, flow.bitrate)
    }
    @Test fun sustainedCongestionReducesBitrateWithoutDisconnectingAndCanRecover() {
        val flow = VideoFlowControl()
        flow.reserve(1, 0)
        assertTrue(flow.congested(1100))
        repeat(50) { flow.reduce(it * 1000L) }
        assertEquals(1_000_000, flow.bitrate)
        flow.acknowledge(1, 50_000)
        repeat(30) {
            val now = 52_000 + it * 100L
            flow.reserve(it.toLong(), now); flow.acknowledge(it.toLong(), now + 50)
        }
        assertTrue(flow.bitrate > 1_000_000)
        flow.resetFrames(); assertTrue(flow.reserve(999, 7000))
    }

    @Test fun aBatchOfLateFeedbackAndWatchdogOnlyReducesBitrateOnce() {
        val flow = VideoFlowControl()
        repeat(4) { assertTrue(flow.reserve(it.toLong(), 0)) }
        repeat(4) { assertTrue(flow.acknowledge(it.toLong(), 500 + it.toLong())) }
        flow.reduce(510)
        assertEquals(5_200_000, flow.bitrate)
        flow.reduce(1500)
        assertEquals(3_380_000, flow.bitrate)
    }

    @Test fun stableModerateLatencyRecoversWithoutRequiringSub150msFeedback() {
        val flow = VideoFlowControl()
        flow.reduce(0)
        val reduced = flow.bitrate
        repeat(30) {
            val now = 2000 + it * 100L
            assertTrue(flow.reserve(it.toLong(), now, 25_000))
            assertTrue(flow.acknowledge(it.toLong(), now + 250))
        }
        assertTrue(flow.bitrate > reduced)
        val stats = flow.snapshot(6000)
        assertTrue(stats.confirmedBitrate > 0)
        assertEquals(250L, stats.feedbackMs)
        assertEquals(0, stats.pendingFrames)
        assertEquals(0, flow.snapshot(8000).confirmedBitrate)
    }

    @Test fun repeatedFeedbackOnlyRequestsOneRecoveryKeyFrameUntilItArrives() {
        val gate = KeyFrameGate()
        assertTrue(gate.request(0))
        repeat(100) { assertFalse(gate.request(it + 1L)) }
        assertTrue(gate.request(1000))
        gate.received()
        assertTrue(gate.request(1001))
    }
}
