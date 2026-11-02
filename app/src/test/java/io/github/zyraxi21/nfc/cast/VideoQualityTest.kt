package io.github.zyraxi21.nfc.cast

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VideoQualityTest {
    @Test fun nativePhoneResolutionIsPreservedExceptRequiredUpwardAlignment() {
        assertEquals(1080, VideoQuality.aligned(1080, 2))
        assertEquals(2656, VideoQuality.aligned(2656, 16))
        assertEquals(1232, VideoQuality.aligned(1220, 16))
        assertEquals(3840, VideoQuality.aligned(3840, 16))
    }
    @Test fun portraitAndLandscapeHaveSameHighBitrateTargetAndRespectCeiling() {
        assertEquals(VideoQuality.targetBitrate(1080, 2400, 30), VideoQuality.targetBitrate(2400, 1080, 30))
        assertTrue(VideoQuality.targetBitrate(1080, 2400, 30) > 8_000_000)
        assertEquals(40_000_000, VideoQuality.targetBitrate(3840, 2160, 60))
    }
    @Test fun protocolAcceptsNativePortraitAnd4kButRejectsExcessiveAllocation() {
        for ((w, h) in listOf(1220 to 2656, 3840 to 2160, 2160 to 3840)) {
            val bytes = ByteArrayOutputStream()
            VideoWire.write(bytes, VideoPacket.Config(w, h, byteArrayOf(1), byteArrayOf(2)))
            val decoded = VideoWire.read(ByteArrayInputStream(bytes.toByteArray())) as VideoPacket.Config
            assertEquals(w, decoded.width); assertEquals(h, decoded.height)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoWire.write(ByteArrayOutputStream(), VideoPacket.Config(8192, 8192, byteArrayOf(1), byteArrayOf(2)))
        }
    }
    @Test fun adaptiveBitrateIsClampedToSelectedEncoderCapabilities() {
        val flow = VideoFlowControl()
        flow.configure(1_000_000, 12_000_000, 20_000_000)
        assertEquals(12_000_000, flow.bitrate)
        repeat(100) { flow.reserve(it.toLong(), 0); flow.acknowledge(it.toLong(), 10) }
        assertEquals(12_000_000, flow.bitrate)
        repeat(100) { flow.reduce() }
        assertEquals(1_000_000, flow.bitrate)
    }
}
