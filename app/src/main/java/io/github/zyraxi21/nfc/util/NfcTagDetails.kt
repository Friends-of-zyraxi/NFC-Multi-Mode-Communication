package io.github.zyraxi21.nfc.util

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.*
import io.github.zyraxi21.nfc.R
import java.util.Locale

/** 读取发现参数及系统能力，不连接标签，也不发送认证或写入命令。 */
internal fun readNfcTagDetails(context: Context, tag: Tag): List<NfcRecordField> = buildList {
    fun field(label: Int, value: String, hex: Boolean = false) { add(NfcRecordField(label, value, hex)) }
    fun bytes(label: Int, value: ByteArray?) {
        value?.takeIf { it.isNotEmpty() }?.let { field(label, it.toDisplayHex(), true) }
    }
    val technologies = tag.techList.map { it.substringAfterLast('.') }
    val protocol = when {
        "IsoDep" in technologies && "NfcA" in technologies -> "ISO/IEC 14443-4 (Type A)"
        "IsoDep" in technologies && "NfcB" in technologies -> "ISO/IEC 14443-4 (Type B)"
        "NfcA" in technologies -> "ISO/IEC 14443-3A"
        "NfcB" in technologies -> "ISO/IEC 14443-3B"
        "NfcF" in technologies -> "JIS X 6319-4 / NFC-F"
        "NfcV" in technologies -> "ISO/IEC 15693 / NFC-V"
        else -> null
    }
    protocol?.let { field(R.string.reader_field_tag_type, it) }
    // 各技术独立处理；设备未提供某个参数时，仍保留其余信息。
    runCatching { NfcA.get(tag)?.let {
        // Android 返回的 ATQA 字节按低字节在前排列，转为常见的 16 位十六进制写法。
        it.atqa?.takeIf { value -> value.size == 2 }?.let { value ->
            field(R.string.reader_field_atqa, "0x" + value.reversedArray().toDisplayHex(""), true)
        }
        field(R.string.reader_field_sak, String.format(Locale.ROOT, "0x%02X", it.sak.toInt() and 0xFF), true)
    } }
    runCatching { MifareClassic.get(tag)?.let { classic ->
        val family = when (classic.type) {
            MifareClassic.TYPE_CLASSIC -> "MIFARE Classic"
            MifareClassic.TYPE_PLUS -> "MIFARE Plus"
            MifareClassic.TYPE_PRO -> "MIFARE Pro"
            else -> "MIFARE Classic-compatible"
        }
        field(R.string.reader_field_chip_family, family + " / " + context.getString(R.string.reader_exact_bytes, classic.size))
        field(R.string.reader_field_physical_memory, context.getString(R.string.reader_exact_bytes, classic.size))
        val sectors = (0 until classic.sectorCount).groupingBy { classic.getBlockCountInSector(it) }.eachCount()
        field(R.string.reader_field_memory_layout, sectors.entries.joinToString("；") {
            context.getString(R.string.reader_sector_layout, it.value, it.key, MifareClassic.BLOCK_SIZE)
        })
    } }
    runCatching { MifareUltralight.get(tag)?.let {
        val family = when (it.type) {
            MifareUltralight.TYPE_ULTRALIGHT -> "MIFARE Ultralight"
            MifareUltralight.TYPE_ULTRALIGHT_C -> "MIFARE Ultralight C"
            else -> "MIFARE Ultralight-compatible"
        }
        field(R.string.reader_field_chip_family, family)
    } }
    runCatching { Ndef.get(tag)?.let {
        val format = when (it.type) {
            Ndef.NFC_FORUM_TYPE_1 -> "NFC Forum Type 1"
            Ndef.NFC_FORUM_TYPE_2 -> "NFC Forum Type 2"
            Ndef.NFC_FORUM_TYPE_3 -> "NFC Forum Type 3"
            Ndef.NFC_FORUM_TYPE_4 -> "NFC Forum Type 4"
            Ndef.MIFARE_CLASSIC -> "NXP MIFARE Classic"
            else -> it.type
        }
        field(R.string.reader_field_ndef_format, format)
        field(R.string.reader_field_can_lock, context.getString(if (it.canMakeReadOnly()) R.string.reader_yes else R.string.reader_no))
    } }
    runCatching { IsoDep.get(tag)?.let {
        bytes(R.string.reader_field_historical_bytes, it.historicalBytes)
        bytes(R.string.reader_field_higher_response, it.hiLayerResponse)
    } }
    runCatching { NfcB.get(tag)?.let {
        bytes(R.string.reader_field_application_data, it.applicationData)
        bytes(R.string.reader_field_protocol_info, it.protocolInfo)
    } }
    runCatching { NfcF.get(tag)?.let {
        bytes(R.string.reader_field_system_code, it.systemCode)
        bytes(R.string.reader_field_manufacturer_data, it.manufacturer)
    } }
    runCatching { NfcV.get(tag)?.let {
        bytes(R.string.reader_field_dsfid, byteArrayOf(it.dsfId))
        bytes(R.string.reader_field_response_flags, byteArrayOf(it.responseFlags))
    } }
}
