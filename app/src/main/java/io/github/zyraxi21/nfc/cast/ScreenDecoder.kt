package io.github.zyraxi21.nfc.cast

import android.content.res.Resources
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.zyraxi21.nfc.R
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Surface 与 Activity 分离：旋转或暂时离开接收页时仍排空网络流，返回后从关键帧恢复。 */
internal class ScreenDecoder(
    private val resources: Resources,
    private val input: InputStream,
    private val expectedId: String,
    private val onSize: (Int, Int) -> Unit,
    private val onFrame: () -> Unit,
    private val onError: (String) -> Unit,
    private val requestKey: () -> Unit,
    private val onRendered: (Long) -> Unit = {},
    private val onCodec: (String, Boolean) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val target = AtomicReference<Surface?>(null)
    private val worker = Executors.newSingleThreadExecutor()
    private val decodeThread = HandlerThread("screen-decoder").apply { start() }
    private val handler = Handler(decodeThread.looper)
    private val queued = Semaphore(2)
    private var codec: MediaCodec? = null // 仅在 decodeThread 内访问
    private var codecSurface: Surface? = null
    private var format: VideoPacket.Config? = null
    private var needsKey = true
    private var reportedFrame = false

    fun attach(surface: Surface?) {
        target.set(surface)
        if (surface != null && !closed.get()) requestKey()
    }

    fun start() {
        handler.post(::drainContinuously)
        worker.execute {
        try {
            require(VideoWire.header(input) == expectedId)
            while (!closed.get()) {
                val packet = VideoWire.read(input)
                queued.acquire()
                if (closed.get()) { queued.release(); break }
                handler.post { try {
                if (!closed.get()) when (packet) {
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
                } catch (_: Exception) { if (!closed.get()) onError(resources.getString(R.string.cast_error_decode)) }
                finally { queued.release() } }
            }
        } catch (_: Exception) { if (!closed.get()) onError(resources.getString(R.string.cast_error_video_receive)) }
        finally { runCatching { input.close() } }
    } }

    private fun drainContinuously() {
        if (closed.get()) return
        try { codec?.let { drain(it) } } catch (_: Exception) {
            if (target.get()?.isValid != true) { releaseCodec(); requestKey() }
            else onError(resources.getString(R.string.cast_error_decoder))
        }
        handler.postDelayed(::drainContinuously, 10)
    }

    private fun decode(frame: VideoPacket.Frame) {
        val surface = target.get()?.takeIf { it.isValid }
        if (surface !== codecSurface) releaseCodec()
        if (surface == null) { onRendered(frame.timeUs); return }
        val config = format ?: error("缺少视频参数")
        if (needsKey && !frame.key) { onRendered(frame.timeUs); return }
        if (codec == null) {
            val selected = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                .sortedByDescending { it.isHardwareAccelerated }
                .firstOrNull { info -> runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                        ?.isSizeSupported(config.width, config.height) == true
                }.getOrDefault(false) }
            val next = if (selected != null) MediaCodec.createByCodecName(selected.name)
                else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = next
            val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config.sps)); setByteBuffer("csd-1", ByteBuffer.wrap(config.pps))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, VideoWire.MAX_FRAME)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            next.configure(mediaFormat, surface, null, 0); next.start(); codecSurface = surface
            onCodec(next.name, next.codecInfo.isHardwareAccelerated)
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
        var latestIndex = -1
        var latestTime = 0L
        while (!closed.get()) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    // 参考 scrcpy 的最新画面策略：只渲染当前批次最新的已解码帧。
                    if (latestIndex >= 0) {
                        decoder.releaseOutputBuffer(latestIndex, false)
                        onRendered(latestTime)
                    }
                    latestIndex = index; latestTime = info.presentationTimeUs
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> break
            }
        }
        if (latestIndex >= 0) {
            decoder.releaseOutputBuffer(latestIndex, !closed.get())
            onRendered(latestTime)
            if (!reportedFrame) { reportedFrame = true; onFrame() }
        }
    }

    private fun releaseCodec() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }
        codec = null; codecSurface = null; needsKey = true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        target.set(null); runCatching { input.close() }; worker.shutdownNow()
        handler.post { releaseCodec(); decodeThread.quitSafely() }
    }
}
