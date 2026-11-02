package io.github.zyraxi21.nfc.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 连接提示只属于当前可见页面；离开时取消，后台事件不在返回后补播。 */
internal class ConnectionMessages(
    private val scope: CoroutineScope,
    private val display: suspend (String) -> Unit,
) {
    private var active = false
    private var current: Job? = null

    fun resume() { active = true }

    fun pause() {
        active = false
        current?.cancel()
        current = null
    }

    fun show(message: String) {
        if (!active) return
        // 连接状态只显示最新一次变化，避免连接中、成功、断开依次排队。
        current?.cancel()
        current = scope.launch { display(message) }
    }
}
