package io.github.zyraxi21.nfc.cast

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** NFC 只交付短期邀请；连接建立后凭 Nearby 的原始认证令牌证明持有邀请。 */
data class CastInvite(val session: String, val secret: String, val expiresAt: Long) {
    fun encode(): ByteArray = JSONObject().put("v", 1).put("session", session)
        .put("secret", secret).put("expires", expiresAt).toString().toByteArray(Charsets.UTF_8)

    fun valid(now: Long = System.currentTimeMillis()): Boolean =
        validId(session) && runCatching { Base64.getUrlDecoder().decode(secret).size == 32 }.getOrDefault(false) &&
            expiresAt > now && expiresAt <= now + LIFETIME_MS + 30_000

    fun proof(token: ByteArray, host: Boolean): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getUrlDecoder().decode(secret), "HmacSHA256"))
        mac.update("NFC-SCREEN/1/$session/${if (host) "host" else "guest"}/".toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(token))
    }

    fun verifies(token: ByteArray, peerHost: Boolean, proof: String): Boolean =
        MessageDigest.isEqual(proof(token, peerHost).toByteArray(), proof.toByteArray())

    companion object {
        const val MIME = "application/vnd.io.github.zyraxi21.nfc.screen"
        const val SERVICE = "io.github.zyraxi21.nfc.SCREEN.v1"
        const val LIFETIME_MS = 180_000L
        fun create(now: Long = System.currentTimeMillis()) = CastInvite(
            UUID.randomUUID().toString(), Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }), now + LIFETIME_MS)

        /** 现有连接两端使用相同令牌派生会话；身份另外由用户核对 Nearby 验证码确认。 */
        internal fun forExistingConnection(token: ByteArray, now: Long = System.currentTimeMillis()): CastInvite {
            require(token.isNotEmpty())
            val domain = "NFC-SCREEN/existing-connection/v1/".toByteArray()
            val secret = MessageDigest.getInstance("SHA-256").digest(domain + token)
            return CastInvite(UUID.nameUUIDFromBytes(domain + token).toString(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(secret), now + LIFETIME_MS)
        }

        fun decode(bytes: ByteArray, now: Long = System.currentTimeMillis()): CastInvite {
            require(bytes.size in 1..1024)
            val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
            require(json.getInt("v") == 1)
            return CastInvite(json.getString("session"), json.getString("secret"), json.getLong("expires"))
                .also { require(it.valid(now)) { "邀请已过期或格式无效" } }
        }
    }
}

internal fun validId(value: String): Boolean = runCatching {
    UUID.fromString(value).toString() == value
}.getOrDefault(false)

/** 控制消息只走 BYTES；视频的尺寸、参数集和帧全部放在同一条 STREAM 内，避免跨类型乱序。 */
internal data class CastControl(val type: String, val id: String = "", val value: String = "") {
    fun encode(): ByteArray = JSONObject().put("v", 1).put("type", type)
        .put("id", id).put("value", value).toString().toByteArray()
    companion object {
        fun decode(bytes: ByteArray): CastControl {
            require(bytes.size in 1..2048)
            val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
            require(json.getInt("v") == 1)
            val type = json.getString("type")
            require(type in setOf("join", "auth", "verify", "role", "accept", "reject", "stop", "key", "ack"))
            val id = json.getString("id")
            require(validId(id))
            val value = json.getString("value")
            require(value.length <= 128)
            if (type == "role") require(value in setOf("host", "guest"))
            return CastControl(type, id, value)
        }
    }
}

internal sealed interface VideoPacket {
    data class Config(val width: Int, val height: Int, val sps: ByteArray, val pps: ByteArray) : VideoPacket
    data class Frame(val key: Boolean, val timeUs: Long, val bytes: ByteArray) : VideoPacket
}

internal object VideoWire {
    const val MAX_FRAME = 8 * 1024 * 1024
    private const val MAGIC = 0x4E465343 // NFSC
    fun header(output: OutputStream, id: String) = DataOutputStream(output).apply {
        require(validId(id)); writeInt(MAGIC); writeInt(1); write(id.toByteArray(Charsets.US_ASCII)); flush()
    }
    fun header(input: InputStream): String = DataInputStream(input).run {
        require(readInt() == MAGIC && readInt() == 1)
        ByteArray(36).also { readFully(it) }.toString(Charsets.US_ASCII).also { require(validId(it)) }
    }
    fun write(output: OutputStream, packet: VideoPacket) = DataOutputStream(output).run {
        when (packet) {
            is VideoPacket.Config -> {
                validate(packet); writeByte(1); writeInt(packet.width); writeInt(packet.height)
                writeInt(packet.sps.size); write(packet.sps); writeInt(packet.pps.size); write(packet.pps)
            }
            is VideoPacket.Frame -> {
                require(packet.bytes.size in 1..MAX_FRAME && packet.timeUs >= 0)
                writeByte(2); writeBoolean(packet.key); writeLong(packet.timeUs)
                writeInt(packet.bytes.size); write(packet.bytes)
            }
        }
        flush()
    }
    fun read(input: InputStream): VideoPacket = DataInputStream(input).run {
        when (readUnsignedByte()) {
            1 -> VideoPacket.Config(readInt(), readInt(), blob(65_536), blob(65_536)).also { validate(it) }
            2 -> {
                val key = readBoolean(); val time = readLong(); require(time >= 0)
                VideoPacket.Frame(key, time, blob(MAX_FRAME))
            }
            else -> error("未知视频包")
        }
    }
    private fun DataInputStream.blob(max: Int): ByteArray {
        val size = readInt(); require(size in 1..max)
        return ByteArray(size).also { readFully(it) }
    }
    private fun validate(config: VideoPacket.Config) {
        require(config.width in 1..8192 && config.height in 1..8192 && config.width.toLong() * config.height <= 33_554_432)
        require(config.sps.size in 1..65_536 && config.pps.size in 1..65_536)
    }
}
