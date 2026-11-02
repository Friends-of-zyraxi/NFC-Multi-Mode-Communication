package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.util.NfcReadRecord
import io.github.zyraxi21.nfc.util.NfcReadResult
import io.github.zyraxi21.nfc.util.NfcRecordField
import io.github.zyraxi21.nfc.util.NfcRecordKind
import io.github.zyraxi21.nfc.util.copyText
import io.github.zyraxi21.nfc.util.toDisplayHex
import io.github.zyraxi21.nfc.util.RecordAction

/** 一次扫描对应一个结果快照；整页统一滚动，记录正文优先，协议细节按需展开。 */
@Composable
fun NFCReaderScreen(
    result: NfcReadResult?,
    isButtonVisible: Boolean,
    onRecordAction: ((RecordAction) -> Unit)? = null,
    onCheckNfcClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    if (result == null && LocalWideLayout.current) {
        CenteredStatusPage(Modifier.testTag("reader-results")) {
            FluentText(stringResource(R.string.reader_title), FluentTextStyle.Title3,
                modifier = Modifier.semantics { heading() })
            ReaderScanPrompt()
            if (isButtonVisible) FluentButton(onClick = onCheckNfcClick,
                text = stringResource(R.string.reader_check_nfc),
                modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget))
        }
        return
    }
    // 扫描新标签时回到列表顶部，并清除上一张标签的展开状态。
    key(result) {
        var tagDetailsExpanded by rememberSaveable { mutableStateOf(false) }
        val tagFields = remember(result, context) { result?.let { tagInformationFields(it, context) }.orEmpty() }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("reader-results"),
            contentPadding = PaddingValues(horizontal = LocalPageGutter.current, vertical = FluentSpacing.mPlus),
            verticalArrangement = Arrangement.spacedBy(FluentSpacing.mPlus),
        ) {
            item {
                FluentText(
                    text = stringResource(R.string.reader_title),
                    style = FluentTextStyle.Title3,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (isButtonVisible) {
                item {
                    FluentButton(
                        onClick = onCheckNfcClick,
                        text = stringResource(R.string.reader_check_nfc),
                        modifier = Modifier.fillMaxWidth().heightIn(min = PageMetrics.minTouchTarget),
                    )
                }
            }
            if (result == null) {
                item { ReaderScanPrompt() }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(FluentSpacing.s)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FluentSpacing.xs)) {
                                FluentText(
                                    text = stringResource(if (result.isEmulated) R.string.msg_hce_tag else R.string.reader_tag_title),
                                    style = FluentTextStyle.Body1Strong,
                                    centered = false,
                                )
                                FluentText(
                                    text = stringResource(R.string.reader_record_count, result.records.size),
                                    style = FluentTextStyle.Caption1,
                                    color = AppTheme.textSecondary,
                                    centered = false,
                                )
                            }
                            if (result.records.isNotEmpty()) {
                                ReaderAction(stringResource(R.string.reader_copy_all)) {
                                    clipboard.setText(AnnotatedString(result.copyText(context)))
                                }
                            }
                        }
                        if (result.capacity != null || result.records.isNotEmpty()) {
                            FluentText(
                                text = if (result.capacity != null) stringResource(R.string.reader_used_capacity, result.messageBytes, result.capacity)
                                    else stringResource(R.string.reader_message_size, stringResource(R.string.reader_exact_bytes, result.messageBytes)),
                                style = FluentTextStyle.Caption1,
                                color = AppTheme.textSecondary,
                                centered = false,
                            )
                        }
                    }
                }
                if (tagFields.isNotEmpty()) {
                    item {
                        ReaderAction(stringResource(if (tagDetailsExpanded) R.string.reader_tag_details_hide else R.string.reader_tag_details_show)) {
                            tagDetailsExpanded = !tagDetailsExpanded
                        }
                    }
                    if (tagDetailsExpanded) {
                        items(tagFields) { ReaderField(it) }
                    }
                }
                if (result.records.isEmpty()) {
                    item {
                        FluentCard(Modifier.fillMaxWidth()) {
                            FluentText(
                                text = stringResource(result.noticeResId ?: R.string.reader_no_records),
                                style = FluentTextStyle.Body2,
                                color = AppTheme.textSecondary,
                                centered = false,
                                modifier = Modifier.padding(FluentSpacing.mPlus),
                            )
                        }
                    }
                }
                itemsIndexed(result.records, key = { index, _ -> index }) { index, record ->
                    ReaderRecordCard(record, index, onRecordAction)
                }
            }
        }
    }
}

