package io.github.zyraxi21.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.util.NfcRecordKind
import io.github.zyraxi21.nfc.util.copyText
import io.github.zyraxi21.nfc.util.parseNfcReadResult
import io.github.zyraxi21.nfc.util.toDisplayHex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class NfcReadResultInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mixedMessagesKeepRecordOrderAndOriginalText() {
        val text = "  第一行\n应用: 这是正文，不是另一条记录\n末行  "
        val messages = listOf(
            NdefMessage(NdefRecord.createTextRecord("zh", text), NdefRecord.createUri("https://www.example.com/a?b=1")),
            NdefMessage(NdefRecord.createApplicationRecord("com.android.settings")),
        )
        val result = parseNfcReadResult(context, messages)
        assertEquals(listOf(NfcRecordKind.TEXT, NfcRecordKind.URI, NfcRecordKind.APPLICATION), result.records.map { it.kind })
        assertEquals(text, result.records[0].fields.single().value)
        assertEquals(text, result.records[0].copyText(context))
        assertEquals("https://www.example.com/a?b=1", result.records[1].fields.single().value)
        assertEquals("com.android.settings", result.records[2].copyText(context))
        assertEquals(messages.sumOf { it.byteArrayLength }, result.messageBytes)
        assertTrue(result.copyText(context).contains("1. 文本\n$text\n\n2. 网址 / URI"))
    }

    @Test
    fun utf16TextKeepsLanguageAndEncodingInDetails() {
        val payload = byteArrayOf(0x82.toByte()) + "zh".toByteArray() + "中文与 NFC".toByteArray(Charsets.UTF_16)
        val result = read(NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, byteArrayOf(), payload))
        assertEquals("中文与 NFC", result.fields.single().value)
        assertEquals(listOf("zh", "UTF-16"), result.details.map { it.value })
        assertNull(result.warningResId)
    }

    @Test
    fun malformedRecordsKeepTheirBytesAndDoNotHideLaterRecords() {
        val invalidText = NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, byteArrayOf(), byteArrayOf(0x3F, 1))
        val invalidUri = NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_URI, byteArrayOf(), byteArrayOf())
        val result = parseNfcReadResult(context, listOf(NdefMessage(invalidText, invalidUri, NdefRecord.createTextRecord("en", "still readable"))))
        assertEquals(3, result.records.size)
        result.records.take(2).forEach { assertEquals(R.string.reader_parse_warning, it.warningResId) }
        assertArrayEquals(invalidText.payload, result.records[0].payload)
        assertEquals("still readable", result.records[2].copyText(context))
    }

    @Test
    fun wifiCredentialsDecodeIntoFieldsWithoutSplittingUserValues() {
        val credential = tlv(0x1045, "Cafe\nSSID: literal".toByteArray()) +
            tlv(0x1027, "pa:ss\nword".toByteArray()) +
            tlv(0x1003, byteArrayOf(0, 0x20)) + tlv(0x100F, byteArrayOf(0, 8)) +
            tlv(0x1026, byteArrayOf(2)) + tlv(0x1020, ByteArray(6) { 0xFF.toByte() })
        val result = read(mime("application/vnd.wfa.wsc", tlv(0x104A, byteArrayOf(0x10)) + tlv(0x100E, credential)))
        assertEquals(NfcRecordKind.WIFI, result.kind)
        assertEquals(listOf("Cafe\nSSID: literal", "pa:ss\nword", "WPA2-PSK", "AES"), result.fields.map { it.value })
        assertEquals(listOf("1.0", "2", "FF:FF:FF:FF:FF:FF"), result.details.map { it.value })
        assertNull(result.warningResId)
    }

    @Test
    fun truncatedWifiKeepsDecodedFieldsAndReportsIncompleteData() {
        val credential = tlv(0x1045, "My Wi-Fi".toByteArray()) + byteArrayOf(0x10, 0x27, 0x01, 0x00, 0x41)
        val bytes = tlv(0x104A, byteArrayOf(0x10)) + tlv(0x100E, credential)
        val result = read(mime("application/vnd.wfa.wsc", bytes))
        assertEquals("My Wi-Fi", result.fields.single().value)
        assertEquals(R.string.reader_parse_warning, result.warningResId)
        assertArrayEquals(bytes, result.payload)
        var nested = tlv(0x1045, "deep".toByteArray())
        repeat(12) { nested = tlv(0x100E, nested) }
        assertEquals(R.string.reader_parse_warning, read(mime("application/vnd.wfa.wsc", nested)).warningResId)
    }

    @Test
    fun bluetoothDecodesStandardOobAndRejectsTheOldUnframedFormat() {
        val address = byteArrayOf(0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())
        val name = "NFC 设备".toByteArray()
        val legacy = read(mime("application/vnd.bluetooth.ep.oob", byteArrayOf(0, 0) + address + name))
        assertTrue(legacy.fields.isEmpty())
        assertEquals(R.string.reader_parse_warning, legacy.warningResId)
        val length = 10 + name.size
        val standard = read(mime("application/vnd.bluetooth.ep.oob", byteArrayOf(length.toByte(), 0) + address + byteArrayOf((name.size + 1).toByte(), 0x09) + name))
        assertEquals(listOf("AA:BB:CC:DD:EE:FF", "NFC 设备"), standard.fields.map { it.value })
        assertNull(standard.warningResId)
    }

    @Test
    fun wifiRejectsFlatAttributesAndMissingCredentialFields() {
        val flat = tlv(0x1045, "flat".toByteArray()) + tlv(0x1027, "12345678".toByteArray())
        val result = read(mime("application/vnd.wfa.wsc", flat))
        assertTrue(result.fields.isEmpty())
        assertEquals(R.string.reader_parse_warning, result.warningResId)
        assertArrayEquals(flat, result.payload)
        val incomplete = tlv(0x104A, byteArrayOf(0x10)) + tlv(0x100E, tlv(0x1045, "SSID".toByteArray()))
        assertEquals(R.string.reader_parse_warning, read(mime("application/vnd.wfa.wsc", incomplete)).warningResId)
    }

    @Test
    fun bluetoothChecksFullLengthAndEirBoundariesAndPrefersCompleteName() {
        val address = byteArrayOf(6, 5, 4, 3, 2, 1)
        fun oob(eir: ByteArray): ByteArray {
            val size = 8 + eir.size
            return byteArrayOf(size.toByte(), (size ushr 8).toByte()) + address + eir
        }
        val payload = oob(byteArrayOf(2, 8, 65, 4, 9, 65, 66, 67))
        val result = read(mime("application/vnd.bluetooth.ep.oob", payload))
        assertEquals(listOf("01:02:03:04:05:06", "ABC"), result.fields.map { it.value })
        assertNull(result.warningResId)
        listOf(payload + byteArrayOf(0), payload.dropLast(1).toByteArray(), oob(byteArrayOf(5, 9, 65))).forEach {
            val malformed = read(mime("application/vnd.bluetooth.ep.oob", it))
            assertEquals(R.string.reader_parse_warning, malformed.warningResId)
            assertArrayEquals(it, malformed.payload)
        }
    }

    @Test
    fun unknownRecordsKeepFullPayloadTypeAndIdForInspectionAndCopy() {
        val payload = ByteArray(1024) { it.toByte() }
        val record = NdefRecord(NdefRecord.TNF_EXTERNAL_TYPE, "example.com:custom".toByteArray(), byteArrayOf(1, 2), payload)
        val result = read(record)
        assertEquals(NfcRecordKind.DATA, result.kind)
        assertEquals("example.com:custom", result.type)
        assertArrayEquals(byteArrayOf(1, 2), result.id)
        assertArrayEquals(payload, result.payload)
        assertEquals(payload.toDisplayHex(), result.copyText(context))
        assertEquals(NfcRecordKind.EMPTY, read(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null)).kind)
    }

    private fun read(record: NdefRecord) = parseNfcReadResult(context, listOf(NdefMessage(record))).records.single()

    private fun mime(type: String, payload: ByteArray) =
        NdefRecord(NdefRecord.TNF_MIME_MEDIA, type.toByteArray(Charsets.US_ASCII), byteArrayOf(), payload)

    private fun tlv(type: Int, value: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + value.size).putShort(type.toShort()).putShort(value.size.toShort()).put(value).array()
}
