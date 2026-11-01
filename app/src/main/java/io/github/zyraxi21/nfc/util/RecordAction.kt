package io.github.zyraxi21.nfc.util

import android.content.Intent
import androidx.core.net.toUri
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings

/** 仅描述用户可点击的操作，解析记录不会执行任何操作。 */
sealed interface RecordAction {
    data class Web(val uri: String) : RecordAction
    data class Application(val packageName: String) : RecordAction
    data class Bluetooth(val mac: String) : RecordAction
    data class Wifi(val ssid: String, val password: String, val auth: Int, val encryption: Int) : RecordAction {
        val needsManualSetup: Boolean get() = when (auth) {
            0x0001 -> encryption != 0x0001 || password.isNotEmpty()
            0x0002, 0x0020, 0x0022 -> encryption !in listOf(4, 8, 12) ||
                password.length !in 8..63 || password.any { it !in ' '..'~' }
            else -> true
        }
    }
}

internal fun webRecordIntent(action: RecordAction.Web): Intent {
    val uri = action.uri.toUri()
    require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrEmpty())
    return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
}

internal fun wifiRecordIntent(action: RecordAction.Wifi): Intent {
    require(!action.needsManualSetup)
    val builder = WifiNetworkSuggestion.Builder().setSsid(action.ssid)
    if (action.auth != 1) builder.setWpa2Passphrase(action.password)
    return Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
        .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(builder.build()))
}
