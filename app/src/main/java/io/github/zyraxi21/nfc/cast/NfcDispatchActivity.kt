package io.github.zyraxi21.nfc.cast

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Toast
import io.github.zyraxi21.nfc.MainActivity

/** 专用系统 NFC 入口，避免给桌面启动入口施加 Android 17 的 NFC 专用权限。 */
class NfcDispatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)?.toList().orEmpty()
            val invite = inviteFromMessages(messages)
            if (invite != null) startActivity(Intent(this, CastActivity::class.java).putExtra(CastActivity.EXTRA_INVITE, invite.encode()))
            else startActivity(Intent(this, MainActivity::class.java).setAction(intent.action).putExtras(intent)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) { Toast.makeText(this, "屏幕共享邀请已失效，请让对方重新生成", Toast.LENGTH_LONG).show() }
        finish()
    }
}
