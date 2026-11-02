package io.github.zyraxi21.nfc.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.lang.ref.WeakReference

internal data class RecordNearbyPeer(val endpoint: String, val token: ByteArray, val code: String, val host: Boolean)

/** SDK 回调由应用持有，普通页面仅注册弱引用；投屏服务可以接管同一连接，无需重新 accept。 */
internal object RecordNearbyLink {
    interface InvitationListener { fun onScreenSharingRequested() }
    private val main = Handler(Looper.getMainLooper())
    private var client: ConnectionsClient? = null
    private var recordsLifecycle = WeakReference<ConnectionLifecycleCallback>(null)
    private var recordsPayloads = WeakReference<PayloadCallback>(null)
    private var invitations = WeakReference<InvitationListener>(null)
    private val pending = mutableMapOf<String, RecordNearbyPeer>()
    private val earlyAuth = ArrayDeque<Payload>()
    var peer: RecordNearbyPeer? = null
        private set
    var handoffPending = false
        private set
    private var castOwner: Any? = null
    private var castPayloads: PayloadCallback? = null
    private var castDisconnected: ((String) -> Unit)? = null

    fun bind(context: Context, lifecycle: ConnectionLifecycleCallback, payloads: PayloadCallback, listener: InvitationListener) {
        client = Nearby.getConnectionsClient(context.applicationContext)
        recordsLifecycle = WeakReference(lifecycle); recordsPayloads = WeakReference(payloads)
        invitations = WeakReference(listener)
    }

    val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) { main.post {
            if (peer != null || castOwner != null || pending.keys.any { it != id }) {
                client?.rejectConnection(id); return@post
            }
            pending[id] = RecordNearbyPeer(id, info.rawAuthenticationToken.copyOf(), info.authenticationDigits, info.isIncomingConnection)
            recordsLifecycle.get()?.onConnectionInitiated(id, info)
        } }
        override fun onConnectionResult(id: String, result: ConnectionResolution) { main.post {
            val candidate = pending.remove(id) ?: return@post
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                rememberConnected(candidate)
            }
            recordsLifecycle.get()?.onConnectionResult(id, result)
        } }
        override fun onDisconnected(id: String) { main.post {
            if (peer?.endpoint == id) reset()
            else if (pending.remove(id) != null) recordsLifecycle.get()?.onDisconnected(id)
        } }
    }

    val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) { main.post {
            if (peer?.endpoint != id) return@post
            val message = payload.asBytes()?.let { runCatching { CastControl.decode(it) }.getOrNull() }
            castPayloads?.let { callback ->
                if (message != null || payload.type == Payload.Type.STREAM) callback.onPayloadReceived(id, payload)
                else recordsPayloads.get()?.onPayloadReceived(id, payload)
                return@post
            }
            if (message != null) {
                val current = peer ?: return@post
                if (current.token.isEmpty()) return@post
                val session = CastInvite.forExistingConnection(current.token).session
                if (message.id == session) when (message.type) {
                    "join" -> invitations.get()?.onScreenSharingRequested()
                    "auth" -> {
                        // 对方已进入，接收方服务尚未绑定；只暂存认证，不接受视频或方向消息。
                        if (earlyAuth.size < 2) earlyAuth.addLast(payload)
                    }
                }
                return@post
            }
            if (payload.type == Payload.Type.STREAM) {
                client?.cancelPayload(payload.id)
            } else recordsPayloads.get()?.onPayloadReceived(id, payload)
        } }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) { main.post {
            if (peer?.endpoint != id) return@post
            castPayloads?.onPayloadTransferUpdate(id, update)
            recordsPayloads.get()?.onPayloadTransferUpdate(id, update)
        } }
    }

    /** 记录当前连接身份；投屏入口接管时必须匹配同一快照。 */
    internal fun rememberConnected(peer: RecordNearbyPeer) { this.peer = peer; earlyAuth.clear() }

    fun reserveForCast() { if (peer != null && castOwner == null) handoffPending = true }
    fun cancelReservation() { handoffPending = false }

    fun takeOver(owner: Any, expected: RecordNearbyPeer, payloads: PayloadCallback, disconnected: (String) -> Unit): Boolean {
        if (peer !== expected || castOwner != null) return false
        handoffPending = false
        castOwner = owner; castPayloads = payloads; castDisconnected = disconnected
        while (earlyAuth.isNotEmpty()) payloads.onPayloadReceived(expected.endpoint, earlyAuth.removeFirst())
        return true
    }

    fun release(owner: Any) { if (castOwner === owner) reset() }

    fun reset() {
        val previous = peer
        val disconnected = castDisconnected
        peer = null; castOwner = null; castPayloads = null; castDisconnected = null
        handoffPending = false
        pending.clear(); earlyAuth.clear()
        if (previous != null) {
            disconnected?.invoke(previous.endpoint)
            recordsLifecycle.get()?.onDisconnected(previous.endpoint)
        }
    }
}