@Composable
private fun ReaderScanPrompt() {
    if (LocalWideLayout.current) {
        FluentCard(Modifier.fillMaxWidth().testTag("reader-scan-prompt")) {
            Row(Modifier.padding(FluentSpacing.xl), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FluentSpacing.xl)) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    colorFilter = ColorFilter.tint(AppTheme.brand), modifier = Modifier.size(72.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
                    FluentText(stringResource(R.string.reader_scan_hint), FluentTextStyle.Body1Strong, centered = false)
                    ReaderDivider()
                    FluentText(stringResource(R.string.reader_supported_formats), FluentTextStyle.Caption1,
                        color = AppTheme.textSecondary, centered = false)
                }
            }
        }
        return
    }
    FluentCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FluentSpacing.xl, vertical = FluentSpacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FluentSpacing.mPlus),
        ) {
            Image(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                colorFilter = ColorFilter.tint(AppTheme.brand),
                modifier = Modifier.size(88.dp),
            )
            FluentText(stringResource(R.string.reader_scan_hint), FluentTextStyle.Body1Strong)
            ReaderDivider()
            FluentText(stringResource(R.string.reader_supported_formats), FluentTextStyle.Caption1, color = AppTheme.textSecondary)
        }
    }
}

@Composable
internal fun ReaderRecordCard(record: NfcReadRecord, index: Int, onRecordAction: ((RecordAction) -> Unit)? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var rawExpanded by rememberSaveable { mutableStateOf(false) }
    FluentCard(Modifier.fillMaxWidth().testTag("reader-record-$index")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(FluentSpacing.mPlus),
            verticalArrangement = Arrangement.spacedBy(FluentSpacing.mPlus),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FluentSpacing.xs)) {
                    FluentText(
                        stringResource(record.kind.titleResId), FluentTextStyle.Body1Strong,
                        centered = false, color = AppTheme.brand,
                        modifier = Modifier.semantics { heading() },
                    )
                    FluentText(
                        stringResource(R.string.reader_record_number, index + 1), FluentTextStyle.Caption1,
                        centered = false, color = AppTheme.textSecondary,
                    )
                }
                if (record.fields.isNotEmpty() || record.payload.isNotEmpty()) {
                    ReaderAction(stringResource(R.string.reader_copy_record), Modifier.testTag("reader-copy-$index")) {
                        clipboard.setText(AnnotatedString(record.copyText(context)))
                    }
                }
            }
            ReaderDivider()
            if (record.kind == NfcRecordKind.DATA && record.type.isNotEmpty()) {
                ReaderField(NfcRecordField(R.string.reader_field_record_type, record.type, monospaced = true))
            }
            record.fields.forEach { field -> ReaderField(field) }
            if (record.fields.isEmpty() && record.warningResId == null) {
                FluentText(
                    stringResource(if (record.kind == NfcRecordKind.EMPTY) R.string.reader_empty_record else R.string.reader_unknown_record),
                    FluentTextStyle.Body2, centered = false, color = AppTheme.textSecondary,
                )
            }
            record.warningResId?.let {
                FluentText(stringResource(it), FluentTextStyle.Body2, centered = false, color = AppTheme.danger)
            }
            RecordActionButtons(record.actions, onRecordAction)
            ReaderAction(
                stringResource(if (rawExpanded) R.string.reader_raw_hide else R.string.reader_raw_show),
                Modifier.testTag("reader-raw-toggle-$index"),
            ) { rawExpanded = !rawExpanded }
            if (rawExpanded) {
                ReaderDivider()
                Column(
                    modifier = Modifier.testTag("reader-raw-$index"),
                    verticalArrangement = Arrangement.spacedBy(FluentSpacing.m),
                ) {
                    ReaderField(NfcRecordField(R.string.reader_field_record_type, record.type.ifEmpty { "TNF " + record.tnf }, true))
                    FluentText(
                        "TNF " + record.tnf + " / " + stringResource(R.string.reader_payload_bytes, record.payload.size),
                        FluentTextStyle.Caption1, centered = false, color = AppTheme.textSecondary,
                    )
                    if (record.id.isNotEmpty()) ReaderField(NfcRecordField(R.string.reader_field_record_id, record.id.toDisplayHex(), true))
                    record.details.forEach { ReaderField(it) }
                    val previewBytes = 256
                    val preview = remember(record) {
                        record.payload.take(previewBytes).chunked(16).joinToString("\n") { it.toByteArray().toDisplayHex() }
                    }
                    ReaderField(NfcRecordField(R.string.reader_field_payload, preview, true))
                    if (record.payload.size > previewBytes) {
                        FluentText(
                            stringResource(R.string.reader_raw_preview, previewBytes), FluentTextStyle.Caption1,
                            centered = false, color = AppTheme.textSecondary,
                        )
                    }
                    if (record.payload.isNotEmpty()) {
                        ReaderAction(stringResource(R.string.reader_raw_copy)) {
                            clipboard.setText(AnnotatedString(record.payload.toDisplayHex()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderField(field: NfcRecordField) {
    Column(verticalArrangement = Arrangement.spacedBy(FluentSpacing.xs)) {
        FluentText(stringResource(field.labelResId), FluentTextStyle.Caption1, centered = false, color = AppTheme.textSecondary)
        ReaderValue(field.value, field.monospaced)
    }
}

/** 正文左对齐并支持选择；折叠只影响显示，复制始终保留完整原文。 */
@Composable
private fun ReaderValue(value: String, monospaced: Boolean) {
    var expanded by rememberSaveable(value) { mutableStateOf(false) }
    var overflows by remember(value) { mutableStateOf(false) }
    SelectionContainer {
        FluentText(
            text = value.ifEmpty { stringResource(R.string.reader_empty_value) },
            style = FluentTextStyle.Body2,
            centered = false,
            fontFamily = if (monospaced) FontFamily.Monospace else null,
            modifier = Modifier.fillMaxWidth(),
            maxLines = if (expanded) Int.MAX_VALUE else 8,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
    }
    if (expanded || overflows) {
        ReaderAction(stringResource(if (expanded) R.string.reader_collapse_text else R.string.reader_expand_text)) {
            expanded = !expanded
        }
    }
}

private fun tagInformationFields(result: NfcReadResult, context: android.content.Context): List<NfcRecordField> = buildList {
    result.tagId?.let { add(NfcRecordField(R.string.reader_field_uid, it, true)) }
    if (result.technologies.isNotEmpty()) add(NfcRecordField(R.string.reader_field_tech, result.technologies.joinToString(" / ")))
    addAll(result.tagDetails)
    result.capacity?.let {
        add(NfcRecordField(R.string.reader_field_capacity, context.getString(R.string.reader_exact_bytes, it)))
    }
    result.isWritable?.let {
        add(NfcRecordField(R.string.reader_field_access, context.getString(if (it) R.string.reader_tag_writable else R.string.reader_tag_readonly)))
    }
}

@Composable
private fun ReaderAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FluentButton(text = text, onClick = onClick, style = ButtonStyle.TextButton, modifier = modifier.heightIn(min = PageMetrics.minTouchTarget))
}

@Composable
private fun ReaderDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.strokeSubtle))
}

private fun previewResult() = NfcReadResult(
    records = listOf(
        NfcReadRecord(
            NfcRecordKind.TEXT,
            listOf(NfcRecordField(R.string.reader_field_text, "你好，NFC！\n每条记录独立展示，长内容也能轻松阅读。")),
            "Hello, NFC!".toByteArray(), "T", 1, byteArrayOf(),
        ),
        NfcReadRecord(
            NfcRecordKind.APPLICATION,
            listOf(NfcRecordField(R.string.reader_field_package, "com.android.settings", true)),
            "com.android.settings".toByteArray(), "android.com:pkg", 4, byteArrayOf(),
        ),
    ),
    messageBytes = 72,
    tagId = "04 A1 B2 C3 D4 E5 F6",
    technologies = listOf("NfcA", "Ndef"),
)

@Preview(name = "读卡 浅色", showBackground = true)
@Composable
private fun NFCReaderPreviewLight() {
    MyApplicationTheme(darkTheme = false, dynamicColor = false) { NFCReaderScreen(previewResult(), false) }
}

@Preview(name = "读卡 深色", showBackground = true)
@Composable
private fun NFCReaderPreviewDark() {
    MyApplicationTheme(darkTheme = true, dynamicColor = false) { NFCReaderScreen(previewResult(), false) }
}

@Preview(name = "读卡 等待扫描", showBackground = true)
@Composable
private fun NFCReaderPreviewEmpty() {
    MyApplicationTheme(darkTheme = false, dynamicColor = false) { NFCReaderScreen(null, false) }
}

@Preview(name = "读卡等待 横屏", showBackground = true, widthDp = 850, heightDp = 360)
@Preview(name = "读卡等待 平板", showBackground = true, widthDp = 1280, heightDp = 800)
@Preview(name = "读卡等待 横屏深色", showBackground = true, widthDp = 850, heightDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NFCReaderPreviewWideEmpty() {
    MyApplicationTheme(dynamicColor = false) {
        CompositionLocalProvider(LocalWideLayout provides true) { NFCReaderScreen(null, false) }
    }
}
