package io.github.zyraxi21.nfc.cast

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.OutputStream
import java.io.BufferedOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 一次授权只创建一次 VirtualDisplay；旋转时更换编码 Surface，并在流内重新发送参数集。 */
internal class ScreenEncoder(
    private val projection: MediaProjection,
    private val output: OutputStream,
    private val streamId: String,
    private val density: Int,
    private val onSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
    private val onQuality: (Int, Int) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val thread = HandlerThread("screen-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val writer = Executors.newSingleThreadExecutor()
    private val packets = ArrayBlockingQueue<VideoPacket>(8)
    private val flow = VideoFlowControl()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var config: VideoPacket.Config? = null
    private var waitingForKey = true
    private var appliedBitrate = flow.bitrate
    private var frameRate = VideoQuality.TARGET_FPS

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { if (!closed.get()) onError("系统已停止屏幕共享") }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!closed.get()) runCatching { configure(width, height) }
                .onFailure { onError(if (it is NativeResolutionException) it.message!! else "无法调整共享画面尺寸") }
        }
    }

    fun start(initialWidth: Int, initialHeight: Int) {
        writer.execute {
            try {
                val buffered = BufferedOutputStream(output, 32 * 1024)
                VideoWire.header(buffered, streamId)
                while (!closed.get()) {
                    val packet = packets.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    VideoWire.write(buffered, packet)
                }
            } catch (_: Exception) { if (!closed.get()) onError("视频发送中断，请重新开始共享") }
        }
        handler.post {
            if (closed.get()) return@post
            try {
                projection.registerCallback(callback, handler)
                configure(initialWidth, initialHeight)
                display = projection.createVirtualDisplay("NFC screen sharing", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler)
                watchdog()
            } catch (error: Exception) { if (!closed.get()) onError(if (error is NativeResolutionException) error.message!! else "无法启动录屏，请重新授权或检查设备编码能力") }
        }
    }

    private fun configure(sourceWidth: Int, sourceHeight: Int) {
        require(sourceWidth > 0 && sourceHeight > 0)
        val selected = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .sortedByDescending { it.isHardwareAccelerated }
            .firstNotNullOfOrNull { info -> runCatching {
                val codecCaps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                require(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in codecCaps.colorFormats)
                val caps = requireNotNull(codecCaps.videoCapabilities)
                require(caps.bitrateRange.lower <= VideoQuality.MAX_BITRATE && caps.bitrateRange.upper > 0)
                val w = VideoQuality.aligned(sourceWidth, caps.widthAlignment)
                val h = VideoQuality.aligned(sourceHeight, caps.heightAlignment)
                require(w <= 8192 && h <= 8192 && w.toLong() * h <= 33_554_432 && caps.isSizeSupported(w, h))
                val fps = caps.getSupportedFrameRatesFor(w, h).upper.toInt().coerceIn(1, VideoQuality.TARGET_FPS)
                require(caps.areSizeAndRateSupported(w, h, fps.toDouble()))
                NativeEncoder(info.name, w, h, fps, caps.bitrateRange.lower, caps.bitrateRange.upper)
            }.getOrNull() } ?: throw NativeResolutionException("设备编码器不支持原始分辨率 ${sourceWidth}×${sourceHeight}")
        val w = selected.width
        val h = selected.height
        android.util.Log.d("NativeCast", "Selected ${selected.name}: ${w}x${h}, ${selected.fps} fps")
        if (w == width && h == height && codec != null) return
        display?.surface = null
        val oldCodec = codec; codec = null
        runCatching { oldCodec?.stop() }; runCatching { oldCodec?.release() }
        surface?.release(); surface = null
        width = w; height = h; config = null; waitingForKey = true; packets.clear()
        frameRate = selected.fps
        val maximum = selected.maxBitrate.coerceAtMost(VideoQuality.MAX_BITRATE)
        val minimum = VideoQuality.MIN_BITRATE.coerceAtMost(maximum).coerceAtLeast(selected.minBitrate)
        flow.configure(minimum, maximum, VideoQuality.targetBitrate(w, h, frameRate))
        flow.resetFrames(); appliedBitrate = flow.bitrate
        onQuality(appliedBitrate, frameRate)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, appliedBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            // 参考 scrcpy：静止画面仅补发少量帧，不按目标帧率持续重复整屏。
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        val next = MediaCodec.createByCodecName(selected.name)
        codec = next
        // WebRTC 使用 CBR 约束突发流量；仅在当前编码器明确支持时启用。
        if (next.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        next.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (codec !== this@ScreenEncoder.codec || closed.get()) return
                try {
                    fun csd(key: String): ByteArray = requireNotNull(format.getByteBuffer(key)).duplicate().run {
                        ByteArray(remaining()).also { get(it) }
                    }
                    config = VideoPacket.Config(w, h, csd("csd-0"), csd("csd-1"))
                    packets.clear(); packets.offer(config!!); waitingForKey = true
                    onSize(w, h)
                } catch (_: Exception) { onError("设备未提供有效的 AVC 编码参数") }
            }
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (codec !== this@ScreenEncoder.codec || closed.get()) return
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        require(info.size <= VideoWire.MAX_FRAME)
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (config != null && (!waitingForKey || key)) {
                            if (!flow.reserve(info.presentationTimeUs.coerceAtLeast(0), SystemClock.elapsedRealtime())) {
                                waitingForKey = true
                                return
                            }
                            val bytes = ByteArray(info.size)
                            requireNotNull(codec.getOutputBuffer(index)).apply {
                                position(info.offset); limit(info.offset + info.size); get(bytes)
                            }
                            if (packets.offer(VideoPacket.Frame(key, info.presentationTimeUs.coerceAtLeast(0), bytes))) {
                                if (key) waitingForKey = false
                            } else {
                                // 丢弃整个未发送 GOP，等待新的关键帧，避免解码损坏及延迟无限增长。
                                packets.clear(); flow.resetFrames(); config?.let { packets.offer(it) }; waitingForKey = true
                                requestKeyFrame()
                            }
                        }
                    }
                } catch (_: Exception) { onError("视频编码失败") }
                finally { runCatching { codec.releaseOutputBuffer(index, false) } }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                if (codec === this@ScreenEncoder.codec && !closed.get()) onError("设备视频编码器异常")
            }
        }, handler)
        next.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = next.createInputSurface()
        next.start()
        display?.apply { resize(w, h, density); surface = this@ScreenEncoder.surface }
    }

    fun requestKeyFrame() { handler.post {
        flow.resetFrames()
        if (!closed.get()) runCatching { codec?.setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }) }
    } }

    fun acknowledge(timeUs: Long) { handler.post {
        if (!closed.get() && flow.acknowledge(timeUs, SystemClock.elapsedRealtime())) {
            updateBitrate()
            if (waitingForKey) runCatching { codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }) }
        }
    } }

    private fun updateBitrate() {
        if (appliedBitrate != flow.bitrate) runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, flow.bitrate) })
            appliedBitrate = flow.bitrate
            onQuality(appliedBitrate, frameRate)
        }
    }

    private fun watchdog() {
        if (closed.get()) return
        if (flow.congested(SystemClock.elapsedRealtime())) { flow.reduce(); updateBitrate() }
        handler.postDelayed(::watchdog, 2000)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }; writer.shutdownNow()
        handler.post {
            projection.unregisterCallback(callback)
            display?.release(); display = null
            runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
            surface?.release(); surface = null
            runCatching { projection.stop() }
            thread.quitSafely()
        }
    }

    private data class NativeEncoder(val name: String, val width: Int, val height: Int, val fps: Int, val minBitrate: Int, val maxBitrate: Int)
    private class NativeResolutionException(message: String) : Exception(message)
}
