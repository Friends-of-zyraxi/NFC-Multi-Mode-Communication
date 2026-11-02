package io.github.zyraxi21.nfc.cast

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.nearby.connection.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordNearbyLinkInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val peer = RecordNearbyPeer("connected-peer", ByteArray(32) { it.toByte() }, "1234", true)
    private val records = mutableListOf<Payload>()
    private val cast = mutableListOf<Payload>()
    private var offers = 0
    private var disconnected = 0
    private var connectedCallbacks = 0
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) = Unit
        override fun onConnectionResult(id: String, result: ConnectionResolution) { connectedCallbacks++ }
        override fun onDisconnected(id: String) { disconnected++ }
    }
    private fun callback(received: MutableList<Payload>) = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) { received += payload }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) = Unit
    }
    private val recordCallback = callback(records)
    private val castCallback = callback(cast)
    private val listener = object : RecordNearbyLink.InvitationListener {
        override fun onScreenSharingRequested() { offers++ }
    }

    @Before fun prepare() { instrumentation.runOnMainSync {
        RecordNearbyLink.reset()
        RecordNearbyLink.bind(instrumentation.targetContext, lifecycle, recordCallback, listener)
        RecordNearbyLink.rememberConnected(peer)
    } }
    @After fun cleanup() { instrumentation.runOnMainSync { RecordNearbyLink.reset() } }

    private fun receive(message: CastControl, endpoint: String = peer.endpoint) {
        RecordNearbyLink.payloads.onPayloadReceived(endpoint, Payload.fromBytes(message.encode()))
        instrumentation.waitForIdleSync()
    }

    @Test fun onlyCurrentConnectionsInviteIsShownAndEarlyAuthenticationSurvivesServiceBinding() {
        val invite = CastInvite.forExistingConnection(peer.token)
        receive(CastControl("join", CastInvite.create().session))
        receive(CastControl("join", invite.session), "different-peer")
        assertEquals(0, offers)
        receive(CastControl("join", invite.session))
        receive(CastControl("auth", invite.session, "early-auth"))
        assertEquals(1, offers)
        assertTrue(records.isEmpty())
        val owner = Any()
        instrumentation.runOnMainSync { assertTrue(RecordNearbyLink.takeOver(owner, peer, castCallback) {}) }
        assertEquals("early-auth", CastControl.decode(requireNotNull(cast.single().asBytes())).value)
        assertSame(peer, RecordNearbyLink.peer)
    }

    @Test fun handoffUsesTheSameEndpointAndKeepsOrdinaryNdefSeparateFromCastControl() {
        val owner = Any()
        instrumentation.runOnMainSync { assertTrue(RecordNearbyLink.takeOver(owner, peer, castCallback) {}) }
        val bytes = NdefMessage(arrayOf(NdefRecord.createTextRecord("zh", "尚未发送完成的记录"))).toByteArray()
        RecordNearbyLink.payloads.onPayloadReceived(peer.endpoint, Payload.fromBytes(bytes))
        receive(CastControl("verify", CastInvite.forExistingConnection(peer.token).session))
        assertArrayEquals(bytes, records.single().asBytes())
        assertEquals("verify", CastControl.decode(requireNotNull(cast.single().asBytes())).type)
        assertSame(peer, RecordNearbyLink.peer)
    }

    @Test fun staleOrCompetingOwnersCannotTakeOverAndReleaseInvalidatesTheConnection() {
        val owner = Any()
        var ended = 0
        instrumentation.runOnMainSync {
            assertFalse(RecordNearbyLink.takeOver(owner, peer.copy(endpoint = "stale"), castCallback) {})
            assertTrue(RecordNearbyLink.takeOver(owner, peer, castCallback) { ended++ })
            assertFalse(RecordNearbyLink.takeOver(Any(), peer, castCallback) {})
            RecordNearbyLink.release(Any())
            assertSame(peer, RecordNearbyLink.peer)
            RecordNearbyLink.release(owner)
        }
        assertNull(RecordNearbyLink.peer)
        assertEquals(1, ended)
        assertEquals(1, disconnected)
    }

    @Test fun lateSuccessfulConnectionCallbackCannotReviveAReleasedEndpoint() {
        instrumentation.runOnMainSync { RecordNearbyLink.reset() }
        RecordNearbyLink.lifecycle.onConnectionResult(peer.endpoint,
            ConnectionResolution(com.google.android.gms.common.api.Status.RESULT_SUCCESS))
        instrumentation.waitForIdleSync()
        assertNull(RecordNearbyLink.peer)
        assertEquals(0, connectedCallbacks)
    }
}
