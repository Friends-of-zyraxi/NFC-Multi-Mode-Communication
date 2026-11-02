package io.github.zyraxi21.nfc.cast

/** 以接收端反馈约束端到端在途帧数，而非只限制本地管道队列。时钟只使用发送端。 */
internal class VideoFlowControl {
    private val pending = linkedMapOf<Long, Long>()
    var bitrate = 384_000
        private set
    private var goodSamples = 0
    @Synchronized fun reserve(timeUs: Long, nowMs: Long): Boolean {
        if (pending.size >= 2 || pending.containsKey(timeUs)) return false
        pending[timeUs] = nowMs
        return true
    }
    @Synchronized fun acknowledge(timeUs: Long, nowMs: Long): Boolean {
        val sent = pending.remove(timeUs) ?: return false
        val delay = (nowMs - sent).coerceAtLeast(0)
        if (delay > 350) {
            bitrate = (bitrate * 0.65).toInt().coerceAtLeast(96_000); goodSamples = 0
        } else if (delay < 150 && ++goodSamples >= 30) {
            bitrate = (bitrate * 1.1).toInt().coerceAtMost(2_000_000); goodSamples = 0
        }
        return true
    }
    @Synchronized fun congested(nowMs: Long): Boolean = pending.values.firstOrNull()?.let { nowMs - it > 1000 } == true
    @Synchronized fun reduce() { bitrate = (bitrate * 0.65).toInt().coerceAtLeast(96_000); goodSamples = 0 }
    @Synchronized fun resetFrames() { pending.clear() }
}
