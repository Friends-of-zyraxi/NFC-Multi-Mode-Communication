package io.github.zyraxi21.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.WifiAuth
import io.github.zyraxi21.nfc.ui.theme.WifiEncryption
import io.github.zyraxi21.nfc.util.HceDataStore
import io.github.zyraxi21.nfc.util.InvalidWriteRecordException
import io.github.zyraxi21.nfc.util.MAX_EMULATED_NDEF_BYTES
import io.github.zyraxi21.nfc.util.NfcRecordKind
import io.github.zyraxi21.nfc.util.NfcWriteRecord
import io.github.zyraxi21.nfc.util.createNfcWriteMessage
import io.github.zyraxi21.nfc.util.parseNfcReadResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class MultiRecordEncodingInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mixedTypesBecomeOneNdefMessageAndRoundTripInTheSameOrder() {
        val records = listOf(
            NfcWriteRecord.Text("  文本第一行\n第二行  "),
            NfcWriteRecord.Link("https://example.com/first"),
            NfcWriteRecord.Wifi("办公室 Wi-Fi", "secret:123", WifiEncryption.AES, WifiAuth.WPA2_PSK),
            NfcWriteRecord.Bluetooth("AA:BB:CC:DD:EE:FF", "NFC 设备"),
            NfcWriteRecord.Application("com.android.settings"),
            NfcWriteRecord.Text("第二条文本"),
        )
        val message = createNfcWriteMessage(records)
        val decoded = NdefMessage(message.toByteArray())
        assertEquals(6, decoded.records.size)
        assertEquals(message.byteArrayLength, decoded.toByteArray().size)
        assertArrayEquals(NdefRecord.RTD_TEXT, decoded.records[0].type)
        assertEquals("https://example.com/first", decoded.records[1].toUri().toString())
        assertEquals("application/vnd.wfa.wsc", decoded.records[2].toMimeType())
        assertEquals("application/vnd.bluetooth.ep.oob", decoded.records[3].toMimeType())
        assertArrayEquals("android.com:pkg".toByteArray(), decoded.records[4].type)
        val read = parseNfcReadResult(context, listOf(decoded))
        assertEquals(listOf(NfcRecordKind.TEXT, NfcRecordKind.URI, NfcRecordKind.WIFI,
            NfcRecordKind.BLUETOOTH, NfcRecordKind.APPLICATION, NfcRecordKind.TEXT), read.records.map { it.kind })
        assertEquals("  文本第一行\n第二行  ", read.records[0].fields.single().value)
        assertEquals(listOf("办公室 Wi-Fi", "WPA2-PSK", "AES", "secret:123"), read.records[2].fields.map { it.value })
        assertEquals(listOf("AA:BB:CC:DD:EE:FF", "NFC 设备"), read.records[3].fields.map { it.value })
        assertEquals("com.android.settings", read.records[4].fields.single().value)
        assertEquals("第二条文本", read.records[5].fields.single().value)
        assertTrue(read.records.all { it.warningResId == null })
    }

    @Test
    fun repeatedTypesAreNotDeduplicatedOrSorted() {
        val records = listOf(NfcWriteRecord.Link("https://example.com/b"), NfcWriteRecord.Text("same"),
            NfcWriteRecord.Link("https://example.com/a"), NfcWriteRecord.Text("same"))
        val message = NdefMessage(createNfcWriteMessage(records).toByteArray())
        assertEquals(4, message.records.size)
        assertEquals("https://example.com/b", message.records[0].toUri().toString())
        assertEquals("https://example.com/a", message.records[2].toUri().toString())
        assertArrayEquals(message.records[1].payload, message.records[3].payload)
    }

    @Test
    fun anInvalidRecordRejectsTheWholeBatchAndIdentifiesItsPosition() {
        val invalidRecords = listOf(
            NfcWriteRecord.Application("not a package"),
            NfcWriteRecord.Link("example.com/no-scheme"),
            NfcWriteRecord.Bluetooth("AA:BB:CC:DD:EE:ZZ", "device"),
            NfcWriteRecord.Wifi("ssid", "a".repeat(65536), WifiEncryption.AES, WifiAuth.WPA2_PSK),
        )
        invalidRecords.forEach { invalid ->
            try {
                createNfcWriteMessage(listOf(NfcWriteRecord.Text("valid"), invalid))
                fail("Invalid second record must reject the complete message")
            } catch (error: InvalidWriteRecordException) {
                assertEquals(1, error.recordIndex)
                assertNotNull(invalid.validationError())
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyBatchCannotBeEncoded() {
        createNfcWriteMessage(emptyList())
    }

    @Test
    fun multiRecordHcePersistsAndReturnsIdenticalBytesOnBothReadPaths() {
        val records = listOf(NfcWriteRecord.Text("长内容，用于验证分块传输。".repeat(60)),
            NfcWriteRecord.Link("https://example.com/multi"), NfcWriteRecord.Application("com.android.settings"))
        val expected = createNfcWriteMessage(records).toByteArray()
        assertTrue(expected.size > MyHostApduService.CHUNK_SIZE * 2)
        MyHostApduService.setEmulatedData(context, expected, "MULTI")
        try {
            val saved = requireNotNull(HceDataStore.load(context))
            assertArrayEquals(expected, saved.first)
            assertEquals("MULTI", saved.second)
            val service = createService()
            assertTrue(capacity(service) >= expected.size + 2)
            val custom = readChunks(expected.size) { offset ->
                service.processCommandApdu(byteArrayOf(0x80.toByte(), 0x03, (offset shr 8).toByte(), offset.toByte(), 0), null)
            }
            assertArrayEquals(expected, custom)
            assertArrayEquals(MyHostApduService.SW_OK,
                service.processCommandApdu(byteArrayOf(0, 0xA4.toByte(), 0, 0x0C, 2, 0xE1.toByte(), 4), null))
            val standard = readChunks(expected.size + 2) { offset ->
                service.processCommandApdu(byteArrayOf(0, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), MyHostApduService.CHUNK_SIZE.toByte()), null)
            }
            assertEquals(expected.size, ((standard[0].toInt() and 0xFF) shl 8) or (standard[1].toInt() and 0xFF))
            assertArrayEquals(expected, standard.copyOfRange(2, standard.size))
            assertEquals(3, NdefMessage(custom).records.size)
        } finally {
            MyHostApduService.setEmulatedData(context, null)
        }
    }

    @Test
    fun hceCapacityIncludesNlenAndRejectsOversizedMessagesBeforeReplacingData() {
        val expected = NdefMessage(NdefRecord(NdefRecord.TNF_UNKNOWN, byteArrayOf(), byteArrayOf(),
            ByteArray(MAX_EMULATED_NDEF_BYTES - 6))).toByteArray()
        assertEquals(MAX_EMULATED_NDEF_BYTES, expected.size)
        MyHostApduService.setEmulatedData(context, expected, "MULTI")
        try {
            assertEquals(expected.size + 2, capacity(createService()))
            try {
                MyHostApduService.setEmulatedData(context, ByteArray(MAX_EMULATED_NDEF_BYTES + 1), "MULTI")
                fail("Oversized data must be rejected")
            } catch (_: IllegalArgumentException) {
                assertArrayEquals(expected, MyHostApduService.emulatedData)
                assertArrayEquals(expected, requireNotNull(HceDataStore.load(context)).first)
            }
        } finally {
            MyHostApduService.setEmulatedData(context, null)
        }
    }

    private fun createService(): MyHostApduService {
        lateinit var service: MyHostApduService
        InstrumentationRegistry.getInstrumentation().runOnMainSync { service = MyHostApduService() }
        return service
    }

    private fun capacity(service: MyHostApduService): Int {
        assertArrayEquals(MyHostApduService.SW_OK,
            service.processCommandApdu(byteArrayOf(0, 0xA4.toByte(), 0, 0x0C, 2, 0xE1.toByte(), 3), null))
        val response = service.processCommandApdu(byteArrayOf(0, 0xB0.toByte(), 0, 0, 15), null)
        assertEquals(17, response.size)
        assertArrayEquals(MyHostApduService.SW_OK, response.takeLast(2).toByteArray())
        assertEquals(MyHostApduService.CHUNK_SIZE, ((response[3].toInt() and 0xFF) shl 8) or (response[4].toInt() and 0xFF))
        return ((response[11].toInt() and 0xFF) shl 8) or (response[12].toInt() and 0xFF)
    }

    private fun readChunks(total: Int, read: (Int) -> ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < total) {
            val response = read(bytes.size())
            assertTrue(response.size > 2)
            assertArrayEquals(MyHostApduService.SW_OK, response.takeLast(2).toByteArray())
            bytes.write(response, 0, response.size - 2)
        }
        assertEquals(total, bytes.size())
        return bytes.toByteArray()
    }
}
