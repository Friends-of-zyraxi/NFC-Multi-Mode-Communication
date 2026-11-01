package io.github.zyraxi21.nfc.util

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.google.android.gms.nearby.connection.ConnectionsClient
import io.github.zyraxi21.nfc.R

const val MAX_NEARBY_NDEF_BYTES = ConnectionsClient.MAX_BYTES_DATA_SIZE

fun createNearbyPayload(records: List<NfcWriteRecord>): ByteArray {
    val message = createNfcWriteMessage(records)
    require(message.byteArrayLength <= MAX_NEARBY_NDEF_BYTES) { "Nearby payload is too large" }
    return message.toByteArray()
}

fun parseNearbyPayload(context: Context, bytes: ByteArray): NfcReadResult = try {
    require(bytes.size <= MAX_NEARBY_NDEF_BYTES)
    parseNfcReadResult(context, listOf(NdefMessage(bytes)))
} catch (_: Exception) {
    NfcReadResult(
        records = listOf(NfcReadRecord(NfcRecordKind.DATA, emptyList(), bytes.copyOf(),
            "application/octet-stream", NdefRecord.TNF_MIME_MEDIA, byteArrayOf(),
            warningResId = R.string.p2p_invalid_payload)),
        messageBytes = bytes.size,
    )
}
