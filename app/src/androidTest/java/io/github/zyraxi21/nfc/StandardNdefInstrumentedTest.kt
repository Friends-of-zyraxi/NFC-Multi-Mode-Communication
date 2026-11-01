package io.github.zyraxi21.nfc

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.WifiAuth
import io.github.zyraxi21.nfc.ui.theme.WifiEncryption
import io.github.zyraxi21.nfc.util.NfcWriteRecord
import io.github.zyraxi21.nfc.util.createNfcWriteMessage
import io.github.zyraxi21.nfc.util.parseNfcReadResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class StandardNdefInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun wifiMatchesWscConfigurationTokenBytes() {
        val record = encode(wifi())
        // 独立固定样本：Version 1.0；Credential 长度 47；Network Index、SSID、认证、加密、密钥、MAC。
        val expected = hex("104A000110 100E002F 1026000101 1045000454657374 " +
            "100300020020 100F00020008 102700083132333435363738 10200006FFFFFFFFFFFF")
        assertEquals("application/vnd.wfa.wsc", record.toMimeType())
        assertArrayEquals(expected, record.payload)
    }

    @Test
    fun openWifiHasNoPasswordAndUsesOpenAuthenticationAndNoEncryption() {
        val record = encode(wifi(password = "", encryption = WifiEncryption.NONE, auth = WifiAuth.OPEN))
        val attributes = credentialAttributes(record.payload)
        assertArrayEquals(byteArrayOf(0, 1), attributes.getValue(0x1003))
        assertArrayEquals(byteArrayOf(0, 1), attributes.getValue(0x100F))
        assertArrayEquals(byteArrayOf(), attributes.getValue(0x1027))
        assertNull(parseNfcReadResult(context, listOf(NdefMessage(record))).records.single().warningResId)
    }

    @Test
    fun wifiValidatesSsidByteCountPskAndSecurityCombinations() {
        listOf(wifi(ssid = "中".repeat(10) + "ab"), wifi(password = "F".repeat(64)),
            wifi(password = " ".repeat(8)), wifi(auth = WifiAuth.WPA_PSK, encryption = WifiEncryption.TKIP),
            wifi(encryption = WifiEncryption.AES_TKIP)).forEach { assertNull(it.validationError()) }
        listOf(wifi(ssid = "中".repeat(11)), wifi(password = "1234567"), wifi(password = "g".repeat(64)),
            wifi(password = "含中文的密码123"), wifi(password = "1234567\n"), wifi(encryption = WifiEncryption.NONE),
            wifi(encryption = WifiEncryption.WEP), wifi(auth = WifiAuth.OPEN),
            wifi(password = "", auth = WifiAuth.OPEN, encryption = WifiEncryption.AES)).forEach {
            assertNotNull(it.validationError())
        }
        val key = "0123456789abcdef".repeat(4)
        assertArrayEquals(key.toByteArray(), credentialAttributes(encode(wifi(password = key)).payload).getValue(0x1027))
    }

    @Test
    fun bluetoothMatchesBrEdrOobBytesAndOmitsEmptyOptionalName() {
        val record = encode(NfcWriteRecord.Bluetooth("AA:BB:CC:DD:EE:FF", "NFC"))
        assertEquals("application/vnd.bluetooth.ep.oob", record.toMimeType())
        assertArrayEquals(hex("0D00 FFEEDDCCBBAA 04094E4643"), record.payload)
        assertArrayEquals(hex("0800 FFEEDDCCBBAA"), encode(NfcWriteRecord.Bluetooth("AA:BB:CC:DD:EE:FF", "")).payload)
    }

    @Test
    fun bluetoothNameLimitsUseUtf8BytesAndUnsignedEirLength() {
        val name = "中".repeat(82) + "ab"
        val valid = NfcWriteRecord.Bluetooth("01:23:45:67:89:AB", name)
        assertNull(valid.validationError())
        val record = encode(valid)
        assertEquals(258, record.payload.size)
        assertArrayEquals(byteArrayOf(2, 1), record.payload.take(2).toByteArray())
        assertEquals(249, record.payload[8].toInt() and 0xFF)
        assertEquals(9, record.payload[9].toInt())
        val decoded = parseNfcReadResult(context, listOf(NdefMessage(record))).records.single()
        assertEquals(name, decoded.fields.last().value)
        assertNull(decoded.warningResId)
        assertEquals(R.string.error_bt_name_too_long, valid.copy(name = name + "x").validationError())
    }

    @Test
    fun websiteRemainsStandardUriAndTheAppDoesNotClaimSystemWebsiteIntents() {
        val url = "https://developer.android.com/index.html"
        val record = encode(NfcWriteRecord.Link(url))
        assertEquals(NdefRecord.TNF_WELL_KNOWN, record.tnf)
        assertArrayEquals(NdefRecord.RTD_URI, record.type)
        assertEquals(url, record.toUri().toString())
        listOf(NfcAdapter.ACTION_NDEF_DISCOVERED, Intent.ACTION_VIEW).forEach { action ->
            val intent = Intent(action, Uri.parse(url)).addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
            assertTrue(context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty())
        }
    }

    private fun encode(record: NfcWriteRecord) = createNfcWriteMessage(listOf(record)).records.single()

    private fun wifi(ssid: String = "Test", password: String = "12345678",
        encryption: WifiEncryption = WifiEncryption.AES, auth: WifiAuth = WifiAuth.WPA2_PSK) =
        NfcWriteRecord.Wifi(ssid, password, encryption, auth)

    private fun hex(value: String) = value.filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun credentialAttributes(payload: ByteArray): Map<Int, ByteArray> {
        fun attributes(bytes: ByteArray): Map<Int, ByteArray> {
            val buffer = ByteBuffer.wrap(bytes)
            return buildMap {
                while (buffer.hasRemaining()) {
                    val type = buffer.short.toInt() and 0xFFFF
                    val value = ByteArray(buffer.short.toInt() and 0xFFFF)
                    buffer.get(value)
                    put(type, value)
                }
            }
        }
        return attributes(attributes(payload).getValue(0x100E))
    }
}
