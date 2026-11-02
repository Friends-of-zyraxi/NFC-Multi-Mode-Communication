package io.github.zyraxi21.nfc.cast

/** 以接收端反馈约束端到端在途帧数，而非只限制本地管道队列。时钟只使用发送端。 */
internal class VideoFlowControl {
    private data class Pending(val sentAt: Long, val bytes: Int)
    private val pending = linkedMapOf<Long, Pending>()
    private var lastReduction: Long? = null
    private var lastAdjustment: Long? = null
    private var sampleStart: Long? = null
    private var sampleBytes = 0L
    private var confirmedBitrate = 0
    private var feedbackMs = 0L
    var bitrate = 8_000_000
        private set
    private var minimum = VideoQuality.MIN_BITRATE
    private var maximum = VideoQuality.MAX_BITRATE
    @Synchronized fun configure(minimum: Int, maximum: Int, initial: Int) {
        require(minimum > 0 && maximum >= minimum)
        this.minimum = minimum; this.maximum = maximum
        bitrate = initial.coerceIn(minimum, maximum); goodSamples = 0
        pending.clear(); lastReduction = null; lastAdjustment = null
        sampleStart = null; sampleBytes = 0; confirmedBitrate = 0; feedbackMs = 0
    }
    private var goodSamples = 0
    @Synchronized fun reserve(timeUs: Long, nowMs: Long, bytes: Int = 0): Boolean {
        require(bytes >= 0)
        if (pending.size >= 4 || pending.containsKey(timeUs)) return false
        pending[timeUs] = Pending(nowMs, bytes)
        if (sampleStart == null) sampleStart = nowMs
        return true
    }
    @Synchronized fun acknowledge(timeUs: Long, nowMs: Long): Boolean {
        val sent = pending.remove(timeUs) ?: return false
        val delay = (nowMs - sent.sentAt).coerceAtLeast(0)
        feedbackMs = delay; sampleBytes += sent.bytes
        if (delay > 350) {
            reduce(nowMs)
        } else if (delay <= 300) {
            goodSamples++
            val previous = lastAdjustment ?: nowMs.also { lastAdjustment = it }
            if (goodSamples >= 3 && nowMs - previous >= 1500) {
                bitrate = (bitrate * 1.15 + 100_000).toInt().coerceAtMost(maximum)
                lastAdjustment = nowMs; goodSamples = 0
            }
        } else goodSamples = 0
        return true
    }
    @Synchronized fun congested(nowMs: Long): Boolean = pending.values.firstOrNull()?.let { nowMs - it.sentAt > 1000 } == true
    @Synchronized fun reduce(nowMs: Long) {
        goodSamples = 0
        // 同一批迟到确认与看门狗共享冷却，避免短时间连续乘法降码率。
        if (lastReduction?.let { nowMs - it < 1000 } == true) return
        bitrate = (bitrate * 0.65).toInt().coerceAtLeast(minimum)
        lastReduction = nowMs; lastAdjustment = nowMs
    }
    @Synchronized fun snapshot(nowMs: Long): VideoTransportStats {
        val start = sampleStart
        if (start != null && nowMs - start >= 1000) {
            confirmedBitrate = (sampleBytes * 8000 / (nowMs - start)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            sampleBytes = 0; sampleStart = nowMs
        }
        return VideoTransportStats(confirmedBitrate, feedbackMs, pending.size)
    }
    @Synchronized fun resetFrames() { pending.clear() }
}

internal data class VideoTransportStats(val confirmedBitrate: Int = 0, val feedbackMs: Long = 0, val pendingFrames: Int = 0)

/** 一次恢复请求只等待一个关键帧，重复反馈不会触发关键帧风暴。 */
internal class KeyFrameGate {
    private var requestedAt: Long? = null
    private var awaiting = false
    fun request(nowMs: Long): Boolean {
        val previous = requestedAt
        if (awaiting && previous != null && nowMs - previous < 1000) return false
        requestedAt = nowMs; awaiting = true
        return true
    }
    fun received() { awaiting = false }
}
