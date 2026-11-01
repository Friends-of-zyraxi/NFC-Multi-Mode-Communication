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
                val complete = parseWifiFields(context, payload, fields)
                if (!complete || fields.isEmpty()) warning = R.string.reader_parse_warning
            }
            NfcRecordKind.BLUETOOTH -> {
                require(payload.size >= 8)
                fields += NfcRecordField(
                    R.string.reader_field_mac,
                    payload.copyOfRange(2, 8).reversedArray().toDisplayHex(":"),
                    monospaced = true,
                )
                // 本应用的写卡格式在地址后直接保存 UTF-8 名称（头部长度为 0）。
                if (payload[0] == 0.toByte() && payload[1] == 0.toByte()) {
                    val name = payload.copyOfRange(8, payload.size).decodeToString(throwOnInvalidSequence = true)
                    if (name.isNotEmpty()) fields += NfcRecordField(R.string.reader_field_device, name)
                } else {
                    // 标准 OOB 的设备名位于 EIR 数据结构中，不能把长度和类型字节当成名称。
                    val length = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                    require(length in 8..payload.size)
                    var offset = 8
                    while (offset < length) {
                        val size = payload[offset].toInt() and 0xFF
                        if (size == 0) break
                        require(offset + size < length)
                        val eirType = payload[offset + 1].toInt() and 0xFF
                        if (eirType == 0x08 || eirType == 0x09) {
                            fields += NfcRecordField(
                                R.string.reader_field_device,
                                payload.copyOfRange(offset + 2, offset + size + 1)
                                    .decodeToString(throwOnInvalidSequence = true),
                            )
                        }
                        offset += size + 1
                    }
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

/** WSC 的 Credential 可以嵌套；长度不完整时保留已经解出的字段并提示查看原始数据。 */
private fun parseWifiFields(
    context: Context,
    payload: ByteArray,
    fields: MutableList<NfcRecordField>,
    depth: Int = 0,
): Boolean {
    if (depth > 8) return false
    var index = 0
    var complete = true
    fun ByteArray.u16(): Int {
        require(size == 2)
        return ((this[0].toInt() and 0xFF) shl 8) or (this[1].toInt() and 0xFF)
    }
    while (index < payload.size) {
        if (index + 4 > payload.size) return false
        val type = payload.copyOfRange(index, index + 2).u16()
        val length = payload.copyOfRange(index + 2, index + 4).u16()
        index += 4
        if (length > payload.size - index) return false
        val data = payload.copyOfRange(index, index + length)
        index += length
        when (type) {
            0x1045 -> fields += NfcRecordField(R.string.reader_field_ssid, data.decodeToString(throwOnInvalidSequence = true))
            0x1027 -> fields += NfcRecordField(R.string.reader_field_password, data.decodeToString(throwOnInvalidSequence = true))
            0x1003 -> fields += NfcRecordField(R.string.reader_field_auth, authTypeName(context, data.u16()))
            0x100F -> fields += NfcRecordField(R.string.reader_field_encryption, encryptionTypeName(context, data.u16()))
            0x1020 -> {
                require(data.size == 6)
                fields += NfcRecordField(R.string.reader_field_mac, data.toDisplayHex(":"), monospaced = true)
            }
            0x1026 -> {
                require(data.size == 1)
                val networkType = when (val value = data[0].toInt() and 0xFF) {
                    0x00 -> context.getString(R.string.msg_unknown)
                    0x01 -> context.getString(R.string.msg_infrastructure)
                    0x02 -> context.getString(R.string.msg_independent)
                    else -> context.getString(R.string.format_reserved_type, value.toString(16))
                }
                fields += NfcRecordField(R.string.reader_field_network, networkType)
            }
            0x100E -> if (!parseWifiFields(context, data, fields, depth + 1)) complete = false
            // 其他 WSC 属性保留在原始载荷中，不混入网络配置的主要字段。
        }
    }
    return complete
}

private fun authTypeName(context: Context, value: Int): String = when (value) {
    0x0001 -> context.getString(R.string.auth_open_system)
    0x0002 -> context.getString(R.string.auth_wpa_psk)
    0x0004 -> context.getString(R.string.auth_shared_key)
    0x0008 -> context.getString(R.string.auth_wpa_eap)
    0x0010 -> context.getString(R.string.auth_wpa2_eap)
    0x0020 -> context.getString(R.string.auth_wpa2_psk)
    0x0022 -> context.getString(R.string.auth_wpa_wpa2_psk)
    0x0040 -> context.getString(R.string.auth_wpa3_sae)
    0x0080 -> context.getString(R.string.auth_wpa3_eap)
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
