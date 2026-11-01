package io.github.zyraxi21.nfc.util

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import androidx.annotation.StringRes
import io.github.zyraxi21.nfc.R
import java.nio.ByteBuffer

/** 保留记录边界和原始载荷，界面不再从格式化字符串中反推字段。 */
data class NfcReadResult(
    val records: List<NfcReadRecord>,
    val messageBytes: Int,
    val tagId: String? = null,
    val technologies: List<String> = emptyList(),
    val isEmulated: Boolean = false,
    val capacity: Int? = null,
    val isWritable: Boolean? = null,
    @param:StringRes val noticeResId: Int? = null,
)

enum class NfcRecordKind(@param:StringRes val titleResId: Int) {
    TEXT(R.string.data_type_text),
    URI(R.string.reader_type_uri),
    WIFI(R.string.data_type_wifi),
    BLUETOOTH(R.string.data_type_bluetooth),
    APPLICATION(R.string.data_type_application),
    DATA(R.string.reader_type_data),
    EMPTY(R.string.reader_type_empty),
}

data class NfcRecordField(
    @param:StringRes val labelResId: Int,
    val value: String,
    val monospaced: Boolean = false,
)

data class NfcReadRecord(
    val kind: NfcRecordKind,
    val fields: List<NfcRecordField>,
    val payload: ByteArray,
    val type: String,
    val tnf: Short,
    val id: ByteArray,
    val details: List<NfcRecordField> = emptyList(),
    @param:StringRes val warningResId: Int? = null,
) {
    /** 单值记录直接复制原文；配置记录保留字段名，未知记录复制完整字节。 */
    fun copyText(context: Context): String = when {
        fields.isEmpty() -> payload.toDisplayHex()
        fields.size == 1 && kind in listOf(NfcRecordKind.TEXT, NfcRecordKind.URI, NfcRecordKind.APPLICATION) -> fields.single().value
        else -> fields.joinToString("\n") { "${context.getString(it.labelResId)}：${it.value}" }
    }
}

fun NfcReadResult.copyText(context: Context): String = records.mapIndexed { index, record ->
    "${index + 1}. ${context.getString(record.kind.titleResId)}\n${record.copyText(context)}"
}.joinToString("\n\n")

fun parseNfcReadResult(
    context: Context,
    messages: List<NdefMessage>,
    tag: Tag? = null,
    isEmulated: Boolean = false,
    @StringRes noticeResId: Int? = null,
): NfcReadResult {
    val ndef = tag?.let(Ndef::get)
    return NfcReadResult(
        records = messages.flatMap { message -> message.records.map { parseRecord(context, it) } },
        messageBytes = messages.sumOf { it.byteArrayLength },
        tagId = tag?.id?.takeIf { it.isNotEmpty() }?.toDisplayHex(),
        technologies = tag?.techList?.map { it.substringAfterLast('.') }.orEmpty(),
        isEmulated = isEmulated,
        capacity = if (isEmulated) null else ndef?.maxSize,
        isWritable = if (isEmulated) null else ndef?.isWritable,
        noticeResId = noticeResId,
    )
}

