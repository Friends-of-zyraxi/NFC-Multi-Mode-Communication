package io.github.zyraxi21.nfc.cast

import kotlin.math.roundToInt

internal object VideoQuality {
    const val TARGET_FPS = 30
    const val MAX_BITRATE = 40_000_000
    const val MIN_BITRATE = 1_000_000
    fun aligned(size: Int, alignment: Int): Int {
        require(size in 1..8192 && alignment in 1..8192)
        return ((size + alignment - 1) / alignment) * alignment
    }
    fun targetBitrate(width: Int, height: Int, fps: Int): Int {
        require(width > 0 && height > 0 && fps > 0)
        return (width.toDouble() * height * fps * 0.18).roundToInt().coerceIn(8_000_000, MAX_BITRATE)
    }
}
