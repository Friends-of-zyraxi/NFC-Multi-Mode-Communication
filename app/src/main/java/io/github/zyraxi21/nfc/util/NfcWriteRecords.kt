package io.github.zyraxi21.nfc.util

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import androidx.annotation.StringRes
import androidx.core.net.toUri
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.ui.theme.WifiAuth
import io.github.zyraxi21.nfc.ui.theme.WifiEncryption
import io.github.zyraxi21.nfc.ui.theme.WriteDataType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale

/** Type 4 Tag 文件上限为 0xFFFE B，其中 2 B 用于 NLEN。 */
const val MAX_EMULATED_NDEF_BYTES = 0xFFFE - 2

sealed class NfcWriteRecord(val type: WriteDataType) {
    data class Text(val text: String) : NfcWriteRecord(WriteDataType.TEXT)
    data class Link(val uri: String) : NfcWriteRecord(WriteDataType.URL)
    data class Wifi(
        val ssid: String,
        val password: String,
        val encryption: WifiEncryption,
        val auth: WifiAuth,
    ) : NfcWriteRecord(WriteDataType.WIFI)
    data class Bluetooth(val mac: String, val name: String) : NfcWriteRecord(WriteDataType.BLUETOOTH)
    data class Application(val packageName: String) : NfcWriteRecord(WriteDataType.APPLICATION)

    @StringRes
    fun validationError(): Int? = when (this) {
        is Text -> if (text.isBlank()) R.string.error_text_blank else null
        is Link -> when {
            uri.isBlank() -> R.string.error_url_blank
            uri.toUri().scheme.isNullOrBlank() -> R.string.error_url_invalid
            else -> null
        }
        is Wifi -> when {
            ssid.isBlank() -> R.string.error_wifi_ssid_blank
            ssid.toByteArray().size > 0xFFFF || password.toByteArray().size > 0xFFFF -> R.string.error_record_field_too_large
            else -> null
        }
        is Bluetooth -> if (!mac.matches(Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))) R.string.error_bt_mac_invalid else null
        is Application -> if (!isValidApplicationPackageName(packageName)) R.string.error_application_package else null
    }
}

class InvalidWriteRecordException(val recordIndex: Int, @param:StringRes val errorResId: Int) :
    IllegalArgumentException("Invalid NFC record at index $recordIndex")

/** 全部校验通过后再编码，保留列表顺序，生成一条包含多条记录的 NDEF 消息。 */
fun createNfcWriteMessage(records: List<NfcWriteRecord>): NdefMessage {
    require(records.isNotEmpty()) { "At least one NFC record is required" }
    records.forEachIndexed { index, record ->
        record.validationError()?.let { throw InvalidWriteRecordException(index, it) }
    }
    return NdefMessage(records.map { record ->
        when (record) {
            is NfcWriteRecord.Text -> NdefRecord.createTextRecord(Locale.getDefault().language, record.text)
            is NfcWriteRecord.Link -> NdefRecord.createUri(record.uri)
            is NfcWriteRecord.Wifi -> createWifiRecord(record)
            is NfcWriteRecord.Bluetooth -> createBluetoothRecord(record)
            is NfcWriteRecord.Application -> NdefRecord.createApplicationRecord(record.packageName.trim())
        }
    }.toTypedArray())
}

private fun createWifiRecord(record: NfcWriteRecord.Wifi): NdefRecord {
    val payload = ByteArrayOutputStream()
    fun writeTlv(type: Int, data: ByteArray) {
        payload.write(ByteBuffer.allocate(4).putShort(type.toShort()).putShort(data.size.toShort()).array())
        payload.write(data)
    }
    fun valueBytes(value: Int) = ByteBuffer.allocate(2).putShort(value.toShort()).array()
    writeTlv(0x1045, record.ssid.toByteArray(Charsets.UTF_8))
    writeTlv(0x1027, record.password.toByteArray(Charsets.UTF_8))
    writeTlv(0x100F, valueBytes(record.encryption.wscValue))
    writeTlv(0x1003, valueBytes(record.auth.wscValue))
    return NdefRecord.createMime("application/vnd.wfa.wsc", payload.toByteArray())
}

private fun createBluetoothRecord(record: NfcWriteRecord.Bluetooth): NdefRecord {
    val mac = record.mac.split(':').map { it.toInt(16).toByte() }.reversed().toByteArray()
    // 保留本应用现有的 OOB 格式，与已发布版本的读卡解析兼容。
    val payload = byteArrayOf(0, 0) + mac + record.name.toByteArray(Charsets.UTF_8)
    return NdefRecord.createMime("application/vnd.bluetooth.ep.oob", payload)
}