private fun parseRecord(context: Context, record: NdefRecord): NfcReadRecord {
    val payload = record.payload
    val type = record.type.toString(Charsets.UTF_8)
    val mimeType = record.toMimeType()
    val kind = when {
        record.tnf == NdefRecord.TNF_EMPTY -> NfcRecordKind.EMPTY
        record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT) -> NfcRecordKind.TEXT
        record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI) -> NfcRecordKind.URI
        record.tnf == NdefRecord.TNF_ABSOLUTE_URI -> NfcRecordKind.URI
        record.tnf == NdefRecord.TNF_EXTERNAL_TYPE && type == "android.com:pkg" -> NfcRecordKind.APPLICATION
        mimeType == "application/vnd.wfa.wsc" -> NfcRecordKind.WIFI
        mimeType == "application/vnd.bluetooth.ep.oob" -> NfcRecordKind.BLUETOOTH
        else -> NfcRecordKind.DATA
    }
    val fields = mutableListOf<NfcRecordField>()
    val details = mutableListOf<NfcRecordField>()
    var warning: Int? = null
    try {
        when (kind) {
            NfcRecordKind.TEXT -> {
                require(payload.isNotEmpty())
                val status = payload[0].toInt() and 0xFF
                val languageLength = status and 0x3F
                require(status and 0x40 == 0 && languageLength + 1 <= payload.size)
                val encoding = if (status and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
                val text = encoding.newDecoder().decode(
                    ByteBuffer.wrap(payload, languageLength + 1, payload.size - languageLength - 1)
                ).toString()
                fields += NfcRecordField(R.string.reader_field_text, text)
                details += NfcRecordField(R.string.reader_field_language, String(payload, 1, languageLength, Charsets.US_ASCII))
                details += NfcRecordField(R.string.reader_field_encoding, encoding.name())
            }
            NfcRecordKind.URI -> {
                val uri = requireNotNull(record.toUri())
                fields += NfcRecordField(R.string.reader_field_uri, uri.toString())
            }
            NfcRecordKind.APPLICATION -> {
                val packageName = payload.decodeToString(throwOnInvalidSequence = true)
                fields += NfcRecordField(R.string.reader_field_package, packageName, monospaced = true)
            }
            NfcRecordKind.WIFI -> {
                val complete = parseWifiFields(context, payload, fields, details)
                if (!complete || fields.isEmpty()) warning = R.string.reader_parse_warning
            }
            NfcRecordKind.BLUETOOTH -> {
                require(payload.size >= 8)
                val length = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                require(length == payload.size)
                fields += NfcRecordField(
                    R.string.reader_field_mac,
                    payload.copyOfRange(2, 8).reversedArray().toDisplayHex(":"),
                    monospaced = true,
                )
                var offset = 8
                var shortName: String? = null
                var completeName: String? = null
                while (offset < length) {
                    val size = payload[offset].toInt() and 0xFF
                    if (size == 0) {
                        require(payload.copyOfRange(offset, length).all { it == 0.toByte() })
                        break
                    }
                    require(offset + size < length)
                    val eirType = payload[offset + 1].toInt() and 0xFF
                    if (eirType == 0x08 || eirType == 0x09) {
                        require(size - 1 <= 248)
                        val name = payload.copyOfRange(offset + 2, offset + size + 1)
                            .decodeToString(throwOnInvalidSequence = true)
                        if (eirType == 0x09) {
                            require(completeName == null)
                            completeName = name
                        } else {
                            require(shortName == null)
                            shortName = name
                        }
                    }
                    offset += size + 1
                }
                (completeName ?: shortName)?.takeIf { it.isNotEmpty() }?.let {
                    fields += NfcRecordField(R.string.reader_field_device, it)
                }
            }
            NfcRecordKind.DATA, NfcRecordKind.EMPTY -> Unit
        }
    } catch (_: Exception) {
        // 单条损坏记录不影响后面的记录；载荷仍可查看和完整复制。
        warning = R.string.reader_parse_warning
    }
    return NfcReadRecord(kind, fields, payload, type, record.tnf, record.id, details, warning)
}

/** 仅解析标准 Configuration Token 的 Credential，损坏数据保留已解出的字段与原始载荷。 */
private fun parseWifiFields(
    context: Context,
    payload: ByteArray,
    fields: MutableList<NfcRecordField>,
    details: MutableList<NfcRecordField>,
): Boolean {
    var versionFound = false
    var credentialFound = false
    var credentialsComplete = true
    val complete = parseWscAttributes(payload) { type, data ->
        when (type) {
            0x104A -> {
                require(!versionFound && data.contentEquals(byteArrayOf(0x10)))
                versionFound = true
                details += NfcRecordField(R.string.reader_field_wsc_version, "1.0")
            }
            0x100E -> {
                credentialFound = true
                if (!parseWifiCredential(context, data, fields, details)) credentialsComplete = false
            }
        }
    }
    return complete && versionFound && credentialFound && credentialsComplete
}

