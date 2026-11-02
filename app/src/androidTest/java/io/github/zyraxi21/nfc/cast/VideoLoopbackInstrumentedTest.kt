package io.github.zyraxi21.nfc.cast

import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 实际 AVC 编码结果经过生产帧协议和生产解码器，验证横竖画面与 Surface 重建。 */
@RunWith(AndroidJUnit4::class)
class VideoLoopbackInstrumentedTest {
    @Test fun avcStreamDecodesAndRecoversAfterRotationAndSurfaceReplacement() {
        val portrait = encode(272, 480)
        val landscape = encode(480, 272)
        val input = PipedInputStream(2 * 1024 * 1024)
        val output = PipedOutputStream(input)
        val thread = HandlerThread("video-test-images").apply { start() }
        val firstImage = CountDownLatch(1)
        val secondImage = CountDownLatch(1)
        val readers = listOf(ImageReader.newInstance(272, 480, PixelFormat.RGBA_8888, 3),
            ImageReader.newInstance(480, 272, PixelFormat.RGBA_8888, 3))
        readers.forEachIndexed { index, reader -> reader.setOnImageAvailableListener({ available ->
            available.acquireLatestImage()?.use { image ->
                if (image.width > 0 && image.height > 0) (if (index == 0) firstImage else secondImage).countDown()
            }
        }, Handler(thread.looper)) }
        val error = AtomicReference<String?>(null)
        val sizes = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val id = UUID.randomUUID().toString()
        val decoder = ScreenDecoder(input, id, { w, h -> sizes += w to h }, {}, { error.set(it) }, {})
        try {
            decoder.attach(readers[0].surface); decoder.start()
            VideoWire.header(output, id)
            // 只发送参数集和单个关键帧，随后保持连接但不写数据；解码器仍必须输出画面。
            portrait.take(2).forEach { VideoWire.write(output, it) }
            assertTrue("竖屏没有输出图像：${error.get()}", firstImage.await(8, TimeUnit.SECONDS))
            portrait.drop(2).forEach { VideoWire.write(output, it) }
            decoder.attach(null)
            decoder.attach(readers[1].surface)
            landscape.forEach { VideoWire.write(output, it) }
            assertTrue("旋转后没有输出图像：${error.get()}", secondImage.await(8, TimeUnit.SECONDS))
            assertNull(error.get())
            assertEquals(listOf(272 to 480, 480 to 272), sizes.toList())
        } finally {
            decoder.close(); output.close()
            readers.forEach { it.close() }; thread.quitSafely()
        }
    }

    private fun encode(width: Int, height: Int): List<VideoPacket> {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val packets = mutableListOf<VideoPacket>()
        try {
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 800_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var fed = 0
            var finished = false
            val deadline = SystemClock.elapsedRealtime() + 10_000
            val info = MediaCodec.BufferInfo()
            while (!finished && SystemClock.elapsedRealtime() < deadline) {
                if (fed <= 5) {
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        if (fed == 5) codec.queueInputBuffer(index, 0, 0, fed * 66_667L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            val image = requireNotNull(codec.getInputImage(index))
                            image.planes.forEachIndexed { planeIndex, plane ->
                                val w = if (planeIndex == 0) width else width / 2
                                val h = if (planeIndex == 0) height else height / 2
                                val value = if (planeIndex == 0) (60 + fed * 20).toByte() else 128.toByte()
                                for (y in 0 until h) for (x in 0 until w) plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, value)
                            }
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2, fed * 66_667L, 0)
                        }
                        fed++
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    fun csd(key: String) = requireNotNull(format.getByteBuffer(key)).duplicate().run { ByteArray(remaining()).also { get(it) } }
                    packets += VideoPacket.Config(width, height, csd("csd-0"), csd("csd-1"))
                } else if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size)
                        requireNotNull(codec.getOutputBuffer(index)).apply { position(info.offset); limit(info.offset + info.size); get(bytes) }
                        packets += VideoPacket.Frame(info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, info.presentationTimeUs, bytes)
                    }
                    finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("编码器没有完成输出", finished)
            assertTrue(packets.first() is VideoPacket.Config)
            assertTrue((packets[1] as VideoPacket.Frame).key)
            return packets
        } finally { runCatching { codec.stop() }; codec.release() }
    }
}
