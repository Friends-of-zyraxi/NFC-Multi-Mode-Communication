package io.github.zyraxi21.nfc.cast

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** 一次授权只创建一次 VirtualDisplay；旋转时更换编码 Surface，并在流内重新发送参数集。 */
internal class ScreenEncoder(
    private val projection: MediaProjection,
    private val output: OutputStream,
    private val streamId: String,
    private val density: Int,
    private val onSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val thread = HandlerThread("screen-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val writer = Executors.newSingleThreadExecutor()
    private val packets = ArrayBlockingQueue<VideoPacket>(8)
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var config: VideoPacket.Config? = null
    private var waitingForKey = true
    @Volatile private var lastWrite = SystemClock.elapsedRealtime()

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { if (!closed.get()) onError("系统已停止屏幕共享") }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!closed.get()) runCatching { configure(width, height) }
                .onFailure { onError("无法调整共享画面尺寸") }
        }
    }

    fun start(initialWidth: Int, initialHeight: Int) {
        writer.execute {
            try {
                VideoWire.header(output, streamId)
                while (!closed.get()) {
                    val packet = packets.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    VideoWire.write(output, packet)
                    lastWrite = SystemClock.elapsedRealtime()
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
            } catch (_: Exception) { if (!closed.get()) onError("无法启动录屏，请重新授权或检查设备编码能力") }
        }
    }

    private fun configure(sourceWidth: Int, sourceHeight: Int) {
        require(sourceWidth > 0 && sourceHeight > 0)
        val scale = min(1.0, min(1280.0 / maxOf(sourceWidth, sourceHeight), 720.0 / minOf(sourceWidth, sourceHeight)))
        // 16 像素对齐兼容硬件 AVC 编码器；轻微尺寸取整由虚拟显示负责缩放。
        val w = maxOf(16, (sourceWidth * scale).toInt() / 16 * 16)
        val h = maxOf(16, (sourceHeight * scale).toInt() / 16 * 16)
        if (w == width && h == height && codec != null) return
        display?.surface = null
        val oldCodec = codec; codec = null
        runCatching { oldCodec?.stop() }; runCatching { oldCodec?.release() }
        surface?.release(); surface = null
        width = w; height = h; config = null; waitingForKey = true; packets.clear()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 15f)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 15)
        }
        val next = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = next
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
                            val bytes = ByteArray(info.size)
                            requireNotNull(codec.getOutputBuffer(index)).apply {
                                position(info.offset); limit(info.offset + info.size); get(bytes)
                            }
                            if (packets.offer(VideoPacket.Frame(key, info.presentationTimeUs.coerceAtLeast(0), bytes))) {
                                if (key) waitingForKey = false
                            } else {
                                // 丢弃整个未发送 GOP，等待新的关键帧，避免解码损坏及延迟无限增长。
                                packets.clear(); config?.let { packets.offer(it) }; waitingForKey = true
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
        if (!closed.get()) runCatching { codec?.setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }) }
    } }

    private fun watchdog() {
        if (closed.get()) return
        if (SystemClock.elapsedRealtime() - lastWrite > 15_000) {
            onError("连接速度不足或视频发送超时，请重新连接")
        } else handler.postDelayed(::watchdog, 3000)
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
}