private fun parseWifiCredential(
    context: Context,
    payload: ByteArray,
    fields: MutableList<NfcRecordField>,
    details: MutableList<NfcRecordField>,
): Boolean {
    val found = mutableSetOf<Int>()
    val complete = parseWscAttributes(payload) { type, data ->
        if (type in setOf(0x1026, 0x1045, 0x1003, 0x100F, 0x1027, 0x1020)) require(found.add(type))
        when (type) {
            0x1045 -> {
                require(data.size in 1..32)
                fields += NfcRecordField(R.string.reader_field_ssid, data.decodeToString(throwOnInvalidSequence = true))
            }
            0x1027 -> {
                require(data.size <= 64)
                fields += NfcRecordField(R.string.reader_field_password, data.decodeToString(throwOnInvalidSequence = true))
            }
            0x1003 -> fields += NfcRecordField(R.string.reader_field_auth, authTypeName(context, data.wscU16()))
            0x100F -> fields += NfcRecordField(R.string.reader_field_encryption, encryptionTypeName(context, data.wscU16()))
            0x1020 -> {
                require(data.size == 6)
                details += NfcRecordField(R.string.reader_field_mac, data.toDisplayHex(":"), monospaced = true)
            }
            0x1026 -> {
                require(data.size == 1)
                details += NfcRecordField(R.string.reader_field_network_index, (data[0].toInt() and 0xFF).toString())
            }
            0x100E -> error("Credential cannot contain another Credential")
        }
    }
    return complete && found.containsAll(setOf(0x1026, 0x1045, 0x1003, 0x100F, 0x1027, 0x1020))
}

private fun ByteArray.wscU16(): Int {
    require(size == 2)
    return ((this[0].toInt() and 0xFF) shl 8) or (this[1].toInt() and 0xFF)
}

private inline fun parseWscAttributes(payload: ByteArray, onAttribute: (Int, ByteArray) -> Unit): Boolean {
    var index = 0
    while (index < payload.size) {
        if (index + 4 > payload.size) return false
        val type = payload.copyOfRange(index, index + 2).wscU16()
        val length = payload.copyOfRange(index + 2, index + 4).wscU16()
        index += 4
        if (length > payload.size - index) return false
        val data = payload.copyOfRange(index, index + length)
        index += length
        onAttribute(type, data)
    }
    return true
}

private fun authTypeName(context: Context, value: Int): String = when (value) {
    0x0001 -> context.getString(R.string.auth_open_system)
    0x0002 -> context.getString(R.string.auth_wpa_psk)
    0x0004 -> context.getString(R.string.auth_shared_key)
    0x0008 -> context.getString(R.string.auth_wpa_eap)
    0x0010 -> context.getString(R.string.auth_wpa2_eap)
    0x0020 -> context.getString(R.string.auth_wpa2_psk)
    0x0022 -> context.getString(R.string.auth_wpa_wpa2_psk)
    else -> context.getString(R.string.format_unknown_auth, value)
}

private fun encryptionTypeName(context: Context, value: Int): String = when (value) {
    0x0001 -> context.getString(R.string.enc_none)
    0x0002 -> context.getString(R.string.enc_wep)
    0x0004 -> context.getString(R.string.enc_tkip)
    0x0008 -> context.getString(R.string.enc_aes)
    0x000C -> context.getString(R.string.enc_aes_tkip)
    else -> context.getString(R.string.format_unknown_encryption, value)
}

fun ByteArray.toDisplayHex(separator: String = " "): String = buildString {
    val digits = "0123456789ABCDEF"
    this@toDisplayHex.forEachIndexed { index, byte ->
        if (index > 0) append(separator)
        val value = byte.toInt() and 0xFF
        append(digits[value ushr 4])
        append(digits[value and 0x0F])
    }
}
