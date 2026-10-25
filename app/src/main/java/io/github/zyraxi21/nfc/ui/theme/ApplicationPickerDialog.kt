package io.github.zyraxi21.nfc.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.microsoft.fluentui.theme.token.controlTokens.ButtonStyle
import com.microsoft.fluentui.tokenized.progress.CircularProgressIndicator
import io.github.zyraxi21.nfc.R
import io.github.zyraxi21.nfc.util.InstalledApplication
import io.github.zyraxi21.nfc.util.loadInstalledApplications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ApplicationPickerDialog(
    onApplicationSelected: (InstalledApplication) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val result by produceState<Result<List<InstalledApplication>>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadInstalledApplications(context.packageManager) }
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    val applications = result?.getOrNull().orEmpty()
    val filtered = remember(applications, searchQuery) {
        val query = searchQuery.trim()
        applications.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        FluentCard(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth().imePadding().padding(FluentSpacing.mPlus),
                verticalArrangement = Arrangement.spacedBy(FluentSpacing.m)
            ) {
                FluentText(
                    text = stringResource(R.string.title_select_application),
                    style = FluentTextStyle.Title3
                )
                FluentTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = stringResource(R.string.label_search_application)
                )
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        result == null -> CircularProgressIndicator()
                        result?.isFailure == true -> FluentText(
                            text = stringResource(R.string.msg_application_list_failed),
                            style = FluentTextStyle.Body2,
                            color = AppTheme.danger
                        )
                        filtered.isEmpty() -> FluentText(
                            text = stringResource(R.string.msg_no_applications),
                            style = FluentTextStyle.Body2,
                            color = AppTheme.textHint
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(filtered, key = { it.packageName }) { application ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onApplicationSelected(application) }
                                        .heightIn(min = PageMetrics.minTouchTarget)
                                        .padding(vertical = FluentSpacing.m),
                                    verticalArrangement = Arrangement.spacedBy(FluentSpacing.xs)
                                ) {
                                    FluentText(
                                        text = application.label,
                                        style = FluentTextStyle.Body1Strong,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    FluentText(
                                        text = application.packageName,
                                        style = FluentTextStyle.Caption1,
                                        color = AppTheme.textHint
                                    )
                                }
                            }
                        }
                    }
                }
                FluentButton(
                    onClick = onDismissRequest,
                    text = stringResource(R.string.button_cancel),
                    style = ButtonStyle.TextButton,
                    modifier = Modifier.align(Alignment.End).heightIn(min = PageMetrics.minTouchTarget)
                )
            }
        }
    }
}
