package io.github.zyraxi21.nfc.cast

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Surface 与 Activity 分离：旋转或暂时离开接收页时仍排空网络流，返回后从关键帧恢复。 */
internal class ScreenDecoder(
    private val input: InputStream,
    private val expectedId: String,
    private val onSize: (Int, Int) -> Unit,
    private val onFrame: () -> Unit,
    private val onError: (String) -> Unit,
    private val requestKey: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val target = AtomicReference<Surface?>(null)
    private val worker = Executors.newSingleThreadExecutor()
    private var codec: MediaCodec? = null // 仅在 worker 内访问
    private var codecSurface: Surface? = null
    private var format: VideoPacket.Config? = null
    private var needsKey = true
    private var reportedFrame = false

    fun attach(surface: Surface?) {
        target.set(surface)
        if (surface != null && !closed.get()) requestKey()
    }

    fun start() { worker.execute {
        try {
            require(VideoWire.header(input) == expectedId)
            while (!closed.get()) {
                when (val packet = VideoWire.read(input)) {
                    is VideoPacket.Config -> {
                        releaseCodec(); format = packet; onSize(packet.width, packet.height)
                    }
                    is VideoPacket.Frame -> {
                        val before = target.get()
                        try { decode(packet) } catch (error: Exception) {
                            if (target.get() !== before || before?.isValid == false) {
                                releaseCodec(); requestKey()
                            } else throw error
                        }
                    }
                }
            }
        } catch (_: Exception) { if (!closed.get()) onError("视频接收中断或设备无法解码，请重新开始共享") }
        finally { releaseCodec(); runCatching { input.close() } }
    } }

    private fun decode(frame: VideoPacket.Frame) {
        val surface = target.get()?.takeIf { it.isValid }
        if (surface !== codecSurface) releaseCodec()
        if (surface == null) return
        val config = format ?: error("缺少视频参数")
        if (needsKey && !frame.key) return
        if (codec == null) {
            val next = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = next
            val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config.sps)); setByteBuffer("csd-1", ByteBuffer.wrap(config.pps))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, VideoWire.MAX_FRAME)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            next.configure(mediaFormat, surface, null, 0); next.start(); codecSurface = surface
        }
        val decoder = requireNotNull(codec)
        needsKey = false
        val deadline = SystemClock.elapsedRealtime() + 1000
        var index: Int
        while (true) {
            drain(decoder)
            index = decoder.dequeueInputBuffer(10_000)
            if (index >= 0) break
            check(!closed.get() && SystemClock.elapsedRealtime() < deadline)
        }
        requireNotNull(decoder.getInputBuffer(index)).apply { clear(); put(frame.bytes) }
        decoder.queueInputBuffer(index, 0, frame.bytes.size, frame.timeUs, 0)
        drain(decoder)
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (!closed.get()) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    decoder.releaseOutputBuffer(index, true)
                    if (!reportedFrame) { reportedFrame = true; onFrame() }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return
            }
        }
    }

    private fun releaseCodec() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }
        codec = null; codecSurface = null; needsKey = true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        target.set(null); runCatching { input.close() }; worker.shutdownNow()
    }
}
