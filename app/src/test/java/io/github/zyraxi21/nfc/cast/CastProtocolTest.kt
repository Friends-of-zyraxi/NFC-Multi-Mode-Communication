package io.github.zyraxi21.nfc.cast

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class CastProtocolTest {
    private val now = 1_800_000_000_000L

    @Test fun existingConnectionDerivesTheSameSessionWithoutNfcAndBindsItToThisConnection() {
        val token = ByteArray(32) { it.toByte() }
        val first = CastInvite.forExistingConnection(token, now)
        val second = CastInvite.forExistingConnection(token, now + 10_000)
        assertEquals(first.session, second.session)
        assertEquals(first.secret, second.secret)
        assertTrue(second.verifies(token, true, first.proof(token, true)))
        assertNotEquals(first.session, CastInvite.forExistingConnection(token + 1.toByte(), now).session)
        assertEquals(CastControl("join", first.session), CastControl.decode(CastControl("join", first.session).encode()))
        assertEquals(CastControl("verify", first.session), CastControl.decode(CastControl("verify", first.session).encode()))
    }

    @Test fun inviteRoundTripHasIndependentRandomSecretsAndExpires() {
        val first = CastInvite.create(now)
        val second = CastInvite.create(now)
        assertNotEquals(first.session, second.session)
        assertNotEquals(first.secret, second.secret)
        assertEquals(first, CastInvite.decode(first.encode(), now))
        assertTrue(first.valid(first.expiresAt - 1))
        assertFalse(first.valid(first.expiresAt))
        assertThrows(IllegalArgumentException::class.java) { CastInvite.decode(first.encode(), first.expiresAt) }
    }

    @Test fun unknownVersionsMalformedSecretsAndDistantExpiryAreRejected() {
        val valid = CastInvite.create(now)
        listOf(
            JSONObject(String(valid.encode())).put("v", 2),
            JSONObject(String(valid.encode())).put("session", "garbage"),
            JSONObject(String(valid.encode())).put("secret", "short"),
            JSONObject(String(valid.encode())).put("expires", now + 86_400_000),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { CastInvite.decode(invalid.toString().toByteArray(), now) }
        }
        assertThrows(IllegalArgumentException::class.java) { CastInvite.decode(ByteArray(1025), now) }
    }

    @Test fun authenticationCannotBeReflectedOrReplayedOnAnotherNearbyConnection() {
        val invite = CastInvite.create(now)
        val token = ByteArray(32) { it.toByte() }
        val hostProof = invite.proof(token, true)
        val guestProof = invite.proof(token, false)
        assertTrue(invite.verifies(token, true, hostProof))
        assertTrue(invite.verifies(token, false, guestProof))
        assertFalse(invite.verifies(token, false, hostProof))
        assertFalse(invite.verifies(token + 1.toByte(), true, hostProof))
        assertFalse(CastInvite.create(now).verifies(token, true, hostProof))
        assertFalse(invite.verifies(token, true, hostProof.dropLast(1)))
    }

    @Test fun controlMessagesRejectUnknownOperationsAndInvalidDirection() {
        val id = CastInvite.create(now).session
        val message = CastControl("role", id, "host")
        assertEquals(message, CastControl.decode(message.encode()))
        listOf(CastControl("launch", id), CastControl("role", id, "device"), CastControl("stop", "bad"))
            .forEach { assertThrows(IllegalArgumentException::class.java) { CastControl.decode(it.encode()) } }
    }

    @Test fun streamCarriesRotationConfigurationsAndFramesInOneOrderedChannel() {
        val id = CastInvite.create(now).session
        val output = ByteArrayOutputStream()
        VideoWire.header(output, id)
        VideoWire.write(output, VideoPacket.Config(720, 1280, byteArrayOf(1, 2), byteArrayOf(3)))
        VideoWire.write(output, VideoPacket.Frame(true, 1000, byteArrayOf(4, 5)))
        VideoWire.write(output, VideoPacket.Config(1280, 720, byteArrayOf(6), byteArrayOf(7)))
        VideoWire.write(output, VideoPacket.Frame(true, 2000, byteArrayOf(8)))
        val input = ByteArrayInputStream(output.toByteArray())
        assertEquals(id, VideoWire.header(input))
        assertEquals(720, (VideoWire.read(input) as VideoPacket.Config).width)
        assertArrayEquals(byteArrayOf(4, 5), (VideoWire.read(input) as VideoPacket.Frame).bytes)
        assertEquals(1280, (VideoWire.read(input) as VideoPacket.Config).width)
        assertEquals(2000L, (VideoWire.read(input) as VideoPacket.Frame).timeUs)
        assertEquals(0, input.available())
    }

    @Test fun streamRejectsOversizedAndTruncatedFramesBeforeAllocatingPayload() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).apply { writeByte(2); writeBoolean(true); writeLong(0); writeInt(Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { VideoWire.read(ByteArrayInputStream(output.toByteArray())) }
        output.reset()
        VideoWire.write(output, VideoPacket.Frame(false, 10, byteArrayOf(1, 2, 3)))
        assertThrows(java.io.EOFException::class.java) { VideoWire.read(ByteArrayInputStream(output.toByteArray().dropLast(1).toByteArray())) }
        assertThrows(IllegalArgumentException::class.java) {
            VideoWire.write(ByteArrayOutputStream(), VideoPacket.Config(-1, 1280, byteArrayOf(1), byteArrayOf(2)))
        }
    }
}
