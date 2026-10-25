package io.github.zyraxi21.nfc.ui.theme

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.microsoft.fluentui.theme.token.controlTokens.BottomSheetInfo
import com.microsoft.fluentui.theme.token.controlTokens.BottomSheetTokens
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import com.microsoft.fluentui.theme.token.controlTokens.SheetAccessibilityAnnouncement
import com.microsoft.fluentui.tokenized.bottomsheet.BottomSheet
import com.microsoft.fluentui.tokenized.bottomsheet.BottomSheetValue
import com.microsoft.fluentui.tokenized.bottomsheet.rememberBottomSheetState
import com.microsoft.fluentui.tokenized.progress.CircularProgressIndicator
import com.microsoft.fluentui.tokenized.progress.LinearProgressIndicator
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.update.RELEASES_URL
import io.github.zyraxi21.nfc.update.REPOSITORY_URL
import io.github.zyraxi21.nfc.update.ReleaseNotesFormatter
import io.github.zyraxi21.nfc.update.UpdateMessage
import io.github.zyraxi21.nfc.update.UpdateUiState
import io.github.zyraxi21.nfc.update.UpdateViewModel
import kotlinx.coroutines.launch

/** 原生 Fluent 2 关于面板；更新由 ViewModel 管理，重建界面或关闭面板都不丢失任务。 */
@Composable
fun AboutBottomSheet(
    updateViewModel: UpdateViewModel? = null,
    content: @Composable (openAbout: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val factory = remember(context) { UpdateViewModel.Factory(context) }
    val owner = LocalViewModelStoreOwner.current
    // 预览没有 Activity 的状态容器，不查询系统下载器或发起联网请求。
    val vm: UpdateViewModel = updateViewModel ?: if (owner == null) remember(factory) {
        factory.create(restoreDownloads = false)
    } else viewModel<UpdateViewModel>(factory = factory)
    val updateState by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val dialogVisible by vm.dialogVisible.collectAsStateWithLifecycle()
    val versionName = vm.installedVersion ?: stringResource(R.string.about_version_unknown)
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(BottomSheetValue.Hidden)
    val title = stringResource(R.string.about_title)
    val tokens = remember {
        object : BottomSheetTokens() {
            @Composable
            override fun backgroundBrush(bottomSheetInfo: BottomSheetInfo): Brush = SolidColor(AppTheme.surface)

            @Composable
            override fun cornerRadius(bottomSheetInfo: BottomSheetInfo): Dp = FluentShapes.radius

            @Composable
            override fun elevation(bottomSheetInfo: BottomSheetInfo): Dp = FluentElevation.sheet
        }
    }
    val closeSheet: () -> Unit = { scope.launch { sheetState.hide() } }
    BackHandler(enabled = sheetState.isVisible && !dialogVisible, onBack = closeSheet)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = PageMetrics.gutterFor(maxWidth)
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val maxSheetHeight = (maxHeight - statusBarHeight - FluentSpacing.massive).coerceAtLeast(1.dp)
        BottomSheet(
            modifier = Modifier.fillMaxSize().clipToBounds(),
            sheetState = sheetState,
            // 复用记账本的固定停靠模式，避免 Fluent 的 slideOver 分支反复修改测量高度。
            slideOver = false,
            peekHeight = maxSheetHeight.coerceAtMost(600.dp),
            expandable = false,
            scrimVisible = true,
            enableSwipeDismiss = true,
            bottomSheetTokens = tokens,
            talkbackAnnouncement = SheetAccessibilityAnnouncement(
                expandedToShown = stringResource(R.string.about_sheet_collapsed),
                expandedToCollapsed = stringResource(R.string.about_sheet_closed),
                shownToExpanded = stringResource(R.string.about_sheet_expanded),
                shownToCollapsed = stringResource(R.string.about_sheet_closed),
                collapsedToExpanded = stringResource(R.string.about_sheet_expanded),
                collapsedToShown = stringResource(R.string.about_sheet_opened)
            ),
            sheetContent = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxSheetHeight)
                        .verticalScroll(rememberScrollState()).navigationBarsPadding()
                        .padding(horizontal = gutter, vertical = FluentSpacing.mPlus)
                        .semantics { paneTitle = title }.testTag("about-sheet"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FluentSpacing.mPlus)
                ) {
                    FluentText(title, FluentTextStyle.Title2, modifier = Modifier.semantics { heading() })
                    Image(painter = painterResource(R.drawable.nfc_logo_round), contentDescription = null,
                        modifier = Modifier.size(64.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(FluentSpacing.xs)) {
                        FluentText(stringResource(R.string.app_name), FluentTextStyle.Title3)
                        FluentText(stringResource(R.string.about_description), FluentTextStyle.Body2, color = AppTheme.textSecondary)
                        FluentText(stringResource(R.string.about_version, versionName), FluentTextStyle.Body2)
                        FluentText(stringResource(R.string.about_maintainer), FluentTextStyle.Caption1, color = AppTheme.textSecondary)
                        FluentText(stringResource(R.string.about_license), FluentTextStyle.Caption1, color = AppTheme.textSecondary)
                    }
                    UpdateSection(updateState, message, vm)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
                        FluentButton(text = stringResource(R.string.about_repository), style = ButtonStyle.OutlinedButton,
                            onClick = { openAboutIntent(context, Intent(Intent.ACTION_VIEW, REPOSITORY_URL.toUri()), R.string.update_error_open_link) },
                            modifier = Modifier.weight(1f))
                        FluentButton(text = stringResource(R.string.about_close), onClick = closeSheet,
                            style = ButtonStyle.OutlinedButton, modifier = Modifier.weight(1f))
                    }
                }
            }
        ) {
            content { scope.launch { sheetState.show() } }
        }
        if (dialogVisible) {
            when (val state = updateState) {
                is UpdateUiState.Available -> UpdateAvailableDialog(state, vm, maxSheetHeight)
                is UpdateUiState.Downloading -> UpdateProgressDialog(state, vm, maxSheetHeight)
                is UpdateUiState.ReadyToInstall -> UpdateInstallDialog(state, message, vm, maxSheetHeight)
                else -> Unit
            }
        }
    }
}

