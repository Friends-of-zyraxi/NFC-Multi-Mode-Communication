package io.github.zyraxi21.nfc.cast

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import io.github.zyraxi21.nfc.MyHostApduService
import java.io.ByteArrayOutputStream

internal fun CastInvite.ndef(packageName: String) = NdefMessage(arrayOf(
    NdefRecord.createMime(CastInvite.MIME, encode()), NdefRecord.createApplicationRecord(packageName)))

internal fun inviteFromMessages(messages: List<NdefMessage>): CastInvite? = messages
    .flatMap { it.records.toList() }.firstOrNull { it.toMimeType() == CastInvite.MIME }
    ?.let { CastInvite.decode(it.payload) }

/** 主界面仍只展示记录；此读取入口仅在用户打开“碰一碰加入”后使用。 */
internal fun readCastInvite(tag: Tag): CastInvite {
    val privateMessage = IsoDep.get(tag)?.let { iso ->
        runCatching {
            iso.use {
                it.connect(); it.timeout = 1500
                val select = byteArrayOf(0, 0xA4.toByte(), 4, 0, 5, 0xF0.toByte(), 0x12, 0x34, 0x56, 0x78)
                fun ByteArray.body(): ByteArray {
                    require(size >= 2 && this[size - 2] == 0x90.toByte() && last() == 0.toByte())
                    return copyOf(size - 2)
                }
                it.transceive(select).body()
                val result = ByteArrayOutputStream()
                while (result.size() <= 65532) {
                    val offset = result.size()
                    val block = it.transceive(byteArrayOf(0x80.toByte(), 3,
                        (offset shr 8).toByte(), offset.toByte(), 0)).body()
                    result.write(block)
                    if (block.size < MyHostApduService.CHUNK_SIZE) break
                }
                require(result.size() <= 65532)
                NdefMessage(result.toByteArray())
            }
        }.getOrNull()
    }
    val message = privateMessage ?: Ndef.get(tag)?.use { it.connect(); it.ndefMessage }
    return requireNotNull(message?.let { inviteFromMessages(listOf(it)) }) { "未找到有效的屏幕共享邀请" }
}
