package io.github.zyraxi21.nfc.cast

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zyraxi21.nfc.MainActivity
import io.github.zyraxi21.nfc.MyHostApduService
import io.github.zyraxi21.nfc.util.NfcRecordKind
import io.github.zyraxi21.nfc.util.RecordAction
import io.github.zyraxi21.nfc.util.parseNfcReadResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CastInviteInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun generatedInviteIsStandardNdefWithAppLaunchRecordAndManualReaderAction() {
        val invite = CastInvite.create()
        val message = NdefMessage(invite.ndef(context.packageName).toByteArray())
        assertEquals(CastInvite.MIME, message.records.first().toMimeType())
        assertEquals(context.packageName, String(message.records[1].payload))
        assertEquals(invite, inviteFromMessages(listOf(message)))
        val parsed = parseNfcReadResult(context, listOf(message))
        assertEquals(NfcRecordKind.SCREEN_SHARE, parsed.records.first().kind)
        val action = parsed.records.first().actions.single() as RecordAction.ScreenShare
        assertEquals(invite, CastInvite.decode(action.inviteBytes))
        assertFalse(parsed.records.first().fields.any { it.value.contains(invite.secret) })
    }

    @Test fun expiredAndUnsupportedInvitesKeepRawBytesWithoutActions() {
        listOf(CastInvite.create(System.currentTimeMillis() - 300_000).encode(), "{\"v\":2}".toByteArray()).forEach { bytes ->
            val message = NdefMessage(arrayOf(NdefRecord.createMime(CastInvite.MIME, bytes)))
            val record = parseNfcReadResult(context, listOf(message)).records.single()
            assertNotNull(record.warningResId)
            assertArrayEquals(bytes, record.payload)
            assertTrue(record.actions.isEmpty())
        }
    }

    @Test fun standardNfcLaunchTargetsProtectedDispatcherAndViewerAllowsRotation() {
        val intent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED).setType(CastInvite.MIME).setPackage(context.packageName)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = requireNotNull(context.packageManager.resolveActivity(intent, 0)).activityInfo
        assertEquals(NfcDispatchActivity::class.java.name, resolved.name)
        if (Build.VERSION.SDK_INT >= 37) assertEquals("android.permission.DISPATCH_NFC_MESSAGE", resolved.permission)
        val main = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertNull(main.permission)
        val viewer = context.packageManager.getActivityInfo(ComponentName(context, CastActivity::class.java), 0)
        assertFalse(viewer.exported)
        assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, viewer.screenOrientation)
    }

    @Test fun hceRefusesExpiredInviteEvenIfItWasCachedInMemory() {
        try {
            MyHostApduService.setEmulatedData(context,
                CastInvite.create(System.currentTimeMillis() - 300_000).ndef(context.packageName).toByteArray(), CastSessionService.INVITE_TYPE)
            // APDU 读取与 Type 4 文件读取都从此缓存入口取数据。
            assertNull(MyHostApduService.emulatedDataOrRestore(context))
            assertNull(MyHostApduService.emulatedData)
        } finally { MyHostApduService.setEmulatedData(context, null) }
    }
}