@Composable
private fun UpdateSection(state: UpdateUiState, message: UpdateMessage?, vm: UpdateViewModel) {
    val context = LocalContext.current
    FluentCard(Modifier.fillMaxWidth().testTag("update-section")) {
        Column(
            modifier = Modifier.padding(FluentSpacing.mPlus).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FluentSpacing.m)
        ) {
            FluentText(stringResource(R.string.update_title), FluentTextStyle.Body1Strong)
            when (state) {
                UpdateUiState.Idle -> if (message == null) {
                    FluentText(stringResource(if (vm.canCheck) R.string.update_hint else R.string.update_error_version),
                        FluentTextStyle.Body2, color = AppTheme.textSecondary)
                }
                UpdateUiState.Restoring -> FluentText(stringResource(R.string.update_restoring), FluentTextStyle.Body2)
                UpdateUiState.Checking -> {
                    CircularProgressIndicator()
                    FluentText(stringResource(R.string.update_checking), FluentTextStyle.Body2)
                }
                is UpdateUiState.Available -> {
                    FluentText(stringResource(R.string.update_available, state.release.versionName), FluentTextStyle.Body2Strong)
                    FluentButton(text = stringResource(R.string.update_view_details), onClick = vm::showUpdateDialog,
                        modifier = Modifier.fillMaxWidth())
                }
                is UpdateUiState.Downloading -> {
                    DownloadProgress(state.progress)
                    FluentButton(text = stringResource(R.string.update_cancel_download), onClick = vm::cancelDownload,
                        enabled = state.canCancel, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.OutlinedButton)
                }
                is UpdateUiState.ReadyToInstall -> {
                    FluentButton(text = stringResource(R.string.update_install), onClick = vm::installUpdate,
                        modifier = Modifier.fillMaxWidth())
                    InstallGuidance(state)
                }
            }
            UpdateFeedback(message)
            if (state == UpdateUiState.Idle || state == UpdateUiState.Checking || state == UpdateUiState.Restoring) {
                FluentButton(text = stringResource(if (state == UpdateUiState.Checking) R.string.update_checking else R.string.update_check),
                    onClick = vm::checkForUpdates, enabled = state == UpdateUiState.Idle && vm.canCheck,
                    modifier = Modifier.fillMaxWidth())
            }
            if (state is UpdateUiState.Downloading || state is UpdateUiState.ReadyToInstall) {
                FluentButton(text = stringResource(R.string.update_view_downloads), style = ButtonStyle.OutlinedButton,
                    onClick = { openAboutIntent(context, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS), R.string.update_error_open_downloads) },
                    modifier = Modifier.fillMaxWidth())
            }
            if (state is UpdateUiState.Available || message?.isError == true) {
                val releaseUrl = (state as? UpdateUiState.Available)?.release?.pageUrl ?: RELEASES_URL
                FluentButton(text = stringResource(R.string.update_view_release), style = ButtonStyle.OutlinedButton,
                    onClick = { openAboutIntent(context, Intent(Intent.ACTION_VIEW, releaseUrl.toUri()), R.string.update_error_open_link) },
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun UpdateAvailableDialog(state: UpdateUiState.Available, vm: UpdateViewModel, maxHeight: Dp) {
    val context = LocalContext.current
    val notes = remember(state.release.notes) { ReleaseNotesFormatter.format(state.release.notes) }
    val noNotes = stringResource(R.string.update_no_notes)
    UpdateDialog(stringResource(R.string.update_available_title), vm::dismissUpdate, maxHeight) {
        FluentText(stringResource(R.string.update_available, state.release.versionName), FluentTextStyle.Body1Strong)
        if (state.asset == null) {
            FluentText(stringResource(R.string.update_no_compatible_apk), FluentTextStyle.Body2, color = AppTheme.warning)
        } else {
            FluentText(stringResource(R.string.update_asset, state.asset.name, Formatter.formatFileSize(context, state.asset.sizeBytes)),
                FluentTextStyle.Caption1, color = AppTheme.textSecondary)
        }
        FluentText(stringResource(R.string.update_release_notes), FluentTextStyle.Body2Strong)
        FluentText(notes.ifBlank { noNotes }, FluentTextStyle.Body2,
            modifier = Modifier.heightIn(max = maxHeight * 0.4f).verticalScroll(rememberScrollState()),
            color = AppTheme.textSecondary, centered = false)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
            FluentButton(text = stringResource(R.string.update_later), onClick = vm::dismissUpdate,
                style = ButtonStyle.OutlinedButton, modifier = Modifier.weight(1f))
            FluentButton(text = stringResource(R.string.update_download_now), onClick = vm::startDownload,
                enabled = state.asset != null, modifier = Modifier.weight(1f))
        }
        FluentButton(text = stringResource(R.string.update_view_release), style = ButtonStyle.TextButton,
            onClick = { openAboutIntent(context, Intent(Intent.ACTION_VIEW, state.release.pageUrl.toUri()), R.string.update_error_open_link) },
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun UpdateProgressDialog(state: UpdateUiState.Downloading, vm: UpdateViewModel, maxHeight: Dp) {
    UpdateDialog(stringResource(R.string.update_downloading), vm::dismissUpdate, maxHeight) {
        DownloadProgress(state.progress)
        FluentButton(text = stringResource(R.string.update_cancel_download), onClick = vm::cancelDownload,
            enabled = state.canCancel, modifier = Modifier.fillMaxWidth())
        FluentButton(text = stringResource(R.string.update_background_download), onClick = vm::dismissUpdate,
            style = ButtonStyle.OutlinedButton, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DownloadProgress(progress: Int) {
    val label = if (progress >= 0) stringResource(R.string.update_progress, progress)
        else stringResource(R.string.update_progress_unknown)
    FluentText(label, FluentTextStyle.Body2)
    if (progress >= 0) LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun UpdateInstallDialog(state: UpdateUiState.ReadyToInstall, message: UpdateMessage?, vm: UpdateViewModel, maxHeight: Dp) {
    UpdateDialog(stringResource(R.string.update_ready_title), vm::dismissUpdate, maxHeight) {
        FluentText(stringResource(R.string.update_download_complete), FluentTextStyle.Body2)
        InstallGuidance(state)
        if (message?.isError == true) UpdateFeedback(message)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FluentSpacing.m)) {
            FluentButton(text = stringResource(R.string.update_later), onClick = vm::dismissUpdate,
                style = ButtonStyle.OutlinedButton, modifier = Modifier.weight(1f))
            FluentButton(text = stringResource(R.string.update_install), onClick = vm::installUpdate, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun InstallGuidance(state: UpdateUiState.ReadyToInstall) {
    FluentText(stringResource(if (state.permissionRequired) R.string.update_install_permission_message else R.string.update_install_hint),
        FluentTextStyle.Body2, color = AppTheme.textSecondary)
    if (state.settingsMissing) {
        FluentText(stringResource(R.string.update_install_manual_path), FluentTextStyle.Body2, color = AppTheme.warning)
    }
}

@Composable
private fun UpdateFeedback(message: UpdateMessage?) {
    if (message != null) FluentText(stringResource(message.resource), FluentTextStyle.Body2,
        color = if (message.isError) AppTheme.danger else AppTheme.textSecondary)
}

@Composable
private fun UpdateDialog(title: String, onDismiss: () -> Unit, maxHeight: Dp, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        FluentCard(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("update-dialog")) {
            Column(Modifier.padding(FluentSpacing.xl).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FluentSpacing.mPlus)) {
                FluentText(title, FluentTextStyle.Title3, modifier = Modifier.semantics { heading() })
                content()
            }
        }
    }
}

private fun openAboutIntent(context: Context, intent: Intent, errorRes: Int) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, errorRes, Toast.LENGTH_LONG).show()
    }
}
