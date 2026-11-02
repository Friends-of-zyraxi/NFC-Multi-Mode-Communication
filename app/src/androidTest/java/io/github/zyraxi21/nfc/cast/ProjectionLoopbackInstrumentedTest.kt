package io.github.zyraxi21.nfc.cast

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProjectionLoopbackInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realMediaProjectionEncodesDecodesAndResizesTheSameCaptureSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        } }
        val service = CompletableFuture<ProjectionTestService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service.complete((binder as ProjectionTestService.LocalBinder).service)
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val input = PipedInputStream(2 * 1024 * 1024)
        val output = PipedOutputStream(input)
        val readers = Collections.synchronizedList(mutableListOf<ImageReader>())
        val error = AtomicReference<String?>(null)
        val portrait = CountDownLatch(1)
        val landscape = CountDownLatch(1)
        val thread = HandlerThread("projection-test-images").apply { start() }
        val id = UUID.randomUUID().toString()
        var encoder: ScreenEncoder? = null
        val acknowledgedFrames = java.util.concurrent.atomic.AtomicInteger(0)
        val continuousFrames = CountDownLatch(15)
        val decoder = ScreenDecoder(input, id, { _, _ -> }, {}, { error.set(it) }, { encoder?.requestKeyFrame() },
            onRendered = { pts ->
                // 延迟反馈模拟慢链路，验证两帧窗口仍持续推进，不会等下一包才能解码。
                Handler(thread.looper).postDelayed({
                    encoder?.acknowledge(pts)
                    acknowledgedFrames.incrementAndGet(); continuousFrames.countDown()
                }, 250)
            })
        var bound = false
        try {
            compose.setContent { Box(Modifier.fillMaxSize().background(Color(0xFF0F6CBD))) }
            shell("wm user-rotation lock 0")
            // 仅在专用测试设备上预授予测试录屏；产品仍走系统对话框，不设置此 AppOp。
            shell("appops set ${context.packageName} PROJECT_MEDIA allow")
            val result = CompletableFuture<ActivityResult>()
            instrumentation.runOnMainSync {
                val launcher = compose.activity.activityResultRegistry.register("projection-loopback", ActivityResultContracts.StartActivityForResult()) { result.complete(it) }
                launcher.launch(context.getSystemService(MediaProjectionManager::class.java)
                    .createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()))
                bound = context.bindService(Intent(context, ProjectionTestService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
            val capture = result.get(15, TimeUnit.SECONDS)
            assertEquals(Activity.RESULT_OK, capture.resultCode)
            val host = service.get(5, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { host.foreground() }
            val projection = requireNotNull(context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(capture.resultCode, requireNotNull(capture.data)))
            encoder = ScreenEncoder(projection, output, id, context.resources.displayMetrics.densityDpi,
                onSize = { width, height ->
                    val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                    readers += reader
                    reader.setOnImageAvailableListener({ available -> available.acquireLatestImage()?.use {
                        (if (width > height) landscape else portrait).countDown()
                    } }, Handler(thread.looper))
                    decoder.attach(reader.surface)
                }, onError = { error.set(it) })
            decoder.start()
            val metrics = compose.activity.windowManager.maximumWindowMetrics.bounds
            encoder.start(metrics.width(), metrics.height())
            assertTrue("录屏竖屏未显示：${error.get()}", portrait.await(12, TimeUnit.SECONDS))
            assertTrue("慢速反馈下视频未持续推进：${error.get()}", continuousFrames.await(15, TimeUnit.SECONDS))
            shell("wm user-rotation lock 1")
            assertTrue("同一录屏会话旋转后未显示：${error.get()}", landscape.await(12, TimeUnit.SECONDS))
            assertNull(error.get())
        } finally {
            encoder?.close(); decoder.close(); runCatching { output.close() }
            if (bound) context.unbindService(connection)
            context.stopService(Intent(context, ProjectionTestService::class.java))
            readers.toList().forEach { it.close() }; thread.quitSafely()
            shell("appops set ${context.packageName} PROJECT_MEDIA default")
            shell("wm user-rotation lock 0")
        }
    }
}
