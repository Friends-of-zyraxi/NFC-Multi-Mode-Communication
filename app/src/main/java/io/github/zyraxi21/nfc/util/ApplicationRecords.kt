package io.github.zyraxi21.nfc.util

import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.text.Collator

data class InstalledApplication(val label: String, val packageName: String)

private val applicationPackagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

fun isValidApplicationPackageName(packageName: String): Boolean =
    applicationPackagePattern.matches(packageName.trim())

/** 写卡和 HCE 共用标准 AAR，由扫描设备的系统按包名打开应用。 */
fun createApplicationNdefMessage(packageName: String): NdefMessage {
    val normalized = packageName.trim()
    require(isValidApplicationPackageName(normalized)) { "Invalid application package name" }
    return NdefMessage(NdefRecord.createApplicationRecord(normalized))
}

/** 只列出具有启动入口的本机应用，同一包的多个入口合并为一项。应在 IO 线程调用。 */
fun loadInstalledApplications(packageManager: PackageManager): List<InstalledApplication> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance()
    return packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        .map { resolved ->
            val application = resolved.activityInfo.applicationInfo
            InstalledApplication(
                label = application.loadLabel(packageManager).toString(),
                packageName = application.packageName
            )
        }
        .distinctBy { it.packageName }
        .sortedWith { first, second ->
            val labelOrder = collator.compare(first.label, second.label)
            if (labelOrder != 0) labelOrder else first.packageName.compareTo(second.packageName)
        }
}
