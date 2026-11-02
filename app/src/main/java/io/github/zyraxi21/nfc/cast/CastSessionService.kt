package io.github.zyraxi21.nfc.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import io.github.zyraxi21.nfc.MyHostApduService
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.util.HceDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class CastStage { IDLE, ADVERTISING, DISCOVERING, CONNECTING, AUTHENTICATING, VERIFYING, READY, OFFER_OUT, OFFER_IN, PREPARING, SENDING, RECEIVING }

data class CastUiState(
    val stage: CastStage = CastStage.IDLE,
    val message: String? = null,
    val localSender: Boolean? = null,
    val width: Int = 0,
    val height: Int = 0,
    val hasFrame: Boolean = false,
    val expiresAt: Long = 0,
    val connectionCode: String = "",
    val codeConfirmed: Boolean = false,
    val videoBitrate: Int = 0,
    val videoFrameRate: Int = 0,
    val linkQuality: Int = BandwidthInfo.Quality.UNKNOWN,
    val confirmedBitrate: Int = 0,
    val feedbackMs: Long = 0,
    val pendingFrames: Int = 0,
    val codecName: String = "",
    val hardwareCodec: Boolean = false,
)

/** 独立占用 Nearby 的投屏会话。所有连接及角色状态在主线程串行更新，媒体在专用线程运行。 */
class CastSessionService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@CastSessionService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(CastUiState())
    private var linkQuality = BandwidthInfo.Quality.UNKNOWN
    val state = mutableState.asStateFlow()
    private val client by lazy { Nearby.getConnectionsClient(this) }
    private var generation = 0
    private var invite: CastInvite? = null
    private var host = false
    private var endpoint: String? = null
    private var authToken: ByteArray? = null
    private var peerProven = false
    private var transportConnected = false
    private var proposal: String? = null
    private var encoder: ScreenEncoder? = null
    private var decoder: ScreenDecoder? = null
    private var targetSurface: Surface? = null
    private var videoPayload: Long? = null
    private var streamReadPipe: ParcelFileDescriptor? = null
    private var timeout: Job? = null
    private var roleTimeout: Job? = null
    private var foreground = false
    private var directConnection = false
    private var localVerified = false
    private var remoteVerified = false

    /** 接管普通端对端的已连接端点，保留原策略、端点和 SDK 回调，不做断开或重新发现。 */
    fun useExistingConnection() {
        if (state.value.stage != CastStage.IDLE) return
        val peer = RecordNearbyLink.peer
        if (peer == null || peer.token.isEmpty() || peer.code.isBlank()) {
            RecordNearbyLink.cancelReservation()
            notice(getString(R.string.cast_error_p2p_expired))
            return
        }
        generation++
        val ticket = generation
        directConnection = true; host = peer.host; endpoint = peer.endpoint
        authToken = peer.token; invite = CastInvite.forExistingConnection(peer.token)
        transportConnected = true; ownsNearby = true
        mutableState.value = CastUiState(CastStage.AUTHENTICATING, connectionCode = peer.code)
        if (!RecordNearbyLink.takeOver(this, peer, payloadCallback(ticket)) { id ->
                main.post { if (generation == ticket && endpoint == id) end(getString(R.string.cast_peer_disconnected)) }
            }) { end(getString(R.string.cast_error_takeover)); return }
        RecordNearbyLink.watchBandwidth(this) { quality -> if (generation == ticket) updateLinkQuality(quality) }
        try { promote(false) } catch (_: Exception) { end(getString(R.string.cast_error_start_service)); return }
        client.stopAdvertising(); client.stopDiscovery()
        val current = requireNotNull(invite)
        send(CastControl("join", current.session))
        send(CastControl("auth", current.session, current.proof(peer.token, host)))
        timeout = scope.launch {
            delay(120_000)
            if (ticket == generation && state.value.stage in listOf(CastStage.AUTHENTICATING, CastStage.VERIFYING)) {
                end(getString(R.string.cast_error_verify_timeout))
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) end()
        return START_NOT_STICKY // 进程终止后不得自动恢复录屏或复用系统授权。
    }

    fun begin(asHost: Boolean, received: CastInvite? = null) {
        if (state.value.stage != CastStage.IDLE) return
        val next = if (asHost) CastInvite.create() else received
        if (next == null || !next.valid()) { notice(getString(R.string.cast_error_invite_regenerate)); return }
        generation++
        val ticket = generation
        invite = next; host = asHost; ownsNearby = true
        try { promote(false) } catch (_: Exception) { end(getString(R.string.cast_error_start_service)); return }
        mutableState.value = CastUiState(if (host) CastStage.ADVERTISING else CastStage.DISCOVERING, expiresAt = next.expiresAt)
        val payloads = payloadCallback(ticket)
        val lifecycle = lifecycleCallback(ticket, payloads)
        if (host) {
            client.startAdvertising(next.session, CastInvite.SERVICE, lifecycle,
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                .addOnSuccessListener {
                    if (ticket == generation) MyHostApduService.setEmulatedData(this, next.ndef(packageName).toByteArray(), INVITE_TYPE)
                }.addOnFailureListener { if (ticket == generation) end(getString(R.string.cast_error_advertising)) }
        } else {
            client.startDiscovery(CastInvite.SERVICE, object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) { main.post {
                    if (ticket != generation || endpoint != null || info.endpointName != next.session) return@post
                    endpoint = id
                    mutableState.value = state.value.copy(stage = CastStage.CONNECTING)
                    client.requestConnection(next.session, id, lifecycle)
                        .addOnFailureListener { if (ticket == generation) end(getString(R.string.cast_error_connect)) }
                } }
                override fun onEndpointLost(id: String) = Unit
            }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                .addOnFailureListener { if (ticket == generation) end(getString(R.string.cast_error_discovery)) }
        }
        timeout = scope.launch {
            delay((next.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
            if (ticket == generation && !peerProven) end(getString(R.string.cast_error_invite_expired))
        }
    }

    private fun lifecycleCallback(ticket: Int, payloads: PayloadCallback) = object : ConnectionLifecycleCallback() {
        override fun onBandwidthChanged(id: String, info: BandwidthInfo) { main.post {
            if (ticket == generation && endpoint == id) updateLinkQuality(info.quality)
        } }
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) { main.post {
            if (ticket != generation) return@post
            val current = invite
            if (current == null || !current.valid() || (endpoint != null && endpoint != id) ||
                info.endpointName != current.session || info.rawAuthenticationToken.isEmpty()) {
                client.rejectConnection(id); return@post
            }
            endpoint = id; authToken = info.rawAuthenticationToken
            mutableState.value = state.value.copy(stage = CastStage.AUTHENTICATING)
            // 仅接受临时传输通道；验证 HMAC 前不允许角色协商或视频数据。
            client.acceptConnection(id, payloads)
                .addOnFailureListener { if (ticket == generation) end(getString(R.string.cast_error_accept_connection)) }
            roleTimeout?.cancel()
            roleTimeout = scope.launch { delay(12_000); if (ticket == generation && !peerProven) end(getString(R.string.cast_error_auth_timeout)) }
        } }
        override fun onConnectionResult(id: String, resolution: ConnectionResolution) { main.post {
            if (ticket != generation || endpoint != id) return@post
            if (resolution.status.statusCode != ConnectionsStatusCodes.STATUS_OK) { end(getString(R.string.cast_error_connection_result)); return@post }
            transportConnected = true
            val current = requireNotNull(invite)
            send(CastControl("auth", current.session, current.proof(requireNotNull(authToken), host)))
            authenticated()
        } }
        override fun onDisconnected(id: String) { main.post {
            if (ticket == generation && endpoint == id) end(getString(R.string.cast_peer_disconnected))
        } }
    }

    private fun authenticated() {
        if (!peerProven || !transportConnected || state.value.stage != CastStage.AUTHENTICATING) return
        if (directConnection) {
            mutableState.value = state.value.copy(stage = CastStage.VERIFYING)
            finishVerification()
            return
        }
        timeout?.cancel(); roleTimeout?.cancel(); clearInvite()
        client.stopAdvertising(); client.stopDiscovery()
        mutableState.value = CastUiState(CastStage.READY, linkQuality = linkQuality)
    }

    private fun payloadCallback(ticket: Int) = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) { main.post {
            if (ticket != generation || endpoint != id) return@post
            try {
                when (payload.type) {
                    Payload.Type.BYTES -> control(CastControl.decode(requireNotNull(payload.asBytes())))
                    Payload.Type.STREAM -> {
                        if (!peerProven || state.value.localSender != false || state.value.stage != CastStage.PREPARING || decoder != null) {
                            client.cancelPayload(payload.id); return@post
                        }
                        val streamId = requireNotNull(proposal)
                        val input = requireNotNull(payload.asStream()?.asInputStream())
                        videoPayload = payload.id
                        mutableState.value = state.value.copy(stage = CastStage.RECEIVING)
                        decoder = ScreenDecoder(resources, input, streamId,
                            onSize = { w, h -> main.post { if (proposal == streamId) mutableState.value = state.value.copy(width = w, height = h) } },
                            onFrame = { main.post { if (proposal == streamId) mutableState.value = state.value.copy(hasFrame = true) } },
                            onError = { message -> main.post { if (proposal == streamId) stopVideo(message) } },
                            requestKey = { main.post { if (proposal == streamId) send(CastControl("key", streamId)) } },
                            onRendered = { pts -> main.post { if (proposal == streamId) send(CastControl("ack", streamId, pts.toString())) } },
                            onCodec = { name, hardware -> main.post {
                                if (proposal == streamId) mutableState.value = state.value.copy(codecName = name, hardwareCodec = hardware)
                            } },
                        ).also { it.attach(targetSurface); it.start() }
                    }
                    else -> client.cancelPayload(payload.id)
                }
            } catch (_: Exception) { end(getString(R.string.cast_error_protocol)) }
        } }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) { main.post {
            if (ticket == generation && endpoint == id && update.payloadId == videoPayload &&
                update.status in listOf(PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED)) {
                stopVideo(getString(R.string.cast_error_transfer))
            }
        } }
    }

    private fun control(message: CastControl) {
        if (message.type == "join") { require(directConnection && message.id == invite?.session); return }
        if (message.type == "auth") {
            val current = requireNotNull(invite)
            require(message.id == current.session && current.verifies(requireNotNull(authToken), !host, message.value))
            if (!peerProven) { peerProven = true; authenticated() }
            return
        }
        require(peerProven && transportConnected)
        when (message.type) {
            "verify" -> if (directConnection && message.id == invite?.session) {
                remoteVerified = true; finishVerification()
            }
            "role" -> {
                // 同时提出方向时由邀请方的请求优先，避免两个发送端都开始录屏。
                if (state.value.stage == CastStage.OFFER_OUT && host) return
                if (state.value.stage !in listOf(CastStage.READY, CastStage.OFFER_OUT)) return
                roleTimeout?.cancel(); proposal = message.id
                mutableState.value = CastUiState(CastStage.OFFER_IN, localSender = (message.value == "host") == host, linkQuality = linkQuality)
                armRoleTimeout(message.id)
            }
            "accept" -> if (proposal == message.id && state.value.stage == CastStage.OFFER_OUT) {
                mutableState.value = state.value.copy(stage = CastStage.PREPARING)
                armRoleTimeout(message.id)
            }
            "reject" -> if (proposal == message.id) stopVideo(getString(R.string.cast_peer_rejected), notifyPeer = false)
            "stop" -> if (proposal == message.id) stopVideo(getString(R.string.cast_sharing_ended), notifyPeer = false)
            "key" -> if (proposal == message.id) encoder?.requestKeyFrame()
            "ack" -> if (proposal == message.id) message.value.toLongOrNull()?.takeIf { it >= 0 }?.let { encoder?.acknowledge(it) }
        }
    }

    fun confirmConnectionCode() {
        if (!directConnection || state.value.stage != CastStage.VERIFYING || localVerified) return
        localVerified = true
        mutableState.value = state.value.copy(codeConfirmed = true)
        send(CastControl("verify", requireNotNull(invite).session))
        finishVerification()
    }

    private fun finishVerification() {
        if (localVerified && remoteVerified && state.value.stage == CastStage.VERIFYING) {
            timeout?.cancel()
            mutableState.value = CastUiState(CastStage.READY, linkQuality = linkQuality)
        }
    }

    fun requestDirection(localSender: Boolean) {
        if (state.value.stage != CastStage.READY) return
        val id = UUID.randomUUID().toString(); proposal = id
        mutableState.value = CastUiState(CastStage.OFFER_OUT, localSender = localSender, linkQuality = linkQuality)
        val senderHost = if (localSender) host else !host
        send(CastControl("role", id, if (senderHost) "host" else "guest"))
        armRoleTimeout(id)
    }

    fun acceptDirection() {
        if (state.value.stage != CastStage.OFFER_IN) return
        val id = requireNotNull(proposal)
        mutableState.value = state.value.copy(stage = CastStage.PREPARING)
        send(CastControl("accept", id)); armRoleTimeout(id)
    }

    private fun armRoleTimeout(id: String) {
        roleTimeout?.cancel()
        roleTimeout = scope.launch { delay(120_000); if (proposal == id && encoder == null && decoder == null) stopVideo(getString(R.string.cast_error_request_timeout)) }
    }

    /** 只由可见 Activity 在系统授权 RESULT_OK 后调用，授权 Intent 从不持久化或复用。 */
    fun startCapture(resultCode: Int, data: Intent, width: Int, height: Int) {
        if (state.value.stage != CastStage.PREPARING || state.value.localSender != true || encoder != null) return
        val id = requireNotNull(proposal)
        var readPipe: ParcelFileDescriptor? = null
        var writePipe: ParcelFileDescriptor? = null
        var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
        var projection: android.media.projection.MediaProjection? = null
        try {
            promote(true)
            projection = requireNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data))
            val pipe = ParcelFileDescriptor.createPipe(); readPipe = pipe[0]; writePipe = pipe[1]
            streamReadPipe = readPipe
            output = ParcelFileDescriptor.AutoCloseOutputStream(writePipe)
            val payload = Payload.fromStream(readPipe)
            videoPayload = payload.id
            encoder = ScreenEncoder(resources, projection, output, id, resources.displayMetrics.densityDpi,
                onSize = { w, h -> main.post { if (proposal == id) mutableState.value = state.value.copy(width = w, height = h) } },
                onError = { message -> main.post { if (proposal == id) stopVideo(message) } },
                onQuality = { bitrate, fps -> main.post { if (proposal == id) mutableState.value = state.value.copy(videoBitrate = bitrate, videoFrameRate = fps) } },
                onTransport = { stats -> main.post {
                    if (proposal == id) mutableState.value = state.value.copy(confirmedBitrate = stats.confirmedBitrate,
                        feedbackMs = stats.feedbackMs, pendingFrames = stats.pendingFrames)
                } },
                onCodec = { name, hardware -> main.post {
                    if (proposal == id) mutableState.value = state.value.copy(codecName = name, hardwareCodec = hardware)
                } },
            )
            mutableState.value = state.value.copy(stage = CastStage.SENDING)
            roleTimeout?.cancel()
            client.sendPayload(requireNotNull(endpoint), payload).addOnFailureListener {
                if (proposal == id) stopVideo(getString(R.string.cast_error_send_stream))
            }
            encoder?.start(width, height)
            // Payload 持有读取端直到取消；写入端由 ScreenEncoder 关闭。
        } catch (_: Exception) {
            if (encoder == null) runCatching { projection?.stop() }
            runCatching { readPipe?.close() }; runCatching { output?.close() }; runCatching { writePipe?.close() }
            stopVideo(getString(R.string.cast_error_start_capture))
        }
    }

    fun attachSurface(surface: Surface?) { targetSurface = surface; decoder?.attach(surface) }

    fun stopVideo(message: String? = null, notifyPeer: Boolean = true) {
        val id = proposal
        proposal = null; roleTimeout?.cancel()
        if (notifyPeer && id != null && transportConnected) send(CastControl("stop", id))
        val payload = videoPayload; videoPayload = null
        encoder?.close(); encoder = null; decoder?.close(); decoder = null
        if (payload != null) client.cancelPayload(payload)
        runCatching { streamReadPipe?.close() }; streamReadPipe = null
        if (peerProven && transportConnected) {
            mutableState.value = if (directConnection && (!localVerified || !remoteVerified)) state.value.copy(message = message)
                else CastUiState(CastStage.READY, message, linkQuality = linkQuality)
            runCatching { promote(false) }
        }
    }

    fun notice(message: String) { mutableState.value = state.value.copy(message = message) }

    private fun updateLinkQuality(quality: Int) {
        linkQuality = quality
        mutableState.value = state.value.copy(linkQuality = quality)
    }

    private fun send(message: CastControl) {
        val id = endpoint ?: return
        val ticket = generation
        client.sendPayload(id, Payload.fromBytes(message.encode())).addOnFailureListener {
            if (ticket == generation) end(getString(R.string.cast_error_connection_lost))
        }
    }

    fun end(message: String? = null) {
        generation++; timeout?.cancel(); roleTimeout?.cancel()
        if (directConnection) RecordNearbyLink.release(this)
        directConnection = false; localVerified = false; remoteVerified = false
        proposal = null; videoPayload = null
        encoder?.close(); encoder = null; decoder?.close(); decoder = null
        runCatching { streamReadPipe?.close() }; streamReadPipe = null
        if (ownsNearby) {
            client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        }
        clearInvite()
        invite = null; endpoint = null; authToken = null; peerProven = false; transportConnected = false
        ownsNearby = false
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        linkQuality = BandwidthInfo.Quality.UNKNOWN
        mutableState.value = CastUiState(message = message)
        stopSelf()
    }

    private fun clearInvite() {
        if (HceDataStore.load(this)?.second == INVITE_TYPE) MyHostApduService.setEmulatedData(this, null)
    }

    private fun promote(projecting: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.cast_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, CastActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, CastSessionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_fluent_arrow_swap_24_regular)
            .setContentTitle(if (projecting) getString(R.string.cast_sending_title) else getString(R.string.cast_notification_connection))
            .setContentText(getString(R.string.cast_notification_description))
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, getString(R.string.cast_notification_stop), stop).build()).build()
        if (!foreground) startService(Intent(this, CastSessionService::class.java))
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (projecting) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        startForeground(NOTIFICATION, notification, type)
        foreground = true
    }

    override fun onTaskRemoved(rootIntent: Intent?) { end(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { end(); scope.cancel(); super.onDestroy() }

    companion object {
        const val INVITE_TYPE = "SCREEN_INVITE"
        private const val CHANNEL = "screen-sharing"
        private const val NOTIFICATION = 7001
        private const val STOP = "io.github.zyraxi21.nfc.cast.STOP"
        @Volatile var ownsNearby = false
            private set
    }
}
