package com.adbhelper.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adbhelper.app.R
import com.adbhelper.app.ui.viewmodels.LocalAppInfo
import com.adbhelper.app.ui.viewmodels.AppDetail
import com.adbhelper.app.ui.viewmodels.TransferDirection
import com.adbhelper.app.ui.viewmodels.TransferState

@Composable
fun PushOptionsDialog(
    onSelectFile: () -> Unit,
    onSelectInstalledApp: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.push_apk_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.push_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedCard(onClick = onSelectFile, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.push_select_local_file), style = MaterialTheme.typography.titleSmall)
                            Text("APK", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedCard(onClick = onSelectInstalledApp, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.push_select_installed_app), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.push_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun LocalAppPickerDialog(
    apps: List<LocalAppInfo>,
    onSelect: (LocalAppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = apps.filter {
        searchQuery.isBlank() ||
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_local_app)) },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_local_apps)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) }
                    },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_local_apps), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            ListItem(
                                headlineContent = { Text(app.appName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { onSelect(app) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun TransferProgressDialog(
    state: TransferState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val progress = state.progress
    val hasResult = state.resultMessage != null
    AlertDialog(
        onDismissRequest = { if (hasResult) onDismiss() },
        title = {
            Text(
                when {
                    hasResult && state.isError -> stringResource(R.string.transfer_failed)
                    hasResult -> stringResource(R.string.success)
                    state.direction == TransferDirection.PUSH -> stringResource(R.string.transfer_pushing)
                    else -> stringResource(R.string.transfer_pulling)
                }
            )
        },
        text = {
            Column {
                Text(state.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(16.dp))
                if (hasResult) {
                    Text(
                        state.resultMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (progress.speed == "安装中…") stringResource(R.string.transfer_installing)
                            else stringResource(R.string.transfer_loading),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (hasResult) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = {
            if (!hasResult && !progress.isComplete) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.error) }
            }
        }
    )
}

@Composable
fun AppDetailDialog(
    detail: AppDetail?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_app_info)) },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (detail != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow(stringResource(R.string.detail_package_name), detail.packageName)
                    DetailRow(stringResource(R.string.detail_version), "${detail.versionName} (${detail.versionCode})")
                    DetailRow(stringResource(R.string.detail_apk_size), detail.apkSize.ifBlank { "-" })
                    DetailRow(stringResource(R.string.detail_min_sdk), detail.minSdkVersion.ifBlank { "-" })
                    DetailRow(stringResource(R.string.detail_target_sdk), detail.targetSdkVersion.ifBlank { "-" })
                    DetailRow(stringResource(R.string.detail_first_install), detail.firstInstallTime.ifBlank { "-" })
                    DetailRow(stringResource(R.string.detail_last_update), detail.lastUpdateTime.ifBlank { "-" })
                    DetailRow(stringResource(R.string.detail_launch_activity), detail.launchActivity.ifBlank { "-" })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboardManager.setText(AnnotatedString(value))
                copied = true
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (copied) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
}
