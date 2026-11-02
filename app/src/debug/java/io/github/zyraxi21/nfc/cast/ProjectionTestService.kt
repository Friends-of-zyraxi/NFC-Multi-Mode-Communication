package io.github.zyraxi21.nfc.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import io.github.zyraxi21.nfc.R

/** 调试构建的录屏测试宿主，不替代产品的连接或系统授权流程。 */
class ProjectionTestService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@ProjectionTestService }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    fun foreground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("projection-test", "录屏自动化测试", NotificationManager.IMPORTANCE_LOW))
        startService(Intent(this, ProjectionTestService::class.java))
        startForeground(7099, Notification.Builder(this, "projection-test")
            .setSmallIcon(R.drawable.ic_fluent_arrow_swap_24_regular).setContentTitle("录屏自动化测试").build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
