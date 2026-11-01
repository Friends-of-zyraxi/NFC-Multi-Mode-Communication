package io.github.zyraxi21.nfc

import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.ui.theme.WifiAuth
import io.github.zyraxi21.nfc.ui.theme.WifiEncryption
import io.github.zyraxi21.nfc.util.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedRecordsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nearbyCarriesAllFiveTypesAndMultipleRecordsAsTheSameNdefBytes() {
        val records = listOf(NfcWriteRecord.Text("a|b\nTEXT:literal"), NfcWriteRecord.Link("https://example.org/a?b=1"),
            NfcWriteRecord.Wifi("net|name\nline", "key|pass123", WifiEncryption.AES, WifiAuth.WPA2_PSK),
            NfcWriteRecord.Bluetooth("AA:BB:CC:DD:EE:FF", "device|name"),
            NfcWriteRecord.Application("com.android.settings"), NfcWriteRecord.Text("last"))
        val bytes = createNearbyPayload(records)
        assertArrayEquals(createNfcWriteMessage(records).toByteArray(), bytes)
        val result = parseNearbyPayload(context, bytes)
        assertEquals(6, result.records.size)
        assertEquals("a|b\nTEXT:literal", result.records[0].fields.single().value)
        assertEquals(listOf("net|name\nline", "WPA2-PSK", "AES", "key|pass123"), result.records[2].fields.map { it.value })
        assertEquals(listOf(RecordAction.Web("https://example.org/a?b=1")), result.records[1].actions)
        assertEquals(listOf(RecordAction.Wifi("net|name\nline", "key|pass123", 0x20, 8)), result.records[2].actions)
        assertEquals(listOf(RecordAction.Bluetooth("AA:BB:CC:DD:EE:FF")), result.records[3].actions)
        assertEquals(listOf(RecordAction.Application("com.android.settings")), result.records[4].actions)
        assertTrue(result.records.all { it.warningResId == null })
    }

    @Test
    fun malformedNearbyPayloadsAndUnsafeUrisNeverProduceActions() {
        listOf(byteArrayOf(), "WIFI:old|format".toByteArray(), byteArrayOf(0xD1.toByte(), 1, 0x7F)).forEach { bytes ->
            val result = parseNearbyPayload(context, bytes)
            assertEquals(R.string.p2p_invalid_payload, result.records.single().warningResId)
            assertArrayEquals(bytes, result.records.single().payload)
            assertTrue(result.records.single().actions.isEmpty())
        }
        val invalid = listOf(NdefRecord.createUri("javascript:alert(1)"), NdefRecord.createUri("file:///sdcard/file"),
            NdefRecord.createApplicationRecord("invalid package"),
            NdefRecord.createMime("application/vnd.bluetooth.ep.oob", ByteArray(8)))
        assertTrue(parseNfcReadResult(context, listOf(NdefMessage(invalid.toTypedArray()))).records.all { it.actions.isEmpty() })
    }

    @Test
    fun wifiAndWebActionsUsePublicSystemConfirmationIntents() {
        val action = RecordAction.Wifi("Test", "12345678", 0x20, 8)
        val intent = wifiRecordIntent(action)
        assertEquals(Settings.ACTION_WIFI_ADD_NETWORKS, intent.action)
        val networks = intent.getParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, WifiNetworkSuggestion::class.java)
        assertEquals(1, requireNotNull(networks).size)
        assertEquals("Test", networks.single().ssid)
        val open = wifiRecordIntent(RecordAction.Wifi("Public", "", 1, 1))
            .getParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, WifiNetworkSuggestion::class.java)
        assertEquals("Public", requireNotNull(open).single().ssid)
        val web = webRecordIntent(RecordAction.Web("https://example.org/"))
        assertEquals(Intent.ACTION_VIEW, web.action)
        assertEquals("https://example.org/", web.data.toString())
        assertTrue(web.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(RecordAction.Wifi("Test", "a".repeat(64), 0x20, 8).needsManualSetup)
        assertTrue(RecordAction.Wifi("Legacy", "12345", 1, 2).needsManualSetup)
        assertFalse(action.needsManualSetup)
    }

    @Test
    fun nearbyEnforcesItsOwnTransportLimitWithoutTheHceLimit() {
        val largerThanHce = listOf(NfcWriteRecord.Text("x".repeat(MAX_EMULATED_NDEF_BYTES + 1)))
        if (MAX_NEARBY_NDEF_BYTES > MAX_EMULATED_NDEF_BYTES + 100) {
            assertTrue(createNearbyPayload(largerThanHce).size > MAX_EMULATED_NDEF_BYTES)
        }
        try {
            createNearbyPayload(listOf(NfcWriteRecord.Text("x".repeat(MAX_NEARBY_NDEF_BYTES))))
            fail("Oversized Nearby data must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            createNearbyPayload(listOf(NfcWriteRecord.Text("valid"), NfcWriteRecord.Application("invalid package")))
            fail("All records must be validated")
        } catch (error: InvalidWriteRecordException) { assertEquals(1, error.recordIndex) }
    }
}
